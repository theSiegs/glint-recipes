package com.thesiegs.glint.recipes

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Regexes for recipes run on RE2J (linear time, no backtracking), so a recipe from anywhere can never
 * hang Glint. Java's `(?<name>…)` groups are read as RE2's `(?P<name>…)`. Compiled patterns are kept.
 */
object Rx {
    private val cache = object : LinkedHashMap<String, com.google.re2j.Pattern>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, com.google.re2j.Pattern>?) = size > 256
    }

    fun compile(pattern: String): com.google.re2j.Pattern = synchronized(cache) {
        cache.getOrPut(pattern) { com.google.re2j.Pattern.compile(pattern.replace("(?<", "(?P<").replace("(?P<=", "(?<=").replace("(?P<!", "(?<!")) }
    }

    fun find(pattern: String, text: String): Boolean = compile(pattern).matcher(cap(text)).find()

    /** The named group `v`, else group 1, else the whole match; null when it doesn't match. */
    fun capture(pattern: String, text: String): String? {
        val m = compile(pattern).matcher(cap(text))
        if (!m.find()) return null
        val named = try { m.group("v") } catch (_: Exception) { null }
        if (named != null) return named
        if (m.groupCount() >= 1) return m.group(1)
        return m.group()
    }

    private fun cap(text: String) = if (text.length > Recipes.MAX_INPUT) text.substring(0, Recipes.MAX_INPUT) else text
}

/** Where a field's text comes from (recipes/SPEC.md §5.1), read off one [NotificationSample]. */
object Sources {
    val Names = listOf(
        "title", "text", "bigText", "subText", "infoText", "summaryText", "conversationTitle", "shortCritical",
        "lines", "lines[n]", "message", "sender", "messages", "progress", "max", "when", "posted",
        "channel", "category", "group", "tag", "template", "all", "action:<semantic>", "fact:<name>",
    )

    /**
     * Typed facts read from the notification's words by [Facts] (title · text · subText): `fact:percent`
     * ("62"), `fact:limitPercent` ("80"), `fact:distance` ("69"), `fact:distanceUnit` ("mi"),
     * `fact:power` ("7.2", kW), `fact:minutesLeft` ("45"), `fact:doneBy` (a time in ms), `fact:route` ("BWI → CUN").
     */
    val FactNames = setOf("percent", "limitPercent", "distance", "distanceUnit", "power", "minutesLeft", "doneBy", "route")
    private val Plain = setOf(
        "title", "text", "bigText", "subText", "infoText", "summaryText", "conversationTitle", "shortCritical",
        "lines", "message", "messages.last", "sender", "messages", "progress", "max", "when", "posted",
        "channel", "category", "group", "tag", "template", "all",
    )
    private val LineAt = Regex("^lines\\[(\\d{1,2})]$")

    fun isKnown(name: String): Boolean = name in Plain || LineAt.matches(name) ||
        name.startsWith("action:") && name.removePrefix("action:") in Semantics.Names ||
        name.startsWith("fact:") && name.removePrefix("fact:") in FactNames

    fun read(name: String, n: NotificationSample): String? = when (name) {
        "title" -> n.title
        "text" -> n.text
        "bigText" -> n.bigText
        "subText" -> n.subText
        "infoText" -> n.infoText
        "summaryText" -> n.summaryText
        "conversationTitle" -> n.conversationTitle
        "shortCritical" -> n.shortCritical
        "lines" -> n.lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
        "message", "messages.last" -> n.lastMessage?.text
        "sender" -> n.lastMessage?.sender
        "messages" -> n.messages.mapNotNull { it.text }.takeIf { it.isNotEmpty() }?.joinToString("\n")
        "progress" -> n.progress?.takeIf { (n.max ?: 0) > 0 }?.toString()
        "max" -> n.max?.takeIf { it > 0 }?.toString()
        "when" -> n.whenTime.takeIf { it > 0 }?.toString()
        "posted" -> n.posted.takeIf { it > 0 }?.toString()
        "channel" -> n.channel
        "category" -> n.category
        "group" -> n.group
        "tag" -> n.tag
        "template" -> n.template
        "all" -> listOfNotNull(n.title, n.text, n.bigText).takeIf { it.isNotEmpty() }?.joinToString("\n")
        else -> when {
            LineAt.matches(name) -> n.lines.getOrNull(LineAt.matchEntire(name)!!.groupValues[1].toInt())
            name.startsWith("action:") -> name.removePrefix("action:").let { s -> n.actions.firstOrNull { it.semantic == s }?.title }
            name.startsWith("fact:") -> fact(name.removePrefix("fact:"), n)
            else -> null
        }
    }?.takeIf { it.isNotBlank() }

    /** The words [Facts] reads: title, text and sub text, as a car app's charging line is read. */
    fun factText(n: NotificationSample): String = listOfNotNull(n.title, n.text, n.subText).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")

