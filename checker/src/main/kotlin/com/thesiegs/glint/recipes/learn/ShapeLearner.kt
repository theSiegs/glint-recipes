package com.thesiegs.glint.recipes.learn

import com.thesiegs.glint.recipes.FaceKind
import com.thesiegs.glint.recipes.FaceRule
import com.thesiegs.glint.recipes.NotificationSample
import com.thesiegs.glint.recipes.NotificationSource
import com.thesiegs.glint.recipes.Recipe
import com.thesiegs.glint.recipes.RecipeApp
import com.thesiegs.glint.recipes.RecipeContext
import com.thesiegs.glint.recipes.RecipeEngine
import com.thesiegs.glint.recipes.RecipeMatch
import com.thesiegs.glint.recipes.RecipeMeta
import com.thesiegs.glint.recipes.RecipeSource
import com.thesiegs.glint.recipes.Recipes
import com.thesiegs.glint.recipes.Scrubber
import com.thesiegs.glint.recipes.Sensitive
import java.time.ZoneOffset
import java.util.zip.CRC32

/**
 * The shape learner (tile exchange tier 2; recipes research 2 §3.2, SPEC §4.2): a `glint.recipe/1`
 * recipe worked out from one app's **scrubbed** notification shapes, deterministically, with no AI.
 * It reads structure first: the channel ids that carry the app's state (promo, recommendation and
 * one-off channels left out), which built-in fields are reliably there (title, text, progress), and
 * only then the app's own static words, the ones that repeat across samples, as `anchors` tagged with
 * the [lang] they were seen in. Its faces use only built-in fields and existing face kinds: progress
 * when the app shows a bar, a headline from the title and text, a count when more than one is up.
 * Nothing is learned until there are [MIN_SAMPLES] useful samples over [MIN_DAYS] days and the shape
 * holds most of them. Pure JVM: the phone runs it, and so does the tile exchange's CI.
 */
object ShapeLearner {
    /** Useful samples (and chosen ones) needed before anything is learned. */
    const val MIN_SAMPLES = 5
    /** Different days (UTC) the chosen samples must come from: one busy hour isn't a habit. */
    const val MIN_DAYS = 2
    /** The share of the useful samples the chosen channels must hold, and the recipe must draw. */
    const val MIN_COVERAGE = 0.6
    /** A channel needs this many samples, and [MIN_CHANNEL_SHARE] of them, not to be a one-off. */
    const val MIN_CHANNEL_SAMPLES = 2
    const val MIN_CHANNEL_SHARE = 0.15
    /** A field present on this share of the chosen samples is reliable; anchors must hold it too. */
    const val RELIABLE = 0.8
    /** A text line seen on this share is worth a face of its own. */
    const val SOMETIMES = 0.3
    /** The share with a progress bar that earns a progress face. */
    const val PROGRESS_SHARE = 0.5
    /** A word on this share of the chosen samples (and on at least [MIN_ANCHOR_HITS]) is the app's own static word. */
    const val ANCHOR_SHARE = 0.6
    const val MIN_ANCHOR_HITS = 3
    const val MAX_ANCHOR_WORDS = 3
    const val STATUS = "learned"
    const val ID_PREFIX = "learned-"
    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000L

    /** Categories that never carry an app's state: promos, system noise, and what other tiles own (calls, chats, media). */
    val SkipCategories = setOf("promo", "recommendation", "sys", "call", "msg", "alarm", "transport")
    /** Styles other tiles own: conversations (People), media and calls. */
    val SkipTemplates = setOf("MessagingStyle", "MediaStyle", "CallStyle")
    /** Channel ids that say they're marketing. */
    private val PromoChannel = Regex("(?i)promo|marketing|recommend|offer|deal|advert|campaign|newsletter")
    private val AsciiWord = Regex("[A-Za-z]+")
    /** Function words: static, but they say nothing about the app. */
    private val Stop = setOf(
        "the", "and", "for", "you", "your", "yours", "our", "are", "was", "were", "has", "have", "had", "with", "this", "that",
        "from", "will", "been", "its", "not", "but", "can", "all", "any", "now", "just", "here", "there", "they", "them",
        "his", "her", "who", "what", "when", "how", "into", "onto", "about", "more", "than", "then", "also", "please",
        // What the scrubber puts in.
        "example", "https", "com", "www",
    )
    private val Placeholders: Set<String> = Scrubber.People.mapTo(HashSet()) { it.lowercase() }

    /** What the learner made of an app's samples: the [recipe], or null and [why] not. */
    data class Attempt(val recipe: Recipe?, val why: String, val useful: Int = 0, val days: Int = 0)

