package com.arkreader.comic.data

import java.time.Instant
import java.time.ZoneId

data class ReadingTime(val day: String, val comicId: Long, val title: String, val millis: Long)

internal fun readingDays(start: Instant, millis: Long, zone: ZoneId): List<Pair<String, Long>> {
    if (millis <= 0) return emptyList()
    val end = start.plusMillis(millis)
    var cursor = start
    return buildList {
        while (cursor < end) {
            val day = cursor.atZone(zone).toLocalDate()
            val boundary = minOf(day.plusDays(1).atStartOfDay(zone).toInstant(), end)
            add(day.toString() to (boundary.toEpochMilli() - cursor.toEpochMilli()))
            cursor = boundary
        }
    }
}
