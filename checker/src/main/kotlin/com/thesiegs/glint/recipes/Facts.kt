package com.thesiegs.glint.recipes

import java.util.Calendar
import java.util.TimeZone

/**
 * One vocabulary for the typed facts a notification's words carry (review 2026-11-10 §2.3 stage 3):
 * a percent and whether it's a limit, a range, power, time left, a clock time ("full by 3:10 PM",
 * "9:52 am"), a route ("BWI → CUN"), a money amount, a sign-in code's digits. Recipes, Live Updates,
 * the car's notification reader and Wallet/Suggest all read through here, so a new app kind
 * doesn't bring a new parser. Pure, so tested on fixtures (`FactsTest`).
 */
object Facts {
    // ---- Percent

    private val Percent = Regex("""(\d{1,3})\s?%""")
    private val LimitBefore = Regex("""(?:to|until|limit(?: of)?|target(?: of)?)\s*$""", RegexOption.IGNORE_CASE)
    private val LimitAfter = Regex("""^\s*(?:charge\s+)?(?:limit|target)""", RegexOption.IGNORE_CASE)

    /** A percent in [text] (0..100): [limit] when it's a target ("to 80%", "80% limit"). */
    data class PercentHit(val value: Int, val limit: Boolean)

    fun percents(text: String): List<PercentHit> = Percent.findAll(text).mapNotNull { m ->
        val v = m.groupValues[1].toInt().takeIf { it in 0..100 } ?: return@mapNotNull null
        val before = text.substring(0, m.range.first)
        val after = text.substring(m.range.last + 1)
        PercentHit(v, LimitBefore.containsMatchIn(before) || LimitAfter.containsMatchIn(after))
    }.toList()

    /** The first level and the first limit in [text]: "33% · charging to 80%" is 33 and 80. */
    fun levelAndLimit(text: String): Pair<Int?, Int?> {
        val hits = percents(text)
        return hits.firstOrNull { !it.limit }?.value to hits.firstOrNull { it.limit }?.value
    }

    // ---- Distance and power

    private val Range = Regex("""(\d{1,3}(?:,\d{3})*|\d+)\s*(?:est\.?\s+|estimated\s+)?(mi|miles|km)\b""", RegexOption.IGNORE_CASE)
    private val Power = Regex("""(\d+(?:\.\d+)?)\s*kw(?!h)\b""", RegexOption.IGNORE_CASE)

    /** A distance ("69 est mi", "1,204 km") and its unit, "mi" or "km". */
    fun distance(text: String): Pair<Double, String>? = Range.find(text)?.let { m ->
        val v = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        v to (if (m.groupValues[2].lowercase().startsWith("mi")) "mi" else "km")
    }

    /** Power in kW ("8 kw", not "kWh"). */
    fun powerKw(text: String): Double? = Power.find(text)?.groupValues?.get(1)?.toDoubleOrNull()

    // ---- Time

    private val Left = Regex(
        """(?:\b(\d+)\s*h(?:ours?|rs?)?\.?\s*)?(?:\b(\d+)\s*m(?:in(?:ute)?s?)?\.?)?\s+(?:to|until|left|remaining)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val By = Regex(
        """\b(?:full|done|complete[ds]?|ready|finish(?:ed|es)?)\s+(?:by|at|around)\s+(\d{1,2})(?::(\d{2}))?\s*([ap])?\.?\s*m?\.?""",
        RegexOption.IGNORE_CASE,
    )

    /** "3h 45m to 80%", "20 min left": minutes; null when none (or zero). */
    fun minutesLeft(text: String): Int? =
        Left.findAll(text).firstOrNull { it.groupValues[1].isNotEmpty() || it.groupValues[2].isNotEmpty() }
            ?.let { (it.groupValues[1].toIntOrNull() ?: 0) * 60 + (it.groupValues[2].toIntOrNull() ?: 0) }?.takeIf { it > 0 }

    /** "full by 3:10 PM", "ready at 7": the first such time at or after [from]. */
    fun doneBy(text: String, from: Long, zone: TimeZone = TimeZone.getDefault()): Long? =
        By.find(text)?.let { m -> clockAfter(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3], from, zone) }

    /** The first time [hour]:[minute] ([ap] "a"/"p", or 24-hour) at or after [from] in [zone]. */
    fun clockAfter(hour: Int, minute: Int, ap: String, from: Long, zone: TimeZone): Long? {
        if (minute !in 0..59) return null
        val h = when (ap.lowercase()) {
            "a" -> if (hour == 12) 0 else hour
            "p" -> if (hour == 12) 12 else hour + 12
            else -> hour
        }
        if (h !in 0..23) return null
        val c = Calendar.getInstance(zone).apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        // "full by 3:10" without am/pm: the next 3:10 on a 12-hour clock.
        if (ap.isEmpty() && hour in 1..12) {
            while (c.timeInMillis < from) c.add(Calendar.HOUR_OF_DAY, 12)
        } else if (c.timeInMillis < from) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /** A clock time in words: "9:52 am", "9 pm", "21:52" (groups 1-3 a 12-hour time, 4-5 a 24-hour one). */
    val Clock = Regex("(?i)\\b(\\d{1,2})(?::(\\d{2}))?\\s*([ap])\\.?\\s*m\\b\\.?|\\b(\\d{1,2}):(\\d{2})\\b")

    /** A 12-hour clock time with minutes ("9:52 am"), as the `time` transform keeps it. */
    val ClockMinutes = Regex("(?i)\\b(\\d{1,2}):(\\d{2})\\s*([ap])\\.?\\s*m\\.?\\b")

    /** A number as words carry it: "-1,204.5". */
    val Number = Regex("-?\\d[\\d,]*(?:\\.\\d+)?")

    // ---- Route

    /** A trip's route between two airport-like codes: "BWI → CUN", "BWI to CUN". */
    val RouteRx = Regex("""\b([A-Z]{3})\s*(?:to|→|->|–|—|-|›|>|✈)\s*([A-Z]{3})\b""")

    /** The route in [text] (from, to), or null. */
    fun route(text: String): Pair<String, String>? = RouteRx.find(text)?.let { it.groupValues[1] to it.groupValues[2] }

    /** [text] with its route taken out and the gap tidied. */
    fun withoutRoute(text: String): String = RouteRx.replace(text, "").replace(Regex("""\s{2,}"""), " ").trim().trim('·', '-', ',').trim()

    // ---- Money and codes

    /** A money amount: "$4.50", "12,00 EUR". */
    val Amount = Regex("""(?:[$€£¥₹₩]\s?\d)|(?:\d[\d,.]*\s?(?:USD|EUR|GBP|JPY|INR|CAD|AUD)\b)""")

    fun hasAmount(text: String): Boolean = Amount.containsMatchIn(text)

    /** A sign-in code's digits: 4 to 8 digits standing alone (not part of a number). */
    val CodeDigits = Regex("""(?<![\d.,])\d{4,8}(?![\d.,])""")
}
