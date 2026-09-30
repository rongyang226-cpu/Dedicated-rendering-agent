package io.nekohasekai.sagernet.bg.box

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.bg.core.CoreStatus
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.buildConfig
import moe.matsuri.nb4a.proxy.config.ConfigBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URI

/** File/config boundary for the modern SFA engine. No libbox JNI is touched in the UI process. */
object BoxCoreManager {
    private const val MAX_CONFIG_BYTES = 4 * 1024 * 1024

    private fun home(context: Context) = File(context.filesDir, "box")
    private fun profileDir(context: Context) = File(home(context), "profiles/active")
    fun activeConfig(context: Context) = File(profileDir(context), "config.json")
    private fun sourceFile(context: Context) = File(home(context), "source.json")
    private fun statusFile(context: Context) = File(home(context), "status.json")

    fun hasPreparedConfig(context: Context): Boolean = activeConfig(context).let {
        it.isFile && it.length() in 2..MAX_CONFIG_BYTES.toLong()
    }

    data class EditableConfig(
        val text: String,
        val description: String,
        val canUpdateSource: Boolean,
    )

    fun editableConfig(): EditableConfig {
        val selected = DataStore.selectedProxy
        val profile = selected.takeIf { it > 0L }?.let(SagerDatabase.proxyDao::getById)
        val group = profile?.let { SagerDatabase.groupDao.getById(it.groupId) } ?: DataStore.currentGroup()
        val bean = profile?.let { runCatching { it.requireBean() }.getOrNull() }
        if (profile != null && bean is ConfigBean && bean.type == 0) {
            val override = DataStore.huiFullConfigOverride(profile.id)
            return EditableConfig(
                text = override.ifBlank { bean.config.orEmpty() },
                description = if (override.isNotBlank())
                    "Box 完整配置 · 本地覆写已启用，更新来源不会覆盖"
                else "Box 完整 sing-box JSON · 可直接编辑",
                canUpdateSource = group.subscription != null,
            )
        }
        return EditableConfig(
            text = DataStore.globalCustomConfig.ifBlank { "{}" },
            description = if (group.subscription != null)
                "Box JSON 覆写 · 更新订阅时会保留本地覆写"
            else "Box JSON 全局覆写",
            canUpdateSource = group.subscription != null,
        )
    }

    fun saveEditedConfig(raw: String) {
        val text = raw.trim().ifBlank { "{}" }
        val formatted = JSONObject(text).toString(2)
        val selected = DataStore.selectedProxy
        val profile = selected.takeIf { it > 0L }?.let(SagerDatabase.proxyDao::getById)
        val bean = profile?.let { runCatching { it.requireBean() }.getOrNull() }
        if (profile != null && bean is ConfigBean && bean.type == 0) {
            DataStore.setHuiFullConfigOverride(profile.id, formatted)
        } else {
            DataStore.globalCustomConfig = formatted
        }
    }

    fun sourceGroup(): io.nekohasekai.sagernet.database.ProxyGroup? {
        val selected = DataStore.selectedProxy
        val profile = selected.takeIf { it > 0L }?.let(SagerDatabase.proxyDao::getById)
        val group = profile?.let { SagerDatabase.groupDao.getById(it.groupId) } ?: DataStore.currentGroup()
        return group.takeIf { it.subscription != null }
    }

    suspend fun prepareSelectedProfile(context: Context): String = withContext(Dispatchers.IO) {
        val selected = DataStore.selectedProxy
        require(selected > 0L) { "请先选择一个节点" }
        val profile = SagerDatabase.proxyDao.getById(selected) ?: error("选中的节点已经不存在")
        DataStore.currentProfile = profile.id
        val result = buildConfig(profile)
        require(result.externalIndex.all { it.chain.isEmpty() }) {
            "该节点依赖外置插件，当前新版 Box 隔离进程暂不支持此插件"
        }
        val text = result.config.trim()
        require(text.isNotEmpty()) { "Box 配置为空" }
        require(text.toByteArray().size <= MAX_CONFIG_BYTES) { "Box 配置超过 4 MB" }
        // NekoBox still emits several pre-1.14 TUN keys. Normalize them before the
        // SFA 1.14.1 process sees the config; full validation still happens in :box.
        val modernConfig = migrateToModernBox(JSONObject(text))
        val modernText = modernConfig.toString()
        require(modernText.toByteArray().size <= MAX_CONFIG_BYTES) { "Box 配置超过 4 MB" }
        writeAtomically(activeConfig(context), modernText.toByteArray())
        val source = JSONObject()
            .put("profileId", profile.id)
            .put("name", profile.displayName())
            .put("preparedAt", System.currentTimeMillis())
        writeAtomically(sourceFile(context), source.toString().toByteArray())
        profile.displayName()
    }

