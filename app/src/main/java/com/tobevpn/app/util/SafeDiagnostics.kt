package com.tobevpn.app.util

import android.util.Log
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Minimal release-visible diagnostics. Messages passed here must never
 * contain endpoint URLs, request paths, account identifiers or tokens.
 */
object SafeDiagnostics {
    @Volatile
    private var sink: ((level: Int, tag: String, message: String) -> Unit)? = null
    @Volatile
    private var detailedLoggingEnabled: (() -> Boolean)? = null
    @Volatile
    private var stateSnapshotProvider: (() -> String)? = null

    fun installSink(
        value: (level: Int, tag: String, message: String) -> Unit,
        isDetailedLoggingEnabled: () -> Boolean,
    ) {
        sink = value
        detailedLoggingEnabled = isDetailedLoggingEnabled
    }

    fun installStateSnapshotProvider(value: () -> String) {
        stateSnapshotProvider = value
    }

    internal fun currentStateSnapshot(): String? = runCatching {
        stateSnapshotProvider?.invoke()
    }.getOrNull()?.takeIf(String::isNotBlank)

    fun error(tag: String, message: String) {
        write(Log.ERROR, tag, message)
    }

    fun info(tag: String, message: String) {
        write(Log.INFO, tag, message)
    }

    fun warn(tag: String, message: String) {
        write(Log.WARN, tag, message)
    }

    /**
     * High-volume operational detail. Unlike state changes and warnings, this
     * does not enter logcat or allocate a journal event unless the user has
     * explicitly started diagnostic collection.
     */
    fun trace(tag: String, message: String) {
        if (detailedLoggingEnabled?.invoke() != true) return
        write(Log.DEBUG, tag, message)
    }

    private fun write(level: Int, tag: String, message: String) {
        Log.println(level, tag, message)
        sink?.invoke(level, tag, message)
    }

    fun failureCategory(error: Throwable): String = when (error) {
        is HttpException -> "HTTP_${error.code()}"
        is UnknownHostException -> "DNS"
        is SocketTimeoutException -> "TIMEOUT"
        is SSLException -> "TLS"
        is IOException -> "IO"
        else -> "OTHER"
    }

    fun failureSummary(error: Throwable): String {
        val types = generateSequence(error) { it.cause }
            .take(3)
            .joinToString(separator = "->") { cause ->
                cause::class.java.simpleName.ifBlank { "Throwable" }
            }
        val origin = error.stackTrace
            .firstOrNull { frame -> frame.className.startsWith("com.tobevpn.app.") }
            ?.let { frame ->
                "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
            }
            ?: "external"
        val appFrames = generateSequence(error) { it.cause }
            .flatMap { cause -> cause.stackTrace.asSequence() }
            .filter { frame -> frame.className.startsWith("com.tobevpn.app.") }
            .distinctBy { frame ->
                Triple(frame.className, frame.methodName, frame.lineNumber)
            }
            .take(4)
            .joinToString(separator = ">") { frame ->
                "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
            }
            .ifBlank { "NONE" }
        return "category=${failureCategory(error)} types=$types origin=$origin " +
            "app_frames=$appFrames"
    }
}
