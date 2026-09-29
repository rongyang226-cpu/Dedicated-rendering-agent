package io.nekohasekai.sagernet.ktx

import libcore.Libcore
import java.io.InputStream
import java.io.OutputStream

object Logs {

    private fun mkTag(): String = Thread.currentThread().stackTrace.getOrNull(4)?.className?.substringAfterLast(".") ?: "Hui"

    private fun legacyProcess(): Boolean = runCatching {
        val p = io.nekohasekai.sagernet.SagerNet.application.process
        p == io.nekohasekai.sagernet.BuildConfig.APPLICATION_ID || p.endsWith(":bg")
    }.getOrDefault(false)

    private fun emit(priority: Int, level: String, message: String, exception: Throwable? = null) {
        val tag = mkTag()
        val text = "[$level] [$tag] $message" + (exception?.let { "\n${it.stackTraceToString()}" } ?: "")
        if (legacyProcess()) {
            runCatching { Libcore.nekoLogPrintln(text) }
                .onFailure { android.util.Log.println(priority, tag, text) }
        } else {
            android.util.Log.println(priority, tag, text)
        }
    }

    fun d(message: String) = emit(android.util.Log.DEBUG, "Debug", message)
    fun d(message: String, exception: Throwable) = emit(android.util.Log.DEBUG, "Debug", message, exception)
    fun i(message: String) = emit(android.util.Log.INFO, "Info", message)
    fun i(message: String, exception: Throwable) = emit(android.util.Log.INFO, "Info", message, exception)
    fun w(message: String) = emit(android.util.Log.WARN, "Warning", message)
    fun w(message: String, exception: Throwable) = emit(android.util.Log.WARN, "Warning", message, exception)
    fun w(exception: Throwable) = emit(android.util.Log.WARN, "Warning", exception.message.orEmpty(), exception)
    fun e(message: String) = emit(android.util.Log.ERROR, "Error", message)
    fun e(message: String, exception: Throwable) = emit(android.util.Log.ERROR, "Error", message, exception)
    fun e(exception: Throwable) = emit(android.util.Log.ERROR, "Error", exception.message.orEmpty(), exception)
}

fun InputStream.use(out: OutputStream) {
    use { input ->
        out.use { output ->
            input.copyTo(output)
        }
    }
}