    private fun migrateToModernBox(root: JSONObject): JSONObject {
        migrateDnsToModernFormat(root)
        migrateLegacyRoutingFields(root)
        val inbounds = root.optJSONArray("inbounds") ?: error("Box 配置缺少 inbounds")
        var foundTun = false
        for (index in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(index) ?: continue
            if (inbound.optString("type") != "tun") continue
            foundTun = true
            inbound.put("auto_route", true)
            inbound.put("mtu", DataStore.mtu.coerceIn(1280, 9000))

            mergeKeys(inbound, "address", "inet4_address", "inet6_address")
            mergeKeys(inbound, "route_address", "inet4_route_address", "inet6_route_address")
            mergeKeys(inbound, "route_exclude_address", "inet4_route_exclude_address", "inet6_route_exclude_address")
            inbound.remove("endpoint_independent_nat") // removed in modern sing-box
            inbound.remove("gso")                     // old TUN option, no longer valid

            if (DataStore.bypassLan) {
                val excludes = linkedSetOf<String>()
                readStrings(inbound.opt("route_exclude_address"), excludes)
                excludes += listOf(
                    "10.0.0.0/8", "100.64.0.0/10", "169.254.0.0/16",
                    "172.16.0.0/12", "192.168.0.0/16",
                    "fc00::/7", "fe80::/10",
                )
                inbound.put("route_exclude_address", JSONArray(excludes.toList()))
            }
        }
        require(foundTun) { "当前配置没有 TUN 入站，不能作为 Box VPN 启动" }
        return root
    }

    private fun migrateDnsToModernFormat(root: JSONObject) {
        val dns = root.optJSONObject("dns") ?: return
        val servers = dns.optJSONArray("servers") ?: return
        val legacyFake = dns.optJSONObject("fakeip")
        val migrated = JSONArray()
        val rcodeTags = linkedSetOf<String>()

        for (index in 0 until servers.length()) {
            val source = servers.optJSONObject(index) ?: continue
            if (source.optString("type").isNotBlank()) {
                migrated.put(source)
                continue
            }
            val address = source.optString("address").trim()
            if (address.startsWith("rcode://", ignoreCase = true)) {
                source.optString("tag").takeIf(String::isNotBlank)?.let(rcodeTags::add)
                continue
            }
            migrated.put(migrateLegacyDnsServer(source, address, legacyFake))
        }
        dns.put("servers", migrated)
        dns.remove("fakeip")

        val rules = dns.optJSONArray("rules")
        if (rules != null) {
            val modernRules = JSONArray()
            for (index in 0 until rules.length()) {
                val rule = rules.optJSONObject(index) ?: continue
                // `outbound` DNS matchers were removed in sing-box 1.14. Dial-side DNS
                // resolution is handled by route.default_domain_resolver below.
                if (rule.has("outbound")) continue
                if (rcodeTags.isNotEmpty()) rewriteLegacyRcodeRule(rule, rcodeTags)
                modernRules.put(rule)
            }
            dns.put("rules", modernRules)
        }

        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        if (!route.has("default_domain_resolver")) {
            val tags = (0 until migrated.length()).mapNotNull { index ->
                migrated.optJSONObject(index)?.optString("tag")?.takeIf(String::isNotBlank)
            }
            val resolver = when {
                "dns-direct" in tags -> "dns-direct"
                "dns-local" in tags -> "dns-local"
                else -> tags.firstOrNull { it != "dns-fake" }
            }
            if (!resolver.isNullOrBlank()) route.put("default_domain_resolver", resolver)
        }
    }

