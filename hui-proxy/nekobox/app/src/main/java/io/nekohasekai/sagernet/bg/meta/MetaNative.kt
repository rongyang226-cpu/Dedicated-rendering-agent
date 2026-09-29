package io.nekohasekai.sagernet.bg.meta

import com.github.kr328.clash.core.bridge.Bridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

object MetaNative {
    @JvmStatic
    fun loadProfile(path: String) = runBlocking {
        withTimeout(20_000L) {
            val deferred = CompletableDeferred<Unit>()
            Bridge.INSTANCE.nativeLoad(deferred, path)
            deferred.await()
        }
    }

    @JvmStatic
    fun healthCheck(group: String) = runBlocking {
        withTimeout(15_000L) {
            val deferred = CompletableDeferred<Unit>()
            Bridge.INSTANCE.nativeHealthCheck(deferred, group)
            deferred.await()
        }
    }

    @JvmStatic
    fun updateProvider(type: String, name: String) = runBlocking {
        withTimeout(20_000L) {
            val deferred = CompletableDeferred<Unit>()
            Bridge.INSTANCE.nativeUpdateProvider(deferred, type, name)
            deferred.await()
        }
    }

    @JvmStatic fun coreVersion(): String = Bridge.INSTANCE.nativeCoreVersion()
}
