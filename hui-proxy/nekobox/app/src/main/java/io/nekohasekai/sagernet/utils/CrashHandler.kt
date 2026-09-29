package io.nekohasekai.sagernet.utils

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val CRASH_DIR = "crash"
    private const val LAST_CRASH = "last_crash.log"
    private const val PENDING_CRASH = "pending_crash"
    private const val MAX_CRASH_CHARS = 512 * 1024
    private val handlingCrash = AtomicBoolean(false)
    @Volatile private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun install() {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current !== this) previousHandler = current
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    fun consumePendingCrash(): Boolean = try {
        val marker = File(app.filesDir, "$CRASH_DIR/$PENDING_CRASH")
        marker.exists() && marker.delete()
    } catch (_: Throwable) {
        false
    }

    fun lastCrashFile(): File? = try {
        File(app.filesDir, "$CRASH_DIR/$LAST_CRASH").takeIf { it.isFile && it.length() > 0L }
    } catch (_: Throwable) {
        null
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // Never launch UI from a fatal-exception handler. The previous implementation
        // rebooted into a share chooser, so a recurring startup/background crash could
        // trap the user in an endless system share sheet. Save one local snapshot instead.
        if (handlingCrash.compareAndSet(false, true)) {
            try {
                Log.e(thread.toString(), throwable.stackTraceToString())
            } catch (_: Throwable) {
            }
            try {
                Logs.e(thread.toString())
                Logs.e(throwable.stackTraceToString())
            } catch (_: Throwable) {
            }
            try {
                persistCrash(thread, throwable)
            } catch (_: Throwable) {
            }
        }

        val delegate = previousHandler
        if (delegate != null && delegate !== this) {
            try {
                delegate.uncaughtException(thread, throwable)
            } catch (_: Throwable) {
            }
        }
        // Android's default handler normally terminates the process. If a custom
        // upstream handler returns, never continue execution in a corrupted state.
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(10)
    }

    private fun persistCrash(thread: Thread, throwable: Throwable) {
        val dir = File(app.filesDir, CRASH_DIR).apply { mkdirs() }
        val report = buildString {
            append("绘 ${SagerNet.appVersionNameForDisplay} (${BuildConfig.VERSION_CODE})\n")
            append("Date: ${getCurrentMilliSecondUTCTimeStamp()}\n")
            append("Process: ${app.process}\n")
            append("Thread: ${thread.name}\n\n")
            append(formatThrowable(throwable))
        }.take(MAX_CRASH_CHARS)
        File(dir, LAST_CRASH).writeText(report)
        File(dir, PENDING_CRASH).writeText(System.currentTimeMillis().toString())
    }

    fun formatThrowable(throwable: Throwable): String {
        var format = throwable.javaClass.name
        val message = throwable.message
        if (!message.isNullOrBlank()) {
            format += ": $message"
        }
        format += "\n"

        format += throwable.stackTrace.joinToString("\n") {
            "    at ${it.className}.${it.methodName}(${it.fileName}:${if (it.isNativeMethod) "native" else it.lineNumber})"
        }

        val cause = throwable.cause
        if (cause != null) {
            format += "\n\nCaused by: " + formatThrowable(cause)
        }

        return format
    }

    fun buildReportHeader(): String {
        var report = ""
        report += "绘 ${SagerNet.appVersionNameForDisplay} (${BuildConfig.VERSION_CODE})\n"
        report += "Date: ${getCurrentMilliSecondUTCTimeStamp()}\n\n"
        report += "OS_VERSION: ${getSystemPropertyWithAndroidAPI("os.version")}\n"
        report += "SDK_INT: ${Build.VERSION.SDK_INT}\n"
        report += if ("REL" == Build.VERSION.CODENAME) {
            "RELEASE: ${Build.VERSION.RELEASE}"
        } else {
            "CODENAME: ${Build.VERSION.CODENAME}"
        } + "\n"
        report += "ID: ${Build.ID}\n"
        report += "DISPLAY: ${Build.DISPLAY}\n"
        report += "INCREMENTAL: ${Build.VERSION.INCREMENTAL}\n"

        val systemProperties = getSystemProperties()

        report += "SECURITY_PATCH: ${systemProperties.getProperty("ro.build.version.security_patch")}\n"
        report += "IS_DEBUGGABLE: ${systemProperties.getProperty("ro.debuggable")}\n"
        report += "IS_EMULATOR: ${systemProperties.getProperty("ro.boot.qemu")}\n"
        report += "IS_TREBLE_ENABLED: ${systemProperties.getProperty("ro.treble.enabled")}\n"

        report += "TYPE: ${Build.TYPE}\n"
        report += "TAGS: ${Build.TAGS}\n\n"

        report += "MANUFACTURER: ${Build.MANUFACTURER}\n"
        report += "BRAND: ${Build.BRAND}\n"
        report += "MODEL: ${Build.MODEL}\n"
        report += "PRODUCT: ${Build.PRODUCT}\n"
        report += "BOARD: ${Build.BOARD}\n"
        report += "HARDWARE: ${Build.HARDWARE}\n"
        report += "DEVICE: ${Build.DEVICE}\n"
        report += "SUPPORTED_ABIS: ${
            Build.SUPPORTED_ABIS.filter { it.isNotBlank() }.joinToString(", ")
        }\n\n"


        // Do not dump the preference database here. It may contain subscription URLs,
        // UUIDs, passwords or other node credentials. Diagnostic exports should be safe
        // to share by default.
        report += "\n"
        return report
    }

    private fun getSystemProperties(): Properties {
        val systemProperties = Properties()

        // getprop commands returns values in the format `[key]: [value]`
        // Regex matches string starting with a literal `[`,
        // followed by one or more characters that do not match a closing square bracket as the key,
        // followed by a literal `]: [`,
        // followed by one or more characters as the value,
        // followed by string ending with literal `]`
        // multiline values will be ignored
        val propertiesPattern = Pattern.compile("^\\[([^]]+)]: \\[(.+)]$")
        try {
            val process = ProcessBuilder().command("/system/bin/getprop")
                .redirectErrorStream(true)
                .start()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                reader.forEachLine { currentLine ->
                    val matcher = propertiesPattern.matcher(currentLine)
                    if (matcher.matches()) {
                        val key = matcher.group(1).orEmpty()
                        val value = matcher.group(2).orEmpty()
                        if (key.isNotEmpty() && value.isNotEmpty()) {
                            systemProperties[key] = value
                        }
                    }
                }
            }
            process.destroy()
        } catch (e: IOException) {
            Logs.e(
                "Failed to get run \"/system/bin/getprop\" to get system properties.", e
            )
        }

        //for (String key : systemProperties.stringPropertyNames()) {
        //    Logger.logVerbose(key + ": " +  systemProperties.get(key));
        //}
        return systemProperties
    }

    private fun getSystemPropertyWithAndroidAPI(property: String): String? {
        return try {
            System.getProperty(property)
        } catch (e: Exception) {
            Logs.e("Failed to get system property \"" + property + "\":" + e.message)
            null
        }
    }

    @SuppressLint("SimpleDateFormat")
    private fun getCurrentMilliSecondUTCTimeStamp(): String {
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS z")
        df.timeZone = TimeZone.getTimeZone("UTC")
        return df.format(Date())
    }

}