    private fun migrateLegacyDnsServer(
        source: JSONObject,
        address: String,
        legacyFake: JSONObject?,
    ): JSONObject {
        val target = JSONObject()
        source.optString("tag").takeIf(String::isNotBlank)?.let { target.put("tag", it) }

        fun copyDialOptions() {
            source.optString("detour").takeIf(String::isNotBlank)?.let { target.put("detour", it) }
            val resolver = source.optString("address_resolver")
            val strategy = source.optString("address_strategy")
            if (resolver.isNotBlank()) {
                if (strategy.isBlank()) target.put("domain_resolver", resolver)
                else target.put("domain_resolver", JSONObject().put("server", resolver).put("strategy", strategy))
            }
        }

        if (address.equals("local", ignoreCase = true)) {
            target.put("type", "local")
            copyDialOptions()
            return target
        }
        if (address.equals("fakeip", ignoreCase = true)) {
            target.put("type", "fakeip")
            target.put("inet4_range", legacyFake?.optString("inet4_range")?.ifBlank { "198.18.0.0/15" } ?: "198.18.0.0/15")
            target.put("inet6_range", legacyFake?.optString("inet6_range")?.ifBlank { "fc00::/18" } ?: "fc00::/18")
            return target
        }

        val hasScheme = address.contains("://")
        val rawForUri = if (hasScheme) address else {
            val hostPart = if (address.count { it == ':' } > 1 && !address.startsWith("[")) "[$address]" else address
            "udp://$hostPart"
        }
        val uri = runCatching { URI(rawForUri) }.getOrElse {
            error("无法迁移旧 DNS 地址：$address")
        }
        val scheme = (uri.scheme ?: "udp").lowercase()
        val type = when (scheme) {
            "udp", "tcp", "tls", "quic", "https", "h3", "dhcp" -> scheme
            else -> error("不支持的旧 DNS 类型：$scheme")
        }
        target.put("type", type)

        if (type == "dhcp") {
            uri.host?.takeIf { it.isNotBlank() && !it.equals("auto", true) }?.let { target.put("interface", it) }
            copyDialOptions()
            return target
        }

        val host = uri.host?.takeIf(String::isNotBlank)
            ?: error("DNS 地址缺少服务器：$address")
        target.put("server", host)
        val defaultPort = when (type) {
            "udp", "tcp" -> 53
            "tls", "quic" -> 853
            else -> 443
        }
        if (uri.port > 0 && uri.port != defaultPort) target.put("server_port", uri.port)
        if ((type == "https" || type == "h3") &&
            !uri.rawPath.isNullOrBlank() && uri.rawPath != "/dns-query"
        ) target.put("path", uri.rawPath)
        copyDialOptions()
        return target
    }

    private fun rewriteLegacyRcodeRule(rule: JSONObject, rcodeTags: Set<String>) {
        val server = rule.optString("server")
        if (server in rcodeTags) {
            rule.remove("server")
            rule.remove("disable_cache")
            // rcode://success historically returned an empty NOERROR response. Preserve
            // that behavior using the modern predefined DNS action instead of reject.
            rule.put("action", "predefined")
            rule.put("rcode", "NOERROR")
        }
        val nested = rule.optJSONArray("rules") ?: return
        for (index in 0 until nested.length()) {
            nested.optJSONObject(index)?.let { rewriteLegacyRcodeRule(it, rcodeTags) }
        }
    }

