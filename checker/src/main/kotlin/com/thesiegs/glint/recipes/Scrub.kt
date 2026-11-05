package com.thesiegs.glint.recipes

import java.security.MessageDigest

/**
 * The sample scrubber (recipes/SPEC.md §10, auto tiles research §6.2): makes a phone's notifications
 * safe to hand to an agent, keeping each string's shape so a recipe written for the scrubbed text
 * still matches the real one. Digits become 9 ("Gate B12 · 1:05 PM" → "Gate B99 · 9:99 PM"), people
 * become placeholder names (always the same one for the same person), emails, links and phone
 * numbers become examples, and every word that isn't a [CommonWords] word or the app's own (its
 * label and package name) becomes a placeholder of the same shape, in any case: "Xxxx", "xxxx",
 * "XXXX". Groups, tags and extras' keys go through the same; extras keep their keys, never their
 * values. The channel id stays as it is here (the phone's own learner and the owner need the real
 * one); what leaves the phone scrubs it too ([com.thesiegs.glint.recipes.exchange.submit.SubmitPayload]).
 * The owner still sees the whole dump before it leaves the phone: this is the first line, not the only one.
 */
object Scrubber {
    /** The placeholder names people become (never a real person's); the shape learner never takes them for the app's words. */
    val People = listOf("Alex", "Sam", "Jordan", "Riley", "Casey", "Morgan", "Taylor", "Jamie", "Avery", "Quinn", "Drew", "Robin")
    private val Email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val Url = Regex("(?i)\\b(?:https?://|www\\.)\\S+")
    private val Phone = Regex("(?<![\\w:])\\+?\\d[\\d ().-]{7,}\\d(?![\\w:])")
    /** A run of letters, with inner apostrophes ("don't", "Sam's"): what [scrubText] judges as one word. */
    private val Run = Regex("\\p{L}+(?:['’]\\p{L}+)*")
    private val Code = Regex("\\b(?=[A-Za-z0-9]*\\d)(?=[A-Za-z0-9]*[A-Za-z])[A-Za-z0-9]{5,}\\b")
    private const val MARK_EMAIL = "\u0001\u0002"
    private const val MARK_URL = "\u0001\u0003"
    private const val MARK_PHONE = "\u0001\u0004"
    const val EXAMPLE_EMAIL = "alex@example.com"
    const val EXAMPLE_URL = "https://example.com"
    const val EXAMPLE_PHONE = "555-0100"
    /** Fixed base for times, so a dump never says when something happened, only how far apart. */
    const val BASE_TIME = 1_700_000_000_000L

