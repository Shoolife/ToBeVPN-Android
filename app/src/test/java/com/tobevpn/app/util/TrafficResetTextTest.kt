package com.tobevpn.app.util

import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TrafficResetTextTest {
    private lateinit var originalZone: TimeZone

    @Before
    fun setUp() {
        originalZone = TimeZone.getDefault()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun `counts calendar days, not 24 hour spans`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
        val lateEvening = at(2026, Calendar.NOVEMBER, 2, 23, 50)
        assertEquals(0L, calendarDaysBetween(lateEvening, at(2026, Calendar.NOVEMBER, 2, 23, 59)))
        assertEquals(1L, calendarDaysBetween(lateEvening, at(2026, Calendar.NOVEMBER, 3, 0, 10)))
        assertEquals(2L, calendarDaysBetween(lateEvening, at(2026, Calendar.NOVEMBER, 4, 23, 0)))
    }

    @Test
    fun `a daylight saving change does not shift the day count`() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        // Clocks go back on 2026-10-25: that day is 25 hours long.
        val before = at(2026, Calendar.OCTOBER, 24, 12, 0)
        assertEquals(1L, calendarDaysBetween(before, at(2026, Calendar.OCTOBER, 25, 23, 30)))
        assertEquals(2L, calendarDaysBetween(before, at(2026, Calendar.OCTOBER, 26, 0, 30)))
        // Clocks go forward on 2027-03-28: that day is 23 hours long.
        val spring = at(2027, Calendar.MARCH, 27, 12, 0)
        assertEquals(1L, calendarDaysBetween(spring, at(2027, Calendar.MARCH, 28, 23, 30)))
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, minute)
        }.timeInMillis
}
