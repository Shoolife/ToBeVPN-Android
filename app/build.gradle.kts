import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

val releaseBuildRequested = gradle.startParameter.taskNames.any {
    it.contains("release", ignoreCase = true)
}
val versionCodeOverride = providers.gradleProperty("tobevpn.versionCodeOverride")
    .orNull
    ?.toIntOrNull()

fun requireConfiguredReleaseFallback(name: String, value: String) {
    val normalized = value.trim()
    val looksLikePlaceholder = normalized.isBlank() ||
        normalized.contains("<") ||
        normalized.contains("your-", ignoreCase = true) ||
        normalized.contains("example.", ignoreCase = true) ||
        normalized.contains(".invalid", ignoreCase = true)
    if (releaseBuildRequested && looksLikePlaceholder) {
        throw GradleException(
            "$name must be configured with an operator endpoint for release builds. " +
                "Do not build a production APK with a blank or placeholder network endpoint."
        )
    }
}

android {
    namespace = "com.tobevpn.app"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.tobevpn.app"
        minSdk = 29
        targetSdk = 36
        versionCode = versionCodeOverride ?: 72
        versionName = "1.0.71"

        // Direct APK releases can use the GitHub self-updater. Google Play
        // builds override this flag because Play policy requires updates to
        // be delivered by Google Play instead of REQUEST_INSTALL_PACKAGES.
        buildConfigField("boolean", "IN_APP_UPDATES_ENABLED", "true")
        buildConfigField("boolean", "PLAY_DISTRIBUTION", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ABI filtering is done per build type below — keep defaultConfig
        // empty so the splits {} block works against the full set declared
        // in release{}.

        // Backend URL is **never** hardcoded into the public source tree —
        // production instances must not be discoverable via GitHub search.
        // Resolution order:
        //   1. local.properties: `bot.api.url=https://...` (developers)
        //   2. env var BOT_API_URL (CI / GitHub Actions secret)
        //   3. Hard fail at configure time so a broken build never silently
        //      compiles against `https://example.invalid/` and gets shipped.
        val botApiUrl = localProperties.getProperty("bot.api.url")
            ?: System.getenv("BOT_API_URL")
            ?: throw GradleException(
                "BOT_API_URL is not configured. Set it via local.properties (`bot.api.url=...`) " +
                "or the BOT_API_URL environment variable / Actions secret."
            )
        buildConfigField("String", "BOT_API_BASE_URL", "\"$botApiUrl\"")

        // Release builds require operator-provided fallback endpoints. Debug
        // builds may omit them; environment values take precedence so CI cannot
        // accidentally inherit a developer placeholder from local.properties.
        val fallbackBotDomain = System.getenv("FALLBACK_BOT_DOMAIN")
            ?.takeIf { it.isNotBlank() }
            ?: localProperties.getProperty("fallback.bot.domain")
            ?: ""
        val fallbackSubsDomain = System.getenv("FALLBACK_SUBS_DOMAIN")
            ?.takeIf { it.isNotBlank() }
            ?: localProperties.getProperty("fallback.subs.domain")
            ?: ""
        val subscriptionUrl = System.getenv("SUBSCRIPTION_URL")
            ?.takeIf { it.isNotBlank() }
            ?: localProperties.getProperty("subscription.url")
            ?: ""
        requireConfiguredReleaseFallback("FALLBACK_BOT_DOMAIN", fallbackBotDomain)
        requireConfiguredReleaseFallback("FALLBACK_SUBS_DOMAIN", fallbackSubsDomain)
        requireConfiguredReleaseFallback("SUBSCRIPTION_URL", subscriptionUrl)
        buildConfigField("String", "FALLBACK_BOT_DOMAIN", "\"$fallbackBotDomain\"")
        buildConfigField("String", "FALLBACK_SUBS_DOMAIN", "\"$fallbackSubsDomain\"")
        buildConfigField("String", "SUBSCRIPTION_BASE_URL", "\"$subscriptionUrl\"")

        // Public beta profile used by the mobile-only base-station bypass tab.
        // It is intentionally configurable for CI/operators, but the current
        // public endpoint is a usable default because client-side access gates
        // cannot make a public URL secret in the application binary.
        val baseStationBypassUrl = System.getenv("BASE_STATION_BYPASS_URL")
            ?.takeIf { it.isNotBlank() }
            ?: localProperties.getProperty("base.station.bypass.url")
            ?: "https://functions.yandexcloud.net/d4evsj6lntd0e82tot67"
        buildConfigField(
            "String",
            "BASE_STATION_BYPASS_URL",
            "\"$baseStationBypassUrl\"",
        )
    }

    // Release signing is opt-in — credentials live in local.properties (gitignored).
    // Without them the release config is skipped so debug builds and CI clones
    // still work; `./gradlew assembleRelease` will fail loudly if invoked.
    val keystorePath = localProperties.getProperty("keystore.path")
    val keystorePassword = localProperties.getProperty("keystore.password")
    val keystoreKeyAlias = localProperties.getProperty("keystore.keyAlias")
    val keystoreKeyPassword = localProperties.getProperty("keystore.keyPassword")
    val hasReleaseSigning = keystorePath != null
        && keystorePassword != null
        && keystoreKeyAlias != null
        && keystoreKeyPassword != null

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                // Resolve relative paths against the repo root so users can write
                // e.g. "../tobevpn-release.jks" without worrying about Gradle's
                // module-relative resolution.
                storeFile = rootProject.file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Keep local UI/test builds installable next to the Google Play app.
            // Play App Signing uses a different certificate, so sharing the
            // production package name would otherwise require deleting the
            // user's installed app and all of its data before every test.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            isShrinkResources = false
            ndk {
                // Keep emulator ABIs and arm64 so the same debug application
                // can be installed side-by-side with Play builds on modern
                // physical phones during client/server contract testing.
                abiFilters += listOf("x86", "x86_64", "arm64-v8a")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            ndk {
                abiFilters.clear()
                // Production ABIs we ship in release assets. The splits {}
                // block below turns each into a separate APK so phones only
                // download the native libs they actually need.
                //   * arm64-v8a   — every modern Android phone (since 2017)
                //   * armeabi-v7a — older 32-bit ARM phones still in the wild
                //   * x86_64      — emulators, ChromeOS Android subsystem
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("playRelease") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "IN_APP_UPDATES_ENABLED", "false")
            buildConfigField("boolean", "PLAY_DISTRIBUTION", "true")
        }
    }

    // Per-ABI splits: instead of one universal APK with native libs for every
    // architecture (~30 MB), produce one APK per ABI (~10–13 MB each) and a
    // universal-fallback APK. The in-app updater on the device picks the
    // matching split via Build.SUPPORTED_ABIS, so users only ever download
    // ~12 MB on update instead of the full bundle.
    //
    // Splits are toggled off when building the App Bundle: AAB carries every
    // architecture inside one .aab and Google's bundletool re-splits per
    // device, so doing it ourselves at the same time is incompatible
    // (Google issuetracker #402800800). The CI runs APK and AAB in
    // separate gradle invocations, passing -PdisableAbiSplits for the AAB.
    val splitsDisabled = providers.gradleProperty("disableAbiSplits").isPresent
    splits {
        abi {
            isEnable = !splitsDisabled
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            // Keep a universal APK as a safety net for any device whose
            // Build.SUPPORTED_ABIS doesn't intersect our list (rare, but
            // RuStore / sideload scenarios sometimes ship to unusual hardware).
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", file("$projectDir/schemas").path)
}

dependencies {
    // XRay core (AAR in libs/)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    // Image loading (Telegram avatar)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation("com.google.android.play:app-update:2.1.0")
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    androidTestImplementation(libs.androidx.room.testing)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // ML Kit Barcode Scanner (for linking TV via QR)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation(libs.zxing.core)

    // SQLCipher
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite.ktx)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
