package io.nekohasekai.sagernet.bg.core

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Stable-channel native core updater.
 *
 * Native bindings are intentionally constrained to the ABI family compiled into Hui:
 * Box 1.14.x and CMFA 2.11.x. A newer minor/major stays visible as "app update required"
 * instead of risking a JNI mismatch. Patch releases in the compatible family can be
 * installed without replacing the whole APK and take effect in the next core process.
 */
object CoreUpdateManager {
    private const val BOX_API = "https://api.github.com/repos/SagerNet/sing-box/releases/latest"
    private const val META_API = "https://api.github.com/repos/MetaCubeX/ClashMetaForAndroid/releases/latest"
    private const val BOX_FAMILY = "1.14."
    private const val META_FAMILY = "2.11."
    private const val MAX_APK_BYTES = 150L * 1024L * 1024L
    private const val CHECK_INTERVAL = 24L * 60L * 60L * 1000L
    private const val PREFS = "hui_core_update_state"

    data class UpdateReport(
        val box: String,
        val meta: String,
        val changed: Boolean,
    ) {
        val message: String get() = "Box：$box\nMeta：$meta"
    }

    private data class Release(
        val version: String,
        val assetName: String,
        val url: String,
        val digest: String?,
    )

    suspend fun autoUpdateIfDue(context: Context) = withContext(Dispatchers.IO) {
        if (!DataStore.huiCoreAutoUpdate) return@withContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_check", 0L) < CHECK_INTERVAL) return@withContext
        prefs.edit().putLong("last_check", now).apply()
        runCatching { updateAll(context, manual = false) }
    }

    suspend fun updateAll(context: Context, manual: Boolean): UpdateReport = withContext(Dispatchers.IO) {
        val box = runCatching { updateBox(context, manual) }.getOrElse { "检查失败：${shortMessage(it)}" }
        val meta = runCatching { updateMeta(context, manual) }.getOrElse { "检查失败：${shortMessage(it)}" }
        UpdateReport(box, meta, box.startsWith("已更新") || meta.startsWith("已更新"))
    }

    fun currentSummary(context: Context): String {
        val box = activeVersion(context, "box") ?: CoreBuildInfo.BOX_VERSION
        val meta = activeVersion(context, "meta") ?: CoreBuildInfo.META_ANDROID_VERSION
        return "Box $box · Meta/CMFA $meta"
    }

    private fun updateBox(context: Context, manual: Boolean): String {
        val release = fetchRelease(BOX_API) { version -> "SFA-$version-arm64-v8a.apk" }
        val installed = activeVersion(context, "box") ?: CoreBuildInfo.BOX_VERSION
        if (!release.version.startsWith(BOX_FAMILY)) {
            return "发现 ${release.version}，需要更新绘的绑定层"
        }
        if (compareVersion(release.version, installed) <= 0) return "已是最新 $installed"
        if (!manual && isMetered(context)) return "发现 ${release.version}，等待 Wi‑Fi 自动更新"
        val apk = downloadVerified(context, "box", release)
        installLibraries(
            context = context,
            engine = "box",
            version = release.version,
            apk = apk,
            entries = mapOf("lib/arm64-v8a/libbox.so" to "libbox.so"),
            source = "SagerNet/sing-box",
        )
        apk.delete()
        return "已更新 ${release.version}，下次启动 Box 生效"
    }

    private fun updateMeta(context: Context, manual: Boolean): String {
        val release = fetchRelease(META_API) { version -> "cmfa-$version-meta-arm64-v8a-release.apk" }
        val installed = activeVersion(context, "meta") ?: CoreBuildInfo.META_ANDROID_VERSION
        if (!release.version.startsWith(META_FAMILY)) {
            return "发现 CMFA ${release.version}，需要更新绘的桥接层"
        }
        if (compareVersion(release.version, installed) <= 0) return "已是最新 CMFA $installed"
        if (!manual && isMetered(context)) return "发现 CMFA ${release.version}，等待 Wi‑Fi 自动更新"
        val apk = downloadVerified(context, "meta", release)
        installLibraries(
            context = context,
            engine = "meta",
            version = release.version,
            apk = apk,
            entries = mapOf(
                "lib/arm64-v8a/libclash.so" to "libclash.so",
                "lib/arm64-v8a/libbridge.so" to "libbridge.so",
            ),
            source = "MetaCubeX/ClashMetaForAndroid",
        )
        apk.delete()
        return "已更新 CMFA ${release.version}，下次启动 Meta 生效"
    }

