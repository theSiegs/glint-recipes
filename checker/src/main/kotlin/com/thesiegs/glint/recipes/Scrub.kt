package com.thesiegs.glint.recipes

/**
 * The sample scrubber (recipes/SPEC.md §10, auto tiles research §6.2): makes a phone's notifications
 * safe to hand to an agent, keeping each string's shape so a recipe written for the scrubbed text
 * still matches the real one. Digits become 9 ("Gate B12 · 1:05 PM" → "Gate B99 · 9:99 PM"), people
 * become placeholder names (always the same one for the same person), emails, links and phone
 * numbers become examples, and a capitalized word that isn't a common word or the app's own becomes
 * "Xxxx". Extras keep their keys, never their values. The owner still sees the whole dump before it
 * leaves the phone: this is the first line, not the only one.
 */
object Scrubber {
    /** The placeholder names people become (never a real person's); the shape learner never takes them for the app's words. */
    val People = listOf("Alex", "Sam", "Jordan", "Riley", "Casey", "Morgan", "Taylor", "Jamie", "Avery", "Quinn", "Drew", "Robin")
    private val Email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val Url = Regex("(?i)\\b(?:https?://|www\\.)\\S+")
    private val Phone = Regex("(?<![\\w:])\\+?\\d[\\d ().-]{7,}\\d(?![\\w:])")
    private val Word = Regex("\\b[\\p{L}][\\p{L}'’]*\\b")
    private val Code = Regex("\\b(?=[A-Za-z0-9]*\\d)(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]{5,}\\b")
    private const val MARK_EMAIL = "\u0001\u0002"
    private const val MARK_URL = "\u0001\u0003"
    private const val MARK_PHONE = "\u0001\u0004"
    /** Fixed base for times, so a dump never says when something happened, only how far apart. */
    const val BASE_TIME = 1_700_000_000_000L

    /** [samples] scrubbed; [keep] are words safe to leave (the app's own name). */
    fun scrub(samples: List<NotificationSample>, keep: Set<String> = emptySet()): List<NotificationSample> {
        val names = LinkedHashMap<String, String>()
        fun person(name: String?): String? {
            val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return names.getOrPut(n.lowercase()) { People[names.size % People.size] + if (names.size >= People.size) " ${names.size / People.size + 1}" else "" }
        }
        // Everyone the notifications name, first, so they're replaced wherever they occur.
        samples.forEach { s ->
            s.messages.forEach { person(it.sender) }
            if (s.template == "MessagingStyle" || s.messages.isNotEmpty()) person(s.conversationTitle)
        }
        val keepWords = (CommonWords + keep.flatMap { k -> Word.findAll(k).map { it.value.lowercase() } }).toSet()
        val first = samples.minOfOrNull { it.posted.takeIf { p -> p > 0 } ?: Long.MAX_VALUE }?.takeIf { it != Long.MAX_VALUE } ?: 0L
        fun time(t: Long) = if (t <= 0) 0 else BASE_TIME + (t - first)
        fun text(v: String?): String? = v?.let { scrubText(it, names, keepWords) }
        return samples.mapIndexed { i, s ->
            s.copy(
                key = "n${i + 1}",
                posted = time(s.posted),
                whenTime = time(s.whenTime),
                group = s.group?.let { masked(it) },
                tag = s.tag?.let { masked(it) },
                title = text(s.title), text = text(s.text), bigText = text(s.bigText), subText = text(s.subText),
                infoText = text(s.infoText), summaryText = text(s.summaryText),
                conversationTitle = text(s.conversationTitle), shortCritical = text(s.shortCritical),
                lines = s.lines.map { scrubText(it, names, keepWords) },
                messages = s.messages.map { m -> m.copy(sender = m.sender?.let { person(it) }, text = text(m.text), time = time(m.time)) },
                // Action words are the app's own UI words, kept; extras keep only their keys.
                extras = s.extras.mapValues { "…" },
            )
        }
    }

    /** A system key or tag: its digits masked, so an order number never leaves. */
    private fun masked(v: String) = v.map { if (it.isDigit()) '9' else it }.joinToString("")

    fun scrubText(v: String, names: Map<String, String> = emptyMap(), keep: Set<String> = CommonWords): String {
        var s = v
        // Known people first, longest first, whole words.
        names.entries.sortedByDescending { it.key.length }.forEach { (real, alias) ->
            s = Regex("(?i)\\b" + Regex.escape(real) + "\\b").replace(s, alias)
        }
        // Examples go in as markers, so the digit mask below leaves them whole.
        s = Email.replace(s, MARK_EMAIL)
        s = Url.replace(s, MARK_URL)
        s = Phone.replace(s, MARK_PHONE)
        // Long codes (confirmation numbers, order ids): letters to A, digits to 9.
        s = Code.replace(s) { m -> m.value.map { c -> if (c.isDigit()) '9' else if (c.isLetter()) 'A' else c }.joinToString("") }
        s = s.map { if (it.isDigit()) '9' else it }.joinToString("")
        val aliases = names.values.toSet()
        s = Word.replace(s) { m ->
            val w = m.value
            when {
                w in aliases || w == "Alex" || w == "Xxxx" -> w
                w.lowercase().trimEnd('\'', '’', 's') in keep || w.lowercase() in keep -> w
                // Lowercase words are ordinary words; a capitalized or all-caps word may be a name or a place.
                w.first().isLowerCase() -> w
                w.length >= 2 && w.all { it.isUpperCase() } -> "X".repeat(w.length)
                else -> "X" + "x".repeat((w.length - 1).coerceAtLeast(0))
            }
        }
        return s.replace(MARK_EMAIL, "alex@example.com").replace(MARK_URL, "https://example.com").replace(MARK_PHONE, "555-0100")
    }

    /**
     * Strings that look unscrubbed (an email, a link, a phone number, a long run of real digits), for
     * the checker's "no unscrubbed strings" rule on fixtures.
     */
    fun leaks(v: String): List<String> = buildList {
        Email.findAll(v).filter { !it.value.endsWith("@example.com") }.forEach { add("email ${it.value}") }
        Url.findAll(v).filter { !it.value.contains("example.com") }.forEach { add("link ${it.value}") }
        Phone.findAll(v).filter { m -> !m.value.contains("555") && m.value.any { it in '0'..'8' } }.forEach { add("phone number ${it.value}") }
    }

    /**
     * Common English words a notification starts a sentence with, or capitalizes, that say nothing
     * about anyone: kept when capitalized. Lowercase words are always kept.
     */
    val CommonWords: Set<String> = """
        a about after all already an and any are arrived arriving at available back be been before being boarding
        boards book booked but by call can cancel canceled cancelled change changed check checked check-in click
        come coming complete completed confirm confirmed continue could day days delayed delay deliver delivered
        delivery departs departure departing did do does done download downloaded downloading due each early email
        estimated every expected flight flights for from gate get go good got had has have here hi hello how i if in
        inbox is it its just last later left like live mail make mark marked message messages missed more new news
        next no not note notes now of off on one open order ordered orders our out over package packages paid
        payment pick picked please posted primary reminder reminders reply review saved see seat sent set shipped
        shipping since soon start started still subscribe subscribed suggested task tasks thanks that the their
        there these they this time to today tomorrow track tracking trip try up update updated updates upload
        uploaded uploading video videos view waiting was watch we welcome were what when where which who will with
        would yes yesterday you your yours
        monday tuesday wednesday thursday friday saturday sunday
        january february march april may june july august september october november december
        jan feb mar apr jun jul aug sep sept oct nov dec mon tue wed thu fri sat sun
        am pm min mins hr hrs
        doorbell door front back camera person someone seen motion detected package
        unread starred important social promotions starts ends gate terminal
    """.trim().split(Regex("\\s+")).toSet()
}
