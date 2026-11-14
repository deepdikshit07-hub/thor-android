package com.thor.assistant

/**
 * Pure command parser for THOR reminders. Android-free for JVM unit testing.
 */
internal object ReminderCommandParser {
    data class Request(
        val relativeDelayMillis: Long?,
        val hour: Int?,
        val minute: Int?,
        val message: String
    )

    private val relativePattern = Regex("""(?i)\bin\s+(\d+)\s+(minutes?|mins?|hours?|hrs?)\b""")
    private val clockPattern = Regex("""(?<!\d)(\d{1,2})[:.](\d{2})(?!\d)""")

    fun parse(command: String): Request {
        val relative = relativePattern.find(command)
        val clock = clockPattern.find(command)
        if (relative == null && clock == null) {
            throw IllegalArgumentException("Use: remind 07:30 Take my book, or remind in 10 minutes Take a break.")
        }

        var delayMillis: Long? = null
        var hour: Int? = null
        var minute: Int? = null
        if (relative != null) {
            val amount = relative.groupValues[1].toLongOrNull()
                ?: throw IllegalArgumentException("That reminder duration is invalid.")
            val unit = relative.groupValues[2].lowercase()
            val isHours = unit.startsWith("hour") || unit.startsWith("hr")
            val maximum = if (isHours) 168L else 10_080L
            if (amount !in 1L..maximum) {
                throw IllegalArgumentException("Choose a reminder between 1 minute and 7 days from now.")
            }
            val multiplier = if (isHours) 3_600_000L else 60_000L
            delayMillis = Math.multiplyExact(amount, multiplier)
        } else {
            hour = clock!!.groupValues[1].toIntOrNull()
            minute = clock.groupValues[2].toIntOrNull()
            if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
                throw IllegalArgumentException("That time is invalid. Use 24-hour time, like 07:30.")
            }
        }

        var message = command
        if (relative != null) message = message.removeRange(relative.range)
        if (clock != null) message = message.removeRange(clock.range)
        message = message.replace(
            Regex("""(?i)^\s*(?:hey\s+thor[\s,]*)?(?:please\s+)?(?:remind(?:er)?|alarm)\b"""),
            " "
        )
        message = message.replace(Regex("""(?i)^\s*(?:me\s+)?(?:to\s+)?"""), " ")
        message = message.replace(Regex("""(?i)^\s*(?:at|in)\s+"""), " ")
            .trim().trim('-', ':', ',', '.', ' ')
        return Request(delayMillis, hour, minute, message)
    }
}
