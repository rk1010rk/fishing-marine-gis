package jp.tasklock.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「1日」の区切り。MVPは 0時固定だが、夜型ユーザー向けに開始時刻を変えられるようにしておく。
 */
data class DayBoundary(val zone: ZoneId, val startHour: Int = 0) {
    init {
        require(startHour in 0..23) { "startHour must be 0..23" }
    }

    fun dayOf(instant: Instant): LocalDate {
        val local = instant.atZone(zone)
        val date = local.toLocalDate()
        return if (local.hour < startHour) date.minusDays(1) else date
    }

    fun startOf(day: LocalDate): Instant = day.atStartOfDay(zone).plusHours(startHour.toLong()).toInstant()

    fun endOf(day: LocalDate): Instant = startOf(day.plusDays(1))
}
