package app.cann

import app.cann.data.Schedule
import app.cann.engine.Scheduler
import app.cann.engine.Templates
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.*

class EngineTest {
    private val now = ZonedDateTime.of(2026, 11, 15, 10, 0, 0, 0, ZoneOffset.UTC)

    @Test fun templates() {
        val outs = listOf(Json.parseToJsonElement("""{"messages":[{"id":"m1","n":3}]}"""))
        val args = Json.parseToJsonElement("""{"id":"{{step1.messages.0.id}}","n":"{{prev.messages.0.n}}","t":"on {{date}}: {{step1.messages.0.id}}","x":"{{step9.a}}"}""")
        val r = Templates.fill(args, outs, now).jsonObject
        assertEquals("m1", r["id"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(3), r["n"])
        assertEquals("on 2026-11-15: m1", r["t"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, r["x"])
    }

    @Test fun daily() {
        val from = now.toInstant().toEpochMilli() // Sunday 10:00 UTC
        val t = Scheduler.next(Schedule.Daily(8, 30), from, ZoneOffset.UTC)!!
        assertEquals(now.plusDays(1).withHour(8).withMinute(30).toInstant().toEpochMilli(), t)
        val wed = Scheduler.next(Schedule.Daily(8, 30, setOf(3)), from, ZoneOffset.UTC)!!
        assertEquals(DayOfWeek.WEDNESDAY, Instant.ofEpochMilli(wed).atZone(ZoneOffset.UTC).dayOfWeek)
        assertEquals(null, Scheduler.next(Schedule.Once(from - 1), from))
        assertEquals(from + 15 * 60_000L, Scheduler.next(Schedule.Interval(5), from))
    }
}
