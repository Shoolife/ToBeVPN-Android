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
    fun warn(tag: String, message: String) {
        Log.println(Log.WARN, tag, message)
    }

    fun failureCategory(error: Throwable): String = when (error) {
        is HttpException -> "HTTP_${error.code()}"
        is UnknownHostException -> "DNS"
        is SocketTimeoutException -> "TIMEOUT"
        is SSLException -> "TLS"
        is IOException -> "IO"
        else -> "OTHER"
    }
}
