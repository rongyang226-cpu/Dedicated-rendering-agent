package io.nekohasekai.sagernet.bg.core

enum class CoreEngine(val id: String, val displayName: String) {
    BOX("box", "Box · sing-box"),
    META("meta", "Meta · Mihomo");

    companion object {
        fun fromId(value: String?): CoreEngine = when (value?.lowercase()) {
            "meta", "mihomo", "clash", "clashmeta" -> META
            else -> BOX
        }
    }
}