    private fun fetchRelease(api: String, assetName: (String) -> String): Release {
        require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) { "当前核心热更新仅支持 arm64-v8a" }
        val json = JSONObject(httpText(api))
        val version = json.getString("tag_name").removePrefix("v")
        val expected = assetName(version)
        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name") != expected) continue
            return Release(
                version = version,
                assetName = expected,
                url = asset.getString("browser_download_url"),
                digest = asset.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 },
            )
        }
        error("官方发布中没有 $expected")
    }

    private fun httpText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Hui-Core-Updater/${CoreBuildInfo.APP_CORE_API}")
        }
        return try {
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadVerified(context: Context, engine: String, release: Release): File {
        val cache = File(context.cacheDir, "core-updates").apply { mkdirs() }
        val target = File(cache, "$engine-${release.version}.apk.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = (URL(release.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Hui-Core-Updater/${CoreBuildInfo.APP_CORE_API}")
        }
        try {
            require(connection.responseCode in 200..299) { "下载失败 HTTP ${connection.responseCode}" }
            val length = connection.contentLengthLong
            require(length <= 0 || length <= MAX_APK_BYTES) { "核心包超过安全大小限制" }
            var total = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_APK_BYTES) { "核心包超过安全大小限制" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            release.digest?.let { require(actual.equals(it, true)) { "官方 SHA-256 校验失败" } }
            require(total > 1024 * 1024) { "下载到的核心包异常" }
            return target
        } catch (t: Throwable) {
            target.delete()
            throw t
        } finally {
            connection.disconnect()
        }
    }

    private fun installLibraries(
        context: Context,
        engine: String,
        version: String,
        apk: File,
        entries: Map<String, String>,
        source: String,
    ) {
        val root = File(context.filesDir, "core-updates/$engine").apply { mkdirs() }
        val pending = File(root, ".pending-$version-${System.currentTimeMillis()}").apply { mkdirs() }
        val remaining = entries.toMutableMap()
        try {
            ZipInputStream(apk.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val outputName = remaining.remove(entry.name) ?: continue
                    val out = File(pending, outputName)
                    FileOutputStream(out).use { output -> zip.copyTo(output, 64 * 1024) }
                    require(isElf(out) && out.length() > 4096L) { "$outputName 不是有效的原生核心" }
                    out.setReadable(true, true)
                    out.setExecutable(true, true)
                }
            }
            require(remaining.isEmpty()) { "核心包缺少 ${remaining.keys.joinToString()}" }
            val target = File(root, version)
            if (target.exists()) target.deleteRecursively()
            require(pending.renameTo(target)) { "无法激活核心目录" }
            val manifest = JSONObject()
                .put("engine", engine)
                .put("version", version)
                .put("source", source)
                .put("installedAt", System.currentTimeMillis())
            val active = File(root, "active.json")
            val temp = File(root, "active.pending.json")
            temp.writeText(manifest.toString())
            if (active.exists()) active.delete()
            require(temp.renameTo(active)) { "无法写入核心激活信息" }
            File(root, "restart-required").writeText(version)
            root.listFiles()?.filter { it.isDirectory && it.name != version && !it.name.startsWith(".pending") }
                ?.sortedByDescending(File::lastModified)?.drop(1)?.forEach(File::deleteRecursively)
        } catch (t: Throwable) {
            pending.deleteRecursively()
            throw t
        }
    }

    fun activeVersion(context: Context, engine: String): String? = runCatching {
        val active = File(context.filesDir, "core-updates/$engine/active.json")
        if (!active.isFile) return@runCatching null
        JSONObject(active.readText()).optString("version").takeIf(String::isNotBlank)
    }.getOrNull()

    private fun isElf(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(4)
            input.read(head) == 4 && head.contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
        }
    }.getOrDefault(false)

    private fun isMetered(context: Context): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true

    private fun compareVersion(a: String, b: String): Int {
        val aa = a.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val bb = b.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(aa.size, bb.size)) {
            val d = aa.getOrElse(i) { 0 }.compareTo(bb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    private fun shortMessage(t: Throwable): String =
        generateSequence(t) { it.cause }.mapNotNull { it.message?.trim() }.firstOrNull { it.isNotEmpty() }
            ?.take(120) ?: t.javaClass.simpleName
}