    /** The recipe [samples] (one app's, scrubbed) teach, or null when they don't teach one yet. */
    fun learn(packageName: String, samples: List<NotificationSample>, label: String? = null, lang: String = "en"): Recipe? =
        attempt(packageName, samples, label, lang).recipe

    fun attempt(packageName: String, samples: List<NotificationSample>, label: String? = null, lang: String = "en"): Attempt {
        if (packageName in Recipes.PeopleApps) return Attempt(null, "a messaging app: its tiles are People tiles")
        if (Sensitive.isSensitive(packageName)) return Attempt(null, "a health or payment app: never learned")
        // Order and keys from the samples themselves, so the same samples always teach the same recipe.
        val all = samples.filter { it.packageName.isEmpty() || it.packageName == packageName }
            .sortedWith(compareBy<NotificationSample>({ it.posted }, { it.channel.orEmpty() }, { it.title.orEmpty() }, { it.text.orEmpty() }))
            .mapIndexed { i, s -> s.copy(key = "s$i", packageName = packageName) }
        val useful = all.filter(::isUseful)
        val usefulDays = days(useful)
        fun no(why: String) = Attempt(null, why, useful.size, usefulDays)
        if (useful.size < MIN_SAMPLES) return no("${useful.size} useful samples, needs $MIN_SAMPLES")
        if (usefulDays < MIN_DAYS) return no("samples from $usefulDays day, needs $MIN_DAYS")

        // The channels that carry the app's state: not marketing, not one-offs.
        val byChannel = useful.groupBy { it.channel }
        val channels = byChannel.filter { (c, list) ->
            c != null && !PromoChannel.containsMatchIn(c) &&
                list.size >= MIN_CHANNEL_SAMPLES && list.size >= MIN_CHANNEL_SHARE * useful.size
        }.keys.filterNotNull().sorted()
        val chosen = if (byChannel.keys.all { it == null }) useful else useful.filter { it.channel in channels }
        if (chosen.size < MIN_SAMPLES || chosen.size < MIN_COVERAGE * useful.size) {
            return no("no channel holds most of its notifications (${chosen.size} of ${useful.size})")
        }
        if (days(chosen) < MIN_DAYS) return no("its main channel's samples come from one day")

        // Without channel ids, the next untranslated signals: a category or a style they all share.
        val noChannels = channels.isEmpty()
        val category = chosen.map { it.category }.distinct().singleOrNull()?.takeIf { noChannels }
        val template = chosen.map { it.template ?: "None" }.distinct().singleOrNull()?.takeIf { noChannels }
        val baseMatch = RecipeMatch(
            channels = channels,
            notCategories = SkipCategories.sorted(),
            categories = listOfNotNull(category),
            templates = listOfNotNull(template),
        )
        val anchors = anchorsOf(chosen)
        val faces = facesOf(chosen)
        val name = (label?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: packageName.substringAfterLast('.').lowercase()).take(Recipes.MAX_LITERAL)
        fun recipe(match: RecipeMatch) = Recipe(
            id = idFor(packageName),
            name = name,
            app = RecipeApp(packageName = packageName, label = label?.trim()?.takeIf { it.isNotEmpty() }),
            meta = RecipeMeta(status = STATUS, author = "Glint", notes = "Learned from ${chosen.size} notification shapes over ${days(chosen)} days."),
            source = RecipeSource(NotificationSource(match = match)),
            faces = faces,
        )
        // The app's static words narrow the match only when they hold nearly every chosen sample.
        val withAnchors = anchors?.let { recipe(baseMatch.copy(anchors = listOf(it), lang = listOf(lang))) }
        val picked = withAnchors?.takeIf { matchShare(it, chosen, lang) >= RELIABLE } ?: recipe(baseMatch)
        val drew = drawShare(picked, useful, lang)
        if (drew < MIN_COVERAGE) return no("the shape draws ${(drew * 100).toInt()}% of its samples")
        val final = picked.copy(rev = revOf(picked))
        val parsed = Recipes.parse(Recipes.write(final))
        if (!parsed.ok) return no("not a valid recipe: ${parsed.errors.joinToString()}")
        return Attempt(final, "learned", useful.size, usefulDays)
    }

    /** `learned-<package>`, in a recipe id's shape (lowercase letters, digits, dashes; at most 64). */
    fun idFor(packageName: String): String =
        (ID_PREFIX + packageName.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString(""))
            .replace(Regex("-+"), "-").trimEnd('-').take(64).trimEnd('-')