    private fun fact(name: String, n: NotificationSample): String? {
        val t = factText(n).takeIf { it.isNotEmpty() } ?: return null
        return when (name) {
            "percent" -> Facts.levelAndLimit(t).first?.toString()
            "limitPercent" -> Facts.levelAndLimit(t).second?.toString()
            "distance" -> Facts.distance(t)?.first?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
            "distanceUnit" -> Facts.distance(t)?.second
            "power" -> Facts.powerKw(t)?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
            "minutesLeft" -> Facts.minutesLeft(t)?.toString()
            "doneBy" -> Facts.doneBy(t, n.posted, java.util.TimeZone.getDefault())?.toString()
            "route" -> Facts.route(t)?.let { (a, b) -> "$a → $b" }
            else -> null
        }
    }
}

/**
 * The fields every recipe has without defining them (recipes/SPEC.md §5.2). Taken from the winning
 * notification, except [count] (matching notifications up) and [keys] (subjects up). [time] is when
 * it was posted and [at] its own `when` (a reminder's or an event's time), both as clock times;
 * `posted` and `when` are the same as times for a format (`{posted|ago}`, `{when|in}`), and `chrono`
 * is a running chronometer's time ("12 min", up or down as the notification counts).
 */
object Fields {
    val BuiltIn = setOf(
        "title", "text", "bigText", "subText", "infoText", "summaryText", "conversationTitle", "shortCritical",
        "sender", "message", "progress", "max", "percent", "count", "keys", "lineCount", "time", "at", "app",
        "posted", "when", "chrono", "limitPercent", "distance", "power", "eta", "route",
    )

    /** Built-ins that are a time in ms, only worth showing through a format. */
    val RawTimes = setOf("posted", "when", "eta")

    /** Built-ins that change as the clock runs, so a face showing one needs the minute tick. */
    val Ticking = setOf("chrono")
}

/**
 * Relative times for faces (recipes/SPEC.md §5.4), Glint's quiet words: `{eta|in}` "in 12 min",
 * `{eta|left}` "12 min", `{posted|ago}` "2 h ago", `{posted|for}` "12 min", `{posted|since}`
 * "since 7:55 am", `{eta|clock}` "9:52 am", `{when|near}` "in 12 min" within the hour, else
 * "1:00 pm". A value is a time in ms (`{posted}`, `{when}`, a field
 * `from: "when"`) or a clock time ("9:52 am", "21:52": the nearest such time to now). A format that
 * doesn't fit (a time already past for `in`) leaves the template unfilled, so the next face draws.
 */
object Times {
    val Formats = listOf("in", "left", "ago", "for", "since", "clock", "near")
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR
    private val Clock = Facts.Clock

    fun format(format: String, value: String, ctx: RecipeContext): String? {
        val t = parse(value, ctx) ?: return null
        val d = t - ctx.now
        return when (format) {
            "in" -> when {
                d < -MIN -> null
                d < MIN -> "now"
                else -> "in " + span(d, up = true)
            }
            "left" -> if (d < -MIN) null else if (d < MIN) "now" else span(d, up = true)
            "ago" -> when {
                d > MIN -> null
                -d < MIN -> "just now"
                -d < DAY -> span(-d, up = false, short = true) + " ago"
                -d < 2 * DAY -> "yesterday"
                else -> "${-d / DAY} d ago"
            }
            "for" -> if (d > MIN) null else span(maxOf(-d, MIN), up = false)
            "since" -> if (d > MIN) null else "since " + clockTime(t, ctx.zone, ctx.h24)
            "clock" -> clockTime(t, ctx.zone, ctx.h24)
            // Within the hour as minutes ("in 12 min", "5 min ago"), else the clock time.
            "near" -> when {
                d >= HOUR || d <= -HOUR -> clockTime(t, ctx.zone, ctx.h24)
                d >= MIN -> "in " + span(d, up = true)
                d > -MIN -> "now"
                else -> span(-d, up = false) + " ago"
            }
            else -> null
        }
    }

    /** [value] as a time in ms: a number of ms, or a clock time taken as the nearest one to now. */
    fun parse(value: String, ctx: RecipeContext): Long? {
        val v = value.trim()
        if (v.length >= 10 && v.all { it.isDigit() }) return v.toLongOrNull()?.takeIf { it > 0 }
        val m = Clock.find(v) ?: return null
        val (h, min) = if (m.groupValues[3].isNotEmpty()) {
            val h12 = m.groupValues[1].toInt()
            if (h12 !in 1..12) return null
            val pm = m.groupValues[3].equals("p", ignoreCase = true)
            (h12 % 12 + if (pm) 12 else 0) to (m.groupValues[2].toIntOrNull() ?: 0)
        } else {
            m.groupValues[4].toInt() to m.groupValues[5].toInt()
        }
        if (h !in 0..23 || min !in 0..59) return null
        val day = java.time.Instant.ofEpochMilli(ctx.now).atZone(ctx.zone).toLocalDate()
        val at = day.atTime(h, min).atZone(ctx.zone).toInstant().toEpochMilli()
        // The nearest such time: "9:52 am" read at 11 pm is tomorrow's, at 1 am yesterday's.
        return when {
            at - ctx.now > 12 * HOUR -> at - DAY
            ctx.now - at > 12 * HOUR -> at + DAY
            else -> at
        }
    }

