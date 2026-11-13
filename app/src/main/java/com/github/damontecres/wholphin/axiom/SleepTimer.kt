package com.github.damontecres.wholphin.axiom

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** App-wide sleep timer: stops playback after the chosen time. */
object SleepTimer {
    private val OPTIONS = listOf(0, 30, 60, 90, 120)

    /** Wall-clock time playback stops, or 0 when off */
    var endAt by mutableLongStateOf(0L)
        private set
    private var minutes by mutableStateOf(0)

    val active: Boolean get() = endAt > 0

    /** Off -> 30 -> 60 -> 90 -> 120 -> Off */
    fun cycle() {
        val next = OPTIONS.getOrElse(OPTIONS.indexOf(minutes) + 1) { 0 }
        minutes = next
        endAt = if (next == 0) 0L else System.currentTimeMillis() + next * 60_000L
    }

    fun cancel() {
        minutes = 0
        endAt = 0L
    }

    fun label(now: Long = System.currentTimeMillis()): String =
        if (!active) "Sleep: Off" else "Sleep: ${((endAt - now + 59_999) / 60_000).coerceAtLeast(1)} min"
}

/** Live label that counts down once a minute. */
@Composable
fun sleepTimerLabel(): String {
    val label by produceState(SleepTimer.label(), SleepTimer.endAt) {
        while (true) {
            value = SleepTimer.label()
            delay(15_000)
        }
    }
    return label
}

/** Calls [onExpire] when the sleep timer runs out. */
@Composable
fun SleepTimerEffect(onExpire: () -> Unit) {
    val end = SleepTimer.endAt
    LaunchedEffect(end) {
        if (end <= 0) return@LaunchedEffect
        delay((end - System.currentTimeMillis()).coerceAtLeast(0))
        SleepTimer.cancel()
        onExpire()
    }
}
