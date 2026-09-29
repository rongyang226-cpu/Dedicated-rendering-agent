package io.nekohasekai.sagernet.bg.meta

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.bg.core.CoreStatus
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object MetaCoreManager {
    const val ENGINE_BOX = "box"
    const val ENGINE_META = "meta"
    private const val MAX_CONFIG_BYTES = 2 * 1024 * 1024

    data class Status(
        val state: String = "STOPPED",
        val message: String = "未连接",
        val txRate: Long = 0,
        val rxRate: Long = 0,
        val txTotal: Long = 0,
        val rxTotal: Long = 0,
        val updatedAt: Long = 0,
        val version: String = "",
    ) {
        val active get() = state == "RUNNING" || state == "STARTING" || state == "STOPPING"
    }

    fun home(context: Context) = File(context.filesDir, "meta")
    fun profileDir(context: Context) = File(home(context), "profiles/active")
    fun activeConfig(context: Context) = File(profileDir(context), "config.yaml")
    private fun sourceFile(context: Context) = File(home(context), "source.txt")
    private fun sourceUrlFile(context: Context) = File(home(context), "source_url.txt")
    private fun backupDir(context: Context) = File(home(context), "backups")
    private fun statusFile(context: Context) = File(home(context), "status.json")

    fun sourceLabel(context: Context): String = runCatching {
        sourceFile(context).takeIf(File::isFile)?.readText()?.trim().orEmpty()
    }.getOrDefault("")

    fun hasConfig(context: Context): Boolean = activeConfig(context).let {
        it.isFile && it.length() in 1..MAX_CONFIG_BYTES.toLong()
    }

    fun importUri(context: Context, uri: Uri, label: String? = null) {
        val bytes = context.contentResolver.openInputStream(uri)?.use(::readLimited)
            ?: error("无法读取 Meta 配置")
        saveConfig(context, bytes, label ?: uri.lastPathSegment.orEmpty(), null)
    }

    fun importUrl(context: Context, raw: String) {
        val normalized = raw.trim()
        val url = URL(normalized)
        require(url.protocol.equals("https", true) || url.protocol.equals("http", true)) {
            "只支持 HTTP/HTTPS 配置链接"
        }
        require(url.userInfo == null) { "URL 不允许携带账号密码" }
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Hui/0.34 Meta")
        }
        try {
            val code = connection.responseCode
            require(code in 200..299) { "配置下载失败：HTTP $code" }
            val length = connection.contentLengthLong
            require(length <= 0 || length <= MAX_CONFIG_BYTES) { "Meta 配置超过 2 MB" }
            val bytes = connection.inputStream.use(::readLimited)
            saveConfig(context, bytes, url.host, normalized)
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(input: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val size = input.read(buffer)
            if (size <= 0) break
            total += size
            require(total <= MAX_CONFIG_BYTES) { "Meta 配置超过 2 MB" }
            output.write(buffer, 0, size)
        }
        return output.toByteArray()
    }

    private fun saveConfig(context: Context, bytes: ByteArray, label: String, sourceUrl: String?) {
        require(bytes.isNotEmpty()) { "Meta 配置为空" }
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        require("proxies:" in text || "proxy-providers:" in text || "listeners:" in text) {
            "这不像 Clash Meta / Mihomo YAML 配置"
        }
        val target = activeConfig(context)
        backupActive(context)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "config.pending.yaml")
        FileOutputStream(temp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (target.exists() && !target.delete()) error("无法替换旧 Meta 配置")
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        sourceFile(context).apply {
            parentFile?.mkdirs()
            writeText(label.take(180))
        }
        if (sourceUrl.isNullOrBlank()) sourceUrlFile(context).delete() else {
            sourceUrlFile(context).apply {
                parentFile?.mkdirs()
                writeText(sourceUrl.take(4096))
            }
        }
    }

    fun sourceUrl(context: Context): String = runCatching {
        sourceUrlFile(context).takeIf(File::isFile)?.readText()?.trim().orEmpty()
    }.getOrDefault("")

    fun readConfigText(context: Context): String = runCatching {
        activeConfig(context).takeIf(File::isFile)?.readText().orEmpty()
    }.getOrDefault("")

    fun saveEditedConfig(context: Context, raw: String) {
        val text = raw.removePrefix("\uFEFF").trim()
        require(text.isNotEmpty()) { "Meta 配置不能为空" }
        require(text.toByteArray().size <= MAX_CONFIG_BYTES) { "Meta 配置超过 2 MB" }
        require("proxies:" in text || "proxy-providers:" in text || "listeners:" in text) {
            "这不像 Clash Meta / Mihomo YAML 配置"
        }
        backupActive(context)
        val target = activeConfig(context)
        target.parentFile?.mkdirs()
        val pending = File(target.parentFile, "config.edited.yaml")
        FileOutputStream(pending).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (target.exists() && !target.delete()) error("无法替换 Meta 配置")
        if (!pending.renameTo(target)) {
            pending.copyTo(target, overwrite = true)
            pending.delete()
        }
    }

    fun refreshFromSource(context: Context) {
        val url = sourceUrl(context)
        require(url.isNotBlank()) { "当前 Meta 配置没有远程来源" }
        importUrl(context, url)
    }

    private fun backupActive(context: Context) {
        val source = activeConfig(context)
        if (!source.isFile || source.length() <= 0L) return
        val dir = backupDir(context).apply { mkdirs() }
        source.copyTo(File(dir, "config-${System.currentTimeMillis()}.yaml"), overwrite = true)
        dir.listFiles()?.sortedByDescending(File::lastModified)?.drop(6)?.forEach(File::delete)
    }


    fun start(context: Context) {
        require(hasConfig(context)) { "先导入 Clash Meta / Mihomo YAML 配置" }
        val apps = ArrayList(DataStore.individual.split('\n').map(String::trim).filter(String::isNotEmpty))
        val intent = Intent(context, MetaVpnService::class.java)
            .setAction(MetaVpnService.START)
            .putExtra("mtu", DataStore.mtu.coerceIn(1280, 9000))
            .putExtra("proxyApps", DataStore.proxyApps)
            .putExtra("bypassApps", DataStore.bypass)
            .putExtra("bypassLan", DataStore.bypassLan)
            .putExtra("allowIpv6", DataStore.ipv6Mode != 0)
            .putStringArrayListExtra("individualApps", apps)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        runCatching { context.stopService(Intent(context, MetaVpnService::class.java)) }
    }

    fun readCoreStatus(context: Context): CoreStatus {
        val value = readStatus(context)
        val state = when (value.state) {
            "STARTING" -> CoreStatus.State.STARTING
            "RUNNING" -> CoreStatus.State.RUNNING
            "STOPPING" -> CoreStatus.State.STOPPING
            "ERROR" -> CoreStatus.State.ERROR
            else -> CoreStatus.State.STOPPED
        }
        return CoreStatus(
            state = state, message = value.message, txRate = value.txRate, rxRate = value.rxRate,
            txTotal = value.txTotal, rxTotal = value.rxTotal, updatedAt = value.updatedAt,
            version = value.version,
        )
    }

    fun readStatus(context: Context): Status = runCatching {
        val file = statusFile(context)
        if (!file.isFile) return@runCatching Status()
        val json = JSONObject(file.readText())
        val updatedAt = json.optLong("updatedAt", 0L)
        val stale = updatedAt > 0 && System.currentTimeMillis() - updatedAt > 12_000L
        val rawState = json.optString("state", "STOPPED")
        val wasActive = rawState == "STARTING" || rawState == "RUNNING" || rawState == "STOPPING"
        if (stale && wasActive) {
            return@runCatching Status(state = "ERROR", message = "Meta 状态已失联", updatedAt = updatedAt)
        }
        Status(
            state = json.optString("state", "STOPPED"),
            message = json.optString("message", "未连接"),
            txRate = json.optLong("txRate", 0L),
            rxRate = json.optLong("rxRate", 0L),
            txTotal = json.optLong("txTotal", 0L),
            rxTotal = json.optLong("rxTotal", 0L),
            updatedAt = updatedAt,
            version = json.optString("version", ""),
        )
    }.getOrDefault(Status())
}
