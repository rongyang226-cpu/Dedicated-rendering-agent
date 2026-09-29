package moe.matsuri.nb4a.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.utils.CrashHandler
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

object SendLog {
    private val exportInProgress = AtomicBoolean(false)
    private const val MAX_NEKO_LOG_BYTES = 1024L * 1024L
    private const val MAX_CRASH_LOG_CHARS = 256 * 1024
    private const val MAX_SAVED_EXPORTS = 4

    private val proxyLinkPattern = Regex(
        "(?i)\\b(vless|vmess|trojan|ss|ssr|hysteria2?|tuic|anytls|socks5?)://[^\\s]+"
    )
    private val httpUrlPattern = Regex("(?i)\\b(https?://[^/\\s?#]+)(?:/[^\\s]*)?")
    private val secretValuePattern = Regex(
        "(?i)([\\\"']?(?:password|passwd|token|secret|uuid|private[_-]?key|authorization|cookie|subscription(?:[_-]?link)?)[\\\"']?\\s*[:=]\\s*)([\\\"']?)[^,}\\s\\n]+"
    )

    private fun sanitize(text: String): String {
        var safe = proxyLinkPattern.replace(text) { "${it.groupValues[1]}://<redacted>" }
        safe = httpUrlPattern.replace(safe) { "${it.groupValues[1]}/<redacted>" }
        safe = secretValuePattern.replace(safe) { "${it.groupValues[1]}<redacted>" }
        return safe
    }

    private fun cleanupOldExports(dir: File) {
        dir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_SAVED_EXPORTS)?.forEach { runCatching { it.delete() } }
    }

    // Create a bounded, redacted diagnostic report and open the share sheet once.
    fun sendLog(context: Context, title: String) {
        if (!exportInProgress.compareAndSet(false, true)) return
        try {
            val logDir = File(app.cacheDir, "log").also { it.mkdirs() }
            cleanupOldExports(logDir)
            val logFile = File.createTempFile("$title ", ".log", logDir)

            logFile.writeText(CrashHandler.buildReportHeader())
            CrashHandler.lastCrashFile()?.let { crash ->
                val crashText = runCatching { crash.readText().take(MAX_CRASH_LOG_CHARS) }.getOrNull()
                if (!crashText.isNullOrBlank()) {
                    logFile.appendText("\nLast crash:\n\n${sanitize(crashText)}\n")
                }
            }

            logFile.appendText("\nLogcat (last 1500 lines):\n\n")
            try {
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "1500"))
                process.inputStream.bufferedReader().useLines { lines ->
                    FileOutputStream(logFile, true).bufferedWriter().use { writer ->
                        lines.forEach { line ->
                            writer.appendLine(sanitize(line))
                        }
                    }
                }
                process.destroy()
            } catch (e: IOException) {
                Logs.w(e)
                logFile.appendText("Export logcat error: ${CrashHandler.formatThrowable(e)}\n")
            }

            val neko = getNekoLog(MAX_NEKO_LOG_BYTES)
            if (neko.isNotEmpty()) {
                logFile.appendText("\nNeko log (tail, capped at 1 MiB):\n\n")
                logFile.appendText(sanitize(neko.toString(Charsets.UTF_8)))
            }

            shareFile(context, logFile)
        } catch (e: Exception) {
            exportInProgress.set(false)
            Logs.w(e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.applicationContext, R.string.hui_log_export_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun shareFile(context: Context, logFile: File) {
        val streamUri = FileProvider.getUriForFile(
            context, BuildConfig.APPLICATION_ID + ".cache", logFile
        )
        val sendIntent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_STREAM, streamUri)
        val chooser = Intent.createChooser(
            sendIntent, context.getString(R.string.abc_shareactionprovider_share_with)
        ).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        Handler(Looper.getMainLooper()).post {
            try {
                context.startActivity(chooser)
            } catch (e: Exception) {
                Logs.w(e)
                Toast.makeText(context.applicationContext, R.string.hui_log_export_failed, Toast.LENGTH_LONG).show()
            } finally {
                exportInProgress.set(false)
            }
        }
    }

    // Get a bounded tail from neko.log. max <= 0 means use the safe default cap.
    fun getNekoLog(max: Long = MAX_NEKO_LOG_BYTES): ByteArray {
        return try {
            val file = File(SagerNet.application.cacheDir, "neko.log")
            val len = file.length()
            if (len <= 0L) return ByteArray(0)
            val limit = if (max <= 0L) MAX_NEKO_LOG_BYTES else max.coerceAtMost(MAX_NEKO_LOG_BYTES)
            FileInputStream(file).use { stream ->
                var remainingSkip = (len - limit).coerceAtLeast(0L)
                while (remainingSkip > 0L) {
                    val skipped = stream.skip(remainingSkip)
                    if (skipped > 0L) {
                        remainingSkip -= skipped
                    } else {
                        if (stream.read() == -1) break
                        remainingSkip--
                    }
                }
                val buffer = ByteArray(limit.toInt())
                var offset = 0
                while (offset < buffer.size) {
                    val read = stream.read(buffer, offset, buffer.size - offset)
                    if (read <= 0) break
                    offset += read
                }
                if (offset == buffer.size) buffer else buffer.copyOf(offset)
            }
        } catch (e: Exception) {
            e.stackTraceToString().take(64 * 1024).toByteArray()
        }
    }
}
