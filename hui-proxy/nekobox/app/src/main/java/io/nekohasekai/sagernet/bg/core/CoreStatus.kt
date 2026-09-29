package io.nekohasekai.sagernet.bg.core

data class CoreStatus(
    val state: State = State.STOPPED,
    val message: String = "未连接",
    val txRate: Long = 0L,
    val rxRate: Long = 0L,
    val txTotal: Long = 0L,
    val rxTotal: Long = 0L,
    val updatedAt: Long = 0L,
    val version: String = "",
) {
    enum class State { STOPPED, STARTING, RUNNING, STOPPING, ERROR }
    val active: Boolean get() = state == State.STARTING || state == State.RUNNING || state == State.STOPPING
}