    /** "12 min", "1 h 5 min", "3 h", "2 d": counting down rounds up, counting up rounds down. */
    fun span(ms: Long, up: Boolean, short: Boolean = false): String {
        val mins = if (up) (ms + MIN - 1) / MIN else ms / MIN
        val h = mins / 60
        val m = mins % 60
        return when {
            mins < 60 -> "${maxOf(mins, 1)} min"
            mins >= 48 * 60 -> "${mins / (24 * 60)} d"
            short || h >= 10 || m == 0L -> "$h h"
            else -> "$h h $m min"
        }
    }

    /** A running chronometer as words: the time left to [whenTime] counting down, else the time since it. */
    fun chrono(whenTime: Long, countDown: Boolean, now: Long): String? = when {
        whenTime <= 0 -> null
        countDown -> if (whenTime - now < MIN) "now" else span(whenTime - now, up = true)
        else -> span(maxOf(now - whenTime, MIN), up = false)
    }
}

/** The transforms a field's `then` may list, in order (recipes/SPEC.md §5.3). */
object Transforms {
    val Names = listOf("trim", "lower", "upper", "title", "collapse", "firstLine", "firstWord", "digits", "number", "time", "shorten:<n>")
    private val Shorten = Regex("^shorten:(\\d{1,3})$")
    private val Time = Facts.ClockMinutes
    private val Num = Facts.Number

    fun isKnown(t: String): Boolean = t in Names || Shorten.matches(t)

    fun apply(t: String, v: String): String? = when (t) {
        "trim" -> v.trim()
        "lower" -> v.lowercase()
        "upper" -> v.uppercase()
        "title" -> v.split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.titlecase() } }
        "collapse" -> v.replace(Regex("\\s+"), " ").trim()
        "firstLine" -> v.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        "firstWord" -> v.trim().split(Regex("\\s+")).firstOrNull()
        "digits" -> v.filter { it.isDigit() }
        "number" -> Num.find(v)?.value
        // "1:05 PM" and "1:05p.m." read as "1:05 pm", Glint's quiet lowercase.
        "time" -> Time.find(v)?.let { m -> "${m.groupValues[1].toInt()}:${m.groupValues[2]} ${m.groupValues[3].lowercase()}m" } ?: v
        else -> Shorten.matchEntire(t)?.let { m ->
            val n = m.groupValues[1].toInt().coerceAtLeast(1)
            if (v.length <= n) v else v.substring(0, n).trimEnd() + "…"
        } ?: v
    }?.takeIf { it.isNotBlank() }
}

/**
 * A face's `when` conditions (recipes/SPEC.md §6.2): "gate" (has a value), "!gate" (has none),
 * "count>1", "status==delivered", "status!=delivered", and alternatives joined by "|".
 */
object Conditions {
    private val Atom = Regex("^\\s*(!)?([A-Za-z][A-Za-z0-9_]*)\\s*(?:(==|!=|>=|<=|>|<|=)\\s*(.*?))?\\s*$")

    fun problem(c: String, known: Set<String>): String? {
        for (alt in c.split('|')) {
            val m = Atom.matchEntire(alt) ?: return "\"$alt\" isn't a condition (\"gate\", \"!gate\", \"count>1\", \"status==delivered\")"
            if (m.groupValues[2] !in known) return "{${m.groupValues[2]}} isn't a field"
            val op = m.groupValues[3]
            if (op in setOf(">", "<", ">=", "<=") && m.groupValues[4].toDoubleOrNull() == null) return "\"$alt\" compares with a number"
        }
        return null
    }

    fun holds(c: String, values: Map<String, String>): Boolean = c.split('|').any { alt ->
        val m = Atom.matchEntire(alt) ?: return@any false
        val not = m.groupValues[1] == "!"
        val v = values[m.groupValues[2]]
        val op = m.groupValues[3]
        val rhs = m.groupValues[4]
        when {
            op.isEmpty() -> (v != null) != not
            v == null -> op == "!="
            op == "==" || op == "=" -> v.equals(rhs, ignoreCase = true)
            op == "!=" -> !v.equals(rhs, ignoreCase = true)
            else -> {
                val a = Transforms.apply("number", v)?.replace(",", "")?.toDoubleOrNull() ?: return@any false
                val b = rhs.toDoubleOrNull() ?: return@any false
                when (op) { ">" -> a > b; "<" -> a < b; ">=" -> a >= b; else -> a <= b }
            }
        }
    }
}

/** A clock time as tiles write it ("4:12 pm", or "16:12" on a 24-hour phone). */
internal fun clockTime(t: Long, zone: ZoneId, h24: Boolean): String =
    DateTimeFormatter.ofPattern(if (h24) "H:mm" else "h:mm a", Locale.US).withZone(zone).format(Instant.ofEpochMilli(t)).lowercase()
