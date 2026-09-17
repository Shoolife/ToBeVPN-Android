package com.tobevpn.app.data.settings

import android.content.Context
import android.net.Uri
import com.tobevpn.app.data.local.PortableDisplayPreferences
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.repository.AppFilterRepository
import com.tobevpn.app.domain.model.AppFilterMode
import com.tobevpn.app.domain.model.AppFilterState
import com.tobevpn.app.domain.model.MAX_INTERFACE_SCALE
import com.tobevpn.app.domain.model.MIN_INTERFACE_SCALE
import com.tobevpn.app.domain.model.ThemeMode
import com.tobevpn.app.util.LocaleManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class SettingsTransferSelection(
    val language: Boolean = true,
    val theme: Boolean = true,
    val display: Boolean = true,
    val appRouting: Boolean = true,
) {
    val hasAny: Boolean get() = language || theme || display || appRouting
}

data class SettingsImportPreview(
    val document: SettingsBackupDocument,
    val available: SettingsTransferSelection,
) {
    val appCount: Int get() = document.appRouting?.packages?.size ?: 0
}

@Serializable
data class SettingsBackupDocument(
    val format: String = SettingsBackupCodec.FORMAT,
    val formatVersion: Int = SettingsBackupCodec.VERSION,
    val createdAtMillis: Long,
    val language: String? = null,
    val theme: String? = null,
    val display: PortableDisplaySettings? = null,
    val appRouting: PortableAppRoutingSettings? = null,
)

@Serializable
data class PortableDisplaySettings(
    val interfaceScale: Float,
    val fontScale: Float,
    val boldText: Boolean,
    val outlinedText: Boolean,
)

@Serializable
data class PortableAppRoutingSettings(
    val mode: String,
    val packages: List<String>,
)

internal object SettingsBackupCodec {
    const val FORMAT = "tobevpn-settings"
    const val VERSION = 1
    const val MAX_FILE_BYTES = 256 * 1024
    private const val MAX_PACKAGES = 2_000

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun encode(document: SettingsBackupDocument): String = json.encodeToString(document)

    fun decode(raw: String): SettingsBackupDocument {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) {
            "Settings file is too large"
        }
        val document = json.decodeFromString<SettingsBackupDocument>(raw)
        require(document.format == FORMAT) { "Not a ToBeVPN settings file" }
        require(document.formatVersion == VERSION) { "Unsupported settings file version" }
        require(document.language == null || document.language in setOf(LocaleManager.LANG_EN, LocaleManager.LANG_RU)) {
            "Unsupported language"
        }
        require(document.theme == null || document.theme in ThemeMode.entries.map { it.name }) {
            "Unsupported theme"
        }
        document.display?.let { display ->
            require(display.interfaceScale.isFinite() && display.interfaceScale in MIN_INTERFACE_SCALE..MAX_INTERFACE_SCALE) {
                "Invalid interface scale"
            }
            require(display.fontScale.isFinite() && display.fontScale in MIN_INTERFACE_SCALE..MAX_INTERFACE_SCALE) {
                "Invalid font scale"
            }
        }
        document.appRouting?.let { routing ->
            require(routing.mode in AppFilterMode.entries.map { it.name }) { "Invalid app routing mode" }
            require(routing.packages.size <= MAX_PACKAGES) { "Too many applications" }
            require(routing.packages.all(::isSafePackageName)) { "Invalid application identifier" }
        }
        require(
            document.language != null || document.theme != null ||
                document.display != null || document.appRouting != null,
        ) {
            "Settings file is empty"
        }
        return document
    }

    private fun isSafePackageName(value: String): Boolean {
        return value.length in 3..255 &&
            '.' in value &&
            value.none(Char::isWhitespace) &&
            value.all { it.isLetterOrDigit() || it == '_' || it == '.' }
    }
}

@Singleton
class SettingsTransferManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PrefsDataStore,
    private val appFilterRepository: AppFilterRepository,
) {
    suspend fun writeExport(uri: Uri, selection: SettingsTransferSelection) = withContext(Dispatchers.IO) {
        require(selection.hasAny) { "No settings selected" }
        val display = if (selection.display) {
            prefs.getPortableDisplayPreferences().let {
                PortableDisplaySettings(
                    interfaceScale = it.interfaceScale,
                    fontScale = it.fontScale,
                    boldText = it.boldText,
                    outlinedText = it.outlinedText,
                )
            }
        } else {
            null
        }
        val routing = if (selection.appRouting) {
            appFilterRepository.getSnapshot().let {
                PortableAppRoutingSettings(
                    mode = it.mode.name,
                    packages = it.selectedPackages.sorted(),
                )
            }
        } else {
            null
        }
        val document = SettingsBackupDocument(
            createdAtMillis = System.currentTimeMillis(),
            language = LocaleManager.current().takeIf { selection.language },
            theme = prefs.getThemeMode()
                ?.let(ThemeMode::fromName)
                ?.name
                ?.takeIf { selection.theme }
                ?: ThemeMode.DEFAULT.name.takeIf { selection.theme },
            display = display,
            appRouting = routing,
        )
        val encoded = SettingsBackupCodec.encode(document)
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(encoded)
        } ?: throw IOException("Unable to open destination")
    }

    suspend fun readImport(uri: Uri): SettingsImportPreview = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use {
            it.readLimitedBytes(SettingsBackupCodec.MAX_FILE_BYTES)
        } ?: throw IOException("Unable to open settings file")
        val document = SettingsBackupCodec.decode(bytes.toString(Charsets.UTF_8))
        SettingsImportPreview(
            document = document,
            available = SettingsTransferSelection(
                language = document.language != null,
                theme = document.theme != null,
                display = document.display != null,
                appRouting = document.appRouting != null,
            ),
        )
    }

    private fun InputStream.readLimitedBytes(limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "Settings file is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /** Returns a language tag that must be applied from the main thread. */
    suspend fun applyImport(
        preview: SettingsImportPreview,
        selection: SettingsTransferSelection,
    ): String? = withContext(Dispatchers.IO) {
        require(selection.hasAny) { "No settings selected" }
        val document = preview.document
        if (selection.theme) {
            val value = requireNotNull(document.theme) { "Theme setting is absent" }
            prefs.setThemeMode(ThemeMode.valueOf(value).name)
        }
        if (selection.display) {
            val value = requireNotNull(document.display) { "Display settings are absent" }
            prefs.setPortableDisplayPreferences(
                PortableDisplayPreferences(
                    interfaceScale = value.interfaceScale,
                    fontScale = value.fontScale,
                    boldText = value.boldText,
                    outlinedText = value.outlinedText,
                ),
            )
        }
        if (selection.appRouting) {
            val value = requireNotNull(document.appRouting) { "App routing settings are absent" }
            appFilterRepository.replaceState(
                AppFilterState(
                    mode = AppFilterMode.valueOf(value.mode),
                    selectedPackages = value.packages
                        .asSequence()
                        .filterNot { it == context.packageName }
                        .toSet(),
                ),
            )
        }
        if (selection.language) {
            requireNotNull(document.language) { "Language setting is absent" }.also {
                prefs.setLanguage(it)
            }
        } else {
            null
        }
    }
}