    /** [samples] scrubbed; [keep] are words safe to leave (the app's own name); each sample's package's words are kept too. */
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
        val keepWords = keep.flatMapTo(HashSet()) { appWords(null, it) } +
            samples.flatMapTo(HashSet()) { appWords(it.packageName.takeIf { p -> p.isNotEmpty() }, null) }
        val first = samples.minOfOrNull { it.posted.takeIf { p -> p > 0 } ?: Long.MAX_VALUE }?.takeIf { it != Long.MAX_VALUE } ?: 0L
        fun time(t: Long) = if (t <= 0) 0 else BASE_TIME + (t - first)
        fun text(v: String?): String? = v?.let { scrubText(it, names, keepWords) }
        return samples.mapIndexed { i, s ->
            s.copy(
                key = "n${i + 1}",
                posted = time(s.posted),
                whenTime = time(s.whenTime),
                // A group or tag is often an account (Gmail's is the address) or an order: scrubbed like text.
                group = text(s.group),
                tag = text(s.tag),
                title = text(s.title), text = text(s.text), bigText = text(s.bigText), subText = text(s.subText),
                infoText = text(s.infoText), summaryText = text(s.summaryText),
                conversationTitle = text(s.conversationTitle), shortCritical = text(s.shortCritical),
                lines = s.lines.map { scrubText(it, names, keepWords) },
                messages = s.messages.map { m -> m.copy(sender = m.sender?.let { person(it) }, text = text(m.text), time = time(m.time)) },
                // Action words are the app's own UI words, kept; extras keep only their (scrubbed) keys.
                extras = s.extras.keys.associate { k -> scrubText(k, emptyMap(), keepWords) to "…" },
            )
        }
    }

    /**
     * The app's own words, lowercase: the letter runs of its [packageName] ("com.example.parcel" →
     * com, example, parcel) and of its [label]. Kept by the scrubber as the app's name, never a person's.
     */
    fun appWords(packageName: String?, label: String?): Set<String> =
        listOfNotNull(packageName, label).flatMapTo(HashSet()) { s -> Run.findAll(s).map { norm(it.value) } }

    /**
     * [v] scrubbed (see [Scrubber]): [names] are the people to replace (lowercase real name to its
     * placeholder), [keep] the app's own words ([appWords]), kept as [CommonWords] are.
     */
    fun scrubText(v: String, names: Map<String, String> = emptyMap(), keep: Set<String> = emptySet()): String {
        var s = v
        // Known people first, longest first, whole words.
        names.entries.sortedByDescending { it.key.length }.forEach { (real, alias) ->
            s = Regex("(?i)\\b" + Regex.escape(real) + "\\b").replace(s, alias)
        }
        // Examples go in as markers, so the digit mask below leaves them whole (an example already there too).
        s = s.replace(EXAMPLE_PHONE, MARK_PHONE)
        s = Email.replace(s, MARK_EMAIL)
        s = Url.replace(s, MARK_URL)
        s = Phone.replace(s, MARK_PHONE)
        // Long codes (confirmation numbers, order ids): letters to A, digits to 9.
        s = Code.replace(s) { m -> m.value.map { c -> if (c.isDigit()) '9' else if (c.isLetter()) 'A' else c }.joinToString("") }
        s = s.map { if (it.isDigit()) '9' else it }.joinToString("")
        val aliases = names.values.flatMapTo(HashSet()) { a -> Run.findAll(a).map { it.value } }
        s = Run.replace(s) { m -> if (m.value in aliases) m.value else word(m.value, keep) }
        return s.replace(MARK_EMAIL, EXAMPLE_EMAIL).replace(MARK_URL, EXAMPLE_URL).replace(MARK_PHONE, EXAMPLE_PHONE)
    }

    /**
     * One letter run: kept when it's a known word ([CommonWords], [keep], or already a placeholder),
     * else split where a lowercase letter meets an uppercase one ("orderUpdates", "iPhone") and each
     * part kept or masked on its own. Masking keeps the shape: uppercase to X, other letters to x.
     */
    private fun word(run: String, keep: Set<String>): String {
        if (known(run, keep)) return run
        return parts(run).joinToString("") { p -> if (known(p, keep)) p else mask(p) }
    }

    /** [run] split at each lowercase-to-uppercase step (the relay's port splits the same way). */
    fun parts(run: String): List<String> {
        val out = ArrayList<String>()
        var from = 0
        for (i in 1 until run.length) {
            if (run[i - 1].isLowerCase() && run[i].isUpperCase()) { out += run.substring(from, i); from = i }
        }
        out += run.substring(from)
        return out
    }

    private fun norm(w: String) = w.lowercase().replace('’', '\'')

    /** Whether [w] may stay: a common or app word (also with a possessive 's), or a placeholder already (only x, X and apostrophes). */
    fun known(w: String, keep: Set<String>): Boolean {
        if (w.all { it == 'x' || it == 'X' || it == '\'' || it == '’' }) return true
        val n = norm(w)
        fun ok(x: String) = x in CommonWords || x in keep
        return ok(n) || (n.endsWith("'s") && ok(n.dropLast(2)))
    }

    private fun mask(w: String): String = w.map { c -> if (c.isUpperCase()) 'X' else if (c.isLetter()) 'x' else c }.joinToString("")

    /**
     * The words in [v] a scrubbed string can't hold (not common, not the app's [keep], not a
     * placeholder), after taking out the examples: the second line's check
     * ([com.thesiegs.glint.recipes.exchange.submit.SubmitPayload.problems], and the relay's), only counted, never shown.
     */
    fun unknownWords(v: String, keep: Set<String>): List<String> {
        var rest = v
        listOf(EXAMPLE_EMAIL, EXAMPLE_URL, EXAMPLE_PHONE).forEach { rest = rest.replace(it, " ") }
        return Run.findAll(rest).map { it.value }.filter { r -> !known(r, keep) && parts(r).any { !known(it, keep) } }.toList()
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

    /** Where the common-word list lives in this module's resources. */
    const val WORDS = "glint/common-words.txt"

    /**
     * SHA-256 of the list's words ([wordsDigest]). The relay's test pins the same value
     * (glint-recipes-intake relay/test/words.test.js), so one list changed without the other fails a test.
     */
    const val WORDS_SHA256 = "2feed4f48af6daf324f4d2bdc5c121f6e80cbc57483d1fe784b05f8b7bc7c41c"

    /**
     * The common words (glint/common-words.txt): frequent English words and words apps use in
     * notifications, never a common first name, surname, place or brand. The only words
     * [scrubText] keeps besides the app's own. Missing (a broken build), it's empty and every word
     * is masked: safe, never leaky.
     */
    val CommonWords: Set<String> by lazy {
        val text = Scrubber::class.java.classLoader?.getResourceAsStream(WORDS)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        parseWords(text.orEmpty())
    }

    /** A word list's words: whitespace-separated, lowercased; a line starting with # is a comment. */
    fun parseWords(text: String): Set<String> = text.lineSequence().filterNot { it.trimStart().startsWith("#") }
        .flatMap { it.trim().split(Regex("\\s+")) }.filter { it.isNotEmpty() }.mapTo(LinkedHashSet()) { it.lowercase() }

    /** The list's digest: SHA-256 (lowercase hex) of its words, sorted, joined with newlines. */
    fun wordsDigest(words: Set<String>): String =
        MessageDigest.getInstance("SHA-256").digest(words.sorted().joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
