package com.thor.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReminderCommandParserTest {
    @Test fun parsesActionBeforeRelativeTime() {
        val parsed = ReminderCommandParser.parse("remind me to stretch in 10 minutes")
        assertEquals(600_000L, parsed.relativeDelayMillis)
        assertEquals("stretch", parsed.message)
        assertNull(parsed.hour)
    }

    @Test fun parsesActionAfterRelativeTime() {
        val parsed = ReminderCommandParser.parse("remind in 15 minutes Take a break")
        assertEquals(900_000L, parsed.relativeDelayMillis)
        assertEquals("Take a break", parsed.message)
    }

    @Test fun limitsHourDurationsToSevenDays() {
        assertEquals(604_800_000L, ReminderCommandParser.parse("remind in 168 hours test").relativeDelayMillis)
        assertThrows(IllegalArgumentException::class.java) {
            ReminderCommandParser.parse("remind in 169 hours test")
        }
    }

    @Test fun rejectsInvalidClockTime() {
        assertThrows(IllegalArgumentException::class.java) {
            ReminderCommandParser.parse("remind 25:70 take a break")
        }
    }

    @Test fun parsesClockTimeAndMessage() {
        val parsed = ReminderCommandParser.parse("remind 07:30 Take my book")
        assertNull(parsed.relativeDelayMillis)
        assertEquals(7, parsed.hour)
        assertEquals(30, parsed.minute)
        assertEquals("Take my book", parsed.message)
    }

    @Test fun rejectsMissingTime() {
        assertThrows(IllegalArgumentException::class.java) {
            ReminderCommandParser.parse("remind me to call home")
        }
    }
}
