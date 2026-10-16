package com.lookbuddy.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** One device-local clock for live screens; previews and tests can supply a fixed source. */
internal val LocalAppNow = staticCompositionLocalOf<() -> LocalDateTime> { { LocalDateTime.now() } }

@Composable
internal fun rememberAppTime(previewTime: LocalDateTime? = null): LocalDateTime {
    val source = LocalAppNow.current
    val owner = LocalLifecycleOwner.current
    var now by remember(source, previewTime) { mutableStateOf(previewTime ?: source()) }
    DisposableEffect(owner, source, previewTime) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && previewTime == null) now = source()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(source, previewTime) {
        if (previewTime == null) while (true) {
            now = source()
            delay(1000)
        }
    }
    return previewTime ?: now
}

internal data class WorkSchedule(
    val start: LocalTime = LocalTime.of(9, 0),
    val end: LocalTime = LocalTime.of(18, 0)
) {
    init { require(start != end) }
    fun status(now: LocalDateTime): WorkStatus {
        val day = now.toLocalDate()
        val overnight = end < start
        val shiftDay = if (overnight && now.toLocalTime() < end) day.minusDays(1) else day
        val begin = shiftDay.atTime(start)
        val finish = shiftDay.plusDays(if (overnight) 1 else 0).atTime(end)
        val total = Duration.between(begin, finish).toMinutes()
        val elapsed = Duration.between(begin, now).toMinutes().coerceIn(0, total)
        val working = !now.isBefore(begin) && now.isBefore(finish)
        return WorkStatus(working, elapsed, if (working) Duration.between(now, finish).toMinutes().coerceAtLeast(0) else 0, elapsed.toFloat() / total)
    }
}

internal data class WorkStatus(val working: Boolean, val elapsedMinutes: Long, val remainingMinutes: Long, val progress: Float)
internal fun displayDuration(minutes: Long): String = "%d:%02d".format(minutes / 60, minutes % 60)
internal fun isNightTime(time: LocalTime): Boolean = time >= LocalTime.of(18, 0) || time < LocalTime.of(6, 0)
internal fun daysUntilWeekend(date: LocalDate): Int = if (date.dayOfWeek.value >= 6) 0 else 6 - date.dayOfWeek.value

internal class WorkScheduleStore(context: Context) {
    private val preferences = context.getSharedPreferences("lookbuddy_schedule", Context.MODE_PRIVATE)
    fun read(): WorkSchedule = runCatching {
        WorkSchedule(LocalTime.parse(preferences.getString("start", "09:00")), LocalTime.parse(preferences.getString("end", "18:00")))
    }.getOrDefault(WorkSchedule())
    fun save(schedule: WorkSchedule) {
        preferences.edit().putString("start", schedule.start.toString()).putString("end", schedule.end.toString()).apply()
    }
    fun companionDays(today: LocalDate): Long {
        val first = preferences.getString("first_day", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: today.also { preferences.edit().putString("first_day", it.toString()).apply() }
        return (ChronoUnit.DAYS.between(first, today) + 1).coerceAtLeast(1)
    }
    fun observe(listener: SharedPreferences.OnSharedPreferenceChangeListener) = preferences.registerOnSharedPreferenceChangeListener(listener)
    fun stopObserving(listener: SharedPreferences.OnSharedPreferenceChangeListener) = preferences.unregisterOnSharedPreferenceChangeListener(listener)
}

@Composable
internal fun rememberWorkSchedule(): WorkSchedule {
    val context = LocalContext.current
    val store = remember(context) { WorkScheduleStore(context) }
    var schedule by remember(store) { mutableStateOf(store.read()) }
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> schedule = store.read() }
        store.observe(listener)
        onDispose { store.stopObserving(listener) }
    }
    return schedule
}
