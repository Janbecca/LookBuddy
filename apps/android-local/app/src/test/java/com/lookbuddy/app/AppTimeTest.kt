package com.lookbuddy.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class AppTimeTest {
    private val schedule = WorkSchedule()
    private fun at(hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 10, 16, hour, minute)
    @Test fun workBoundariesAndCountdownUseRealSchedule() {
        assertFalse(schedule.status(at(8, 59)).working)
        assertTrue(schedule.status(at(9)).working)
        val middle = schedule.status(at(12, 15))
        assertEquals(195L, middle.elapsedMinutes)
        assertEquals(345L, middle.remainingMinutes)
        assertEquals("3:15", displayDuration(middle.elapsedMinutes))
        assertTrue(schedule.status(at(17, 59)).working)
        assertFalse(schedule.status(at(18)).working)
        assertEquals(0L, schedule.status(at(19)).remainingMinutes)
    }
    @Test fun overnightShiftHandlesMidnightAndEndBoundary() {
        val night = WorkSchedule(LocalTime.of(22, 0), LocalTime.of(6, 0))
        assertTrue(night.status(at(23)).working)
        assertTrue(night.status(at(2)).working)
        assertEquals(240L, night.status(at(2)).elapsedMinutes)
        assertFalse(night.status(at(6)).working)
        assertFalse(night.status(at(12)).working)
    }
    @Test fun eveningBackgroundIsIndependentOfWorkingHours() {
        assertFalse(isNightTime(LocalTime.of(17, 59)))
        assertTrue(isNightTime(LocalTime.of(18, 0)))
        assertTrue(isNightTime(LocalTime.of(0, 0)))
        assertFalse(isNightTime(LocalTime.of(6, 0)))
        assertEquals(1, daysUntilWeekend(LocalDate.of(2026, 10, 16)))
        assertEquals(0, daysUntilWeekend(LocalDate.of(2026, 10, 17)))
    }
}
