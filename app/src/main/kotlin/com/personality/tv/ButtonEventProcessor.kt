package com.personality.tv

internal object ButtonEventProcessor {
    const val DOUBLE_PRESS_MS = 280L
    const val LONG_PRESS_MS = 550L

    fun isLong(down: Long, up: Long): Boolean = up - down >= LONG_PRESS_MS
    fun isDouble(firstUp: Long, secondDown: Long): Boolean =
        secondDown - firstUp in 0..DOUBLE_PRESS_MS
}