    private fun migrateLegacyRoutingFields(root: JSONObject) {
        val route = root.optJSONObject("route") ?: JSONObject().also { root.put("route", it) }
        val resolver = route.optString("default_domain_resolver").ifBlank { "dns-direct" }
        val prefixRules = JSONArray()
        val inbounds = root.optJSONArray("inbounds")
        if (inbounds != null) {
            for (index in 0 until inbounds.length()) {
                val inbound = inbounds.optJSONObject(index) ?: continue
                val tag = inbound.optString("tag")
                val sniff = inbound.optBoolean("sniff", false)
                val sniffTimeout = inbound.optString("sniff_timeout")
                val strategy = inbound.optString("domain_strategy")
                val udpDisableUnmap = inbound.optBoolean("udp_disable_domain_unmapping", false)
                val detour = inbound.optString("detour")

                inbound.remove("sniff")
                inbound.remove("sniff_override_destination")
                inbound.remove("sniff_timeout")
                inbound.remove("domain_strategy")
                inbound.remove("udp_disable_domain_unmapping")
                inbound.remove("detour")

                if (tag.isBlank()) continue
                if (sniff) {
                    val rule = JSONObject().put("inbound", JSONArray().put(tag)).put("action", "sniff")
                    if (sniffTimeout.isNotBlank()) rule.put("timeout", sniffTimeout)
                    prefixRules.put(rule)
                }
                if (strategy.isNotBlank() && strategy != "as_is") {
                    prefixRules.put(
                        JSONObject().put("inbound", JSONArray().put(tag))
                            .put("action", "resolve").put("strategy", strategy)
                    )
                }
                if (udpDisableUnmap) {
                    prefixRules.put(
                        JSONObject().put("inbound", JSONArray().put(tag))
                            .put("action", "route-options").put("udp_disable_domain_unmapping", true)
                    )
                }
                if (detour.isNotBlank()) {
                    prefixRules.put(
                        JSONObject().put("inbound", JSONArray().put(tag))
                            .put("action", "route").put("outbound", detour)
                    )
                }
            }
        }

        val oldRules = route.optJSONArray("rules") ?: JSONArray()
        if (prefixRules.length() > 0) {
            val merged = JSONArray()
            for (index in 0 until prefixRules.length()) merged.put(prefixRules.get(index))
            for (index in 0 until oldRules.length()) merged.put(oldRules.get(index))
            route.put("rules", merged)
        }

        val outbounds = root.optJSONArray("outbounds") ?: return
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            if (!outbound.has("domain_strategy")) continue
            val strategy = outbound.optString("domain_strategy")
            outbound.remove("domain_strategy")
            if (strategy.isBlank()) continue
            if (!outbound.has("domain_resolver")) {
                if (strategy == "as_is") outbound.put("domain_resolver", resolver)
                else outbound.put(
                    "domain_resolver",
                    JSONObject().put("server", resolver).put("strategy", strategy),
                )
            }
        }
    }

    private fun mergeKeys(target: JSONObject, destination: String, vararg legacy: String) {
        val values = linkedSetOf<String>()
        readStrings(target.opt(destination), values)
        legacy.forEach { key ->
            readStrings(target.opt(key), values)
            target.remove(key)
        }
        if (values.isNotEmpty()) target.put(destination, JSONArray(values.toList()))
    }

    private fun readStrings(value: Any?, output: MutableSet<String>) {
        when (value) {
            is JSONArray -> for (i in 0 until value.length()) value.optString(i).takeIf(String::isNotBlank)?.let(output::add)
            is String -> value.takeIf(String::isNotBlank)?.let(output::add)
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val pending = File(target.parentFile, target.name + ".pending")
        FileOutputStream(pending).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (target.exists() && !target.delete()) error("无法替换旧配置")
        if (!pending.renameTo(target)) {
            pending.copyTo(target, overwrite = true)
            pending.delete()
        }
    }

    fun start(context: Context) {
        require(hasPreparedConfig(context)) { "Box 配置尚未准备" }
        val apps = ArrayList(DataStore.individual.split('\n').map(String::trim).filter(String::isNotEmpty))
        val intent = Intent(context, BoxVpnService::class.java)
            .setAction(BoxVpnService.START)
            .putExtra("proxyApps", DataStore.proxyApps)
            .putExtra("bypassApps", DataStore.bypass)
            .putStringArrayListExtra("individualApps", apps)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        runCatching { context.stopService(Intent(context, BoxVpnService::class.java)) }
    }

    fun readStatus(context: Context): CoreStatus = runCatching {
        val file = statusFile(context)
        if (!file.isFile) return@runCatching CoreStatus()
        val json = JSONObject(file.readText())
        val updated = json.optLong("updatedAt", 0L)
        val rawState = json.optString("state", "STOPPED")
        val stale = updated > 0 && System.currentTimeMillis() - updated > 12_000L
        val wasActive = rawState == "STARTING" || rawState == "RUNNING" || rawState == "STOPPING"
        val state = if (stale && wasActive) CoreStatus.State.ERROR else runCatching {
            CoreStatus.State.valueOf(rawState)
        }.getOrDefault(CoreStatus.State.STOPPED)
        CoreStatus(
            state = state,
            message = if (stale && wasActive) "Box 状态已失联" else json.optString("message", "未连接"),
            txRate = json.optLong("txRate", 0L),
            rxRate = json.optLong("rxRate", 0L),
            txTotal = json.optLong("txTotal", 0L),
            rxTotal = json.optLong("rxTotal", 0L),
            updatedAt = updated,
            version = json.optString("version", ""),
        )
    }.getOrDefault(CoreStatus())
}
