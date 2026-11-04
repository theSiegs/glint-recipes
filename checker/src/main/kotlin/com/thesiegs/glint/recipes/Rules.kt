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
        "channel", "category", "group", "tag", "template", "all", "action:<semantic>", "extra:<key>",
    )
    private val Plain = setOf(
        "title", "text", "bigText", "subText", "infoText", "summaryText", "conversationTitle", "shortCritical",
        "lines", "message", "messages.last", "sender", "messages", "progress", "max", "when", "posted",
        "channel", "category", "group", "tag", "template", "all",
    )
    private val LineAt = Regex("^lines\\[(\\d{1,2})]$")

    fun isKnown(name: String): Boolean = name in Plain || LineAt.matches(name) ||
        name.startsWith("action:") && name.removePrefix("action:") in Semantics.Names ||
        name.startsWith("extra:") && name.length > 6

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
            name.startsWith("extra:") -> n.extras[name.removePrefix("extra:")]
            else -> null
        }
    }?.takeIf { it.isNotBlank() }
}

/**
 * The fields every recipe has without defining them (recipes/SPEC.md §5.2). Taken from the winning
 * notification, except [count] (matching notifications up) and [keys] (subjects up). [time] is when
 * it was posted and [at] its own `when` (a reminder's or an event's time), both as clock times.
 */
object Fields {
    val BuiltIn = setOf(
        "title", "text", "bigText", "subText", "infoText", "summaryText", "conversationTitle", "shortCritical",
        "sender", "message", "progress", "max", "percent", "count", "keys", "lineCount", "time", "at", "app",
    )
}

/** The transforms a field's `then` may list, in order (recipes/SPEC.md §5.3). */
object Transforms {
    val Names = listOf("trim", "lower", "upper", "title", "collapse", "firstLine", "firstWord", "digits", "number", "time", "shorten:<n>")
    private val Shorten = Regex("^shorten:(\\d{1,3})$")
    private val Time = Regex("(?i)\\b(\\d{1,2}):(\\d{2})\\s*([ap])\\.?\\s*m\\.?\\b")
    private val Num = Regex("-?\\d[\\d,]*(?:\\.\\d+)?")

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