    /** A revision from the shape alone: the same shape always gives the same rev, a changed one a new rev. */
    fun revOf(r: Recipe): Int {
        val crc = CRC32()
        crc.update(Recipes.write(r.copy(rev = 1, meta = r.meta.copy(notes = null))).toByteArray(Charsets.UTF_8))
        return (crc.value % 2_000_000_000L).toInt() + 1
    }

    /**
     * One notification as the learner keeps it on the phone: scrubbed (SPEC §10: digits, names, codes,
     * links replaced; extras' values gone), no conversation, and its post time to the hour, which the
     * day rule needs. [label] is the app's name, kept as its own word.
     */
    fun shapeOf(n: NotificationSample, label: String? = null): NotificationSample {
        val s = Scrubber.scrub(listOf(n.copy(messages = emptyList())), keep = setOfNotNull(label)).first()
        val posted = n.posted / HOUR_MS * HOUR_MS
        return s.copy(key = "", posted = posted, whenTime = if (n.whenTime > 0) posted else 0)
    }

    /** Whether [n] can teach the app's state: not a summary, a promo, a chat, a call or media. */
    fun isUseful(n: NotificationSample): Boolean =
        !n.summary && n.category !in SkipCategories && (n.template ?: "None") !in SkipTemplates && n.messages.isEmpty() &&
            !(n.channel?.let { PromoChannel.containsMatchIn(it) } ?: false) &&
            listOf(n.title, n.text, n.bigText).any { !it.isNullOrBlank() }

    private fun days(list: List<NotificationSample>): Int = list.mapTo(HashSet()) { Math.floorDiv(it.posted, DAY_MS) }.size

    /**
     * The app's own static words as one anchor regex, `(?i)\b(?:arriving|delivery)\b`: ASCII words of
     * 3+ letters on [ANCHOR_SHARE] of [chosen], never a scrubber placeholder (Xxxx, Alex, 999) or a
     * function word. Built from those words only, so no text a notification carried once gets in.
     */
    fun anchorsOf(chosen: List<NotificationSample>): String? {
        val counts = HashMap<String, Int>()
        chosen.forEach { n ->
            val words = AsciiWord.findAll(listOfNotNull(n.title, n.text, n.bigText).joinToString(" ")).map { it.value.lowercase() }
                .filterTo(HashSet()) { isStatic(it) }
            words.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val words = counts.filter { (_, c) -> c >= MIN_ANCHOR_HITS && c >= ANCHOR_SHARE * chosen.size }
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(MAX_ANCHOR_WORDS).map { it.key }.sorted()
        if (words.isEmpty()) return null
        return "(?i)\\b(?:" + words.joinToString("|") + ")\\b"
    }

    private fun isStatic(w: String): Boolean =
        w.length >= 3 && w.any { it != 'x' } && w !in Placeholders && w !in Stop

    /** The calm face set: progress (when the app shows a bar), a headline from its title and text, a count past one. */
    private fun facesOf(chosen: List<NotificationSample>): List<FaceRule> {
        fun share(p: (NotificationSample) -> Boolean) = chosen.count(p).toDouble() / chosen.size
        val title = share { !it.title.isNullOrBlank() }
        val text = share { !it.text.isNullOrBlank() && it.text != it.title }
        val progress = share { (it.max ?: 0) > 0 && it.progress != null }
        val head = if (title >= RELIABLE || title >= text) "{title}" else "{text}"
        val withLine = head == "{title}" && text >= SOMETIMES
        return buildList {
            if (progress >= PROGRESS_SHARE) add(FaceRule(kind = FaceKind.Progress, title = head, value = "{progress}", max = "{max}", line = if (withLine) "{text}" else null))
            if (withLine) {
                add(FaceRule(kind = FaceKind.Headline, title = head, line = "{text}", peek = "{time}"))
                add(FaceRule(kind = FaceKind.Headline, title = head, peek = "{time}", whenAll = listOf("!text")))
            } else {
                add(FaceRule(kind = FaceKind.Headline, title = head, peek = "{time}"))
            }
            add(FaceRule(kind = FaceKind.Count, count = "{count}", label = "new", whenAll = listOf("count>1")))
        }
    }

    private fun ctx(n: NotificationSample, lang: String) = RecipeContext(now = n.posted + 60_000L, language = lang, zone = ZoneOffset.UTC)

    private fun matchShare(r: Recipe, list: List<NotificationSample>, lang: String): Double =
        list.count { RecipeEngine.matches(r, it, ctx(it, lang)) }.toDouble() / list.size

    private fun drawShare(r: Recipe, list: List<NotificationSample>, lang: String): Double =
        list.count { RecipeEngine.evaluate(r, listOf(it), ctx(it, lang)).drew }.toDouble() / list.size
}
