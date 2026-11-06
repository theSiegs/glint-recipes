package com.thesiegs.glint.recipes.exchange.submit

import com.thesiegs.glint.recipes.Fixture
import com.thesiegs.glint.recipes.NotificationSample
import com.thesiegs.glint.recipes.SampleAction
import com.thesiegs.glint.recipes.Scrubber
import com.thesiegs.glint.recipes.exchange.Exchange
import com.thesiegs.glint.recipes.learn.ShapeLearner
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One submission to the tile exchange's relay (`glint.submit/1`, contract on [SubmitClient]): an
 * app's **scrubbed** notification shapes, the ones the on-phone learner keeps
 * (files/recipes/learn/<package>.json), and nothing else: the package, the app's version code, the
 * catalog [rev] it answers (0: the catalog has no live tile for it), the broken recipe it replaces
 * ([against], "id@rev"), the phone's language (anchors are per language) and the app's label (the
 * learner names the recipe and the tile's caption after it, and keeps its words as the app's own).
 * Never content, never a device id. [token] is the relay's one-use challenge and [attestation] the
 * hardware key's certificate chain (base64 DER, leaf first); both are empty in the preview.
 */
@Serializable
data class Submission(
    val format: String,
    @SerialName("package") val packageName: String,
    val versionCode: Long? = null,
    val rev: Int = 0,
    val against: String? = null,
    val lang: String? = null,
    val label: String? = null,
    val shapes: List<NotificationSample>,
    val token: String,
    val attestation: List<String>,
)

/** Builds a [Submission] from the learner's kept shapes, and refuses one that doesn't look scrubbed. */
object SubmitPayload {
    const val FORMAT = "glint.submit/1"
    /** At most this many shapes go (the learner keeps 30 per app). */
    const val MAX_SHAPES = 30
    const val MAX_LABEL = 64
    private const val DAY_MS = 86_400_000L
    /** Shapes span at most this long once rebased: the learner keeps a few weeks at most. */
    private const val MAX_SPAN_MS = 120 * DAY_MS
    private val PackageShape = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    /** What the scrubber writes in place of an email, a link and a phone number; their digits are allowed. */
    private val Placeholders = listOf("alex@example.com", "https://example.com", "555-0100")
    private val RealDigit = Regex("[0-8]")

    /**
     * The submission for [fixture] (the learner's file for one app), or null when it isn't one that may
     * go: no package, too few shapes, or [problems] found anything. Only structure is kept, scrubbed
     * once more here (files written before the scrubber's word list are scrubbed now): every text
     * through [Scrubber.scrubText] with only common words and the app's own left, the channel id too
     * (the learner matches on it, so it goes, scrubbed the same way); no group, tag or extras (the
     * learner reads none of them, and an account or an order hides there); no keys, no conversations;
     * action semantics without their words; a progress bar as a share in tenths of 100 ([share]),
     * never its raw numbers; times rebased to [Scrubber.BASE_TIME] (spacing kept, to the hour as the
     * learner keeps them).
     */
    fun build(fixture: Fixture, versionCode: Long?, rev: Int = 0, against: String? = null, lang: String? = null): Submission? {
        val pkg = fixture.packageName
        if (!PackageShape.matches(pkg)) return null
        val kept = fixture.samples.map { it.notification }.sortedBy { it.posted }.takeLast(MAX_SHAPES)
        if (kept.size < ShapeLearner.MIN_SAMPLES) return null
        val first = kept.mapNotNull { it.posted.takeIf { p -> p > 0 } }.minOrNull() ?: 0L
        fun time(t: Long) = if (t <= 0 || first == 0L) 0L else Scrubber.BASE_TIME + (t - first).coerceAtLeast(0)
        val label = fixture.label?.trim()?.take(MAX_LABEL)?.takeIf { it.isNotEmpty() }
        val keep = Scrubber.appWords(pkg, label)
        fun clean(v: String?) = v?.let { Scrubber.scrubText(it, keep = keep) }
        val shapes = kept.map { n ->
            val (progress, max) = share(n.progress, n.max)
            n.copy(
                key = "", packageName = "", messages = emptyList(),
                posted = time(n.posted), whenTime = time(n.whenTime),
                channel = clean(n.channel), group = null, tag = null,
                title = clean(n.title), text = clean(n.text), bigText = clean(n.bigText), subText = clean(n.subText),
                infoText = clean(n.infoText), summaryText = clean(n.summaryText),
                conversationTitle = clean(n.conversationTitle), shortCritical = clean(n.shortCritical),
                lines = n.lines.map { Scrubber.scrubText(it, keep = keep) },
                progress = progress, max = max,
                actions = n.actions.mapNotNull { a -> a.semantic?.let { SampleAction(semantic = it) } },
                extras = emptyMap(),
            )
        }
        val s = Submission(
            format = FORMAT, packageName = pkg, versionCode = versionCode, rev = rev, against = against,
            lang = lang?.takeIf { it.matches(Regex("^[a-z]{2,3}$")) },
            label = label,
            shapes = shapes, token = "", attestation = emptyList(),
        )
        return s.takeIf { problems(it).isEmpty() }
    }

    /**
     * A progress bar as it leaves the phone: its share of [max] in tenths, out of 100 (a download's
     * bytes or a timer's seconds never go). The learner only asks whether there is a bar, and a
     * learned progress face draws the share. No bar (no max): null, null.
     */
    fun share(progress: Int?, max: Int?): Pair<Int?, Int?> {
        if (progress == null || max == null || max <= 0) return null to null
        return Math.round(progress.coerceIn(0, max) * 10.0 / max).toInt() * 10 to 100
    }

    /**
     * Why [s] must not leave the phone (empty: it may): a chat, health or payment app, a shape with a
     * key, a conversation, a group, tag or extra, an action's words, a raw progress, a string the
     * scrubber would have changed (an email, link or phone number that isn't its example, a digit
     * other than 9, a word that isn't common or the app's own), or a time that isn't rebased. The second line after the scrubber: a bug there stops here.
     */
    fun problems(s: Submission): List<String> = buildList {
        if (s.format != FORMAT) add("format ${s.format}")
        if (!PackageShape.matches(s.packageName)) add("package")
        if (!Exchange.offerable(s.packageName)) add("a chat, health or payment app")
        if (s.shapes.isEmpty() || s.shapes.size > MAX_SHAPES) add("${s.shapes.size} shapes")
        s.label?.let { l -> Scrubber.leaks(l).forEach { add("label: ${it.substringBefore(' ')}") } }
        val keep = Scrubber.appWords(s.packageName, s.label)
        s.shapes.forEachIndexed { i, n ->
            fun bad(why: String) = add("shape $i: $why")
            if (n.key.isNotEmpty()) bad("a key")
            if (n.packageName.isNotEmpty()) bad("a package")
            if (n.messages.isNotEmpty()) bad("a conversation")
            if (n.group != null || n.tag != null) bad("a group or tag")
            if (n.extras.isNotEmpty()) bad("an extra")
            val p = n.progress
            if ((p != null || n.max != null) && !(n.max == 100 && p != null && p in 0..100 && p % 10 == 0)) bad("a raw progress")
            if (n.actions.any { it.title.isNotEmpty() }) bad("an action's words")
            listOf(n.posted, n.whenTime).filter { it != 0L }.forEach { t ->
                if (t < Scrubber.BASE_TIME || t > Scrubber.BASE_TIME + MAX_SPAN_MS) bad("a time not rebased")
            }
            val scrubbed = listOfNotNull(
                n.channel, n.title, n.text, n.bigText, n.subText, n.infoText, n.summaryText, n.conversationTitle, n.shortCritical,
            ) + n.lines
            scrubbed.forEach { v ->
                Scrubber.leaks(v).forEach { bad(it.substringBefore(' ')) }
                if (Scrubber.unknownWords(v, keep).isNotEmpty()) bad("a word not scrubbed")
                var rest = v
                Placeholders.forEach { rest = rest.replace(it, "") }
                if (RealDigit.containsMatchIn(rest)) bad("a digit not scrubbed")
            }
        }
    }
}

/**
 * Which apps may be submitted (owner 2026-11-02: "apps with no recipe or a broken one, never health,
 * payment or chat apps") and the rate rules. Pure, so tested.
 */
object SubmitRules {
    /** Submissions per day (UTC), over all apps; each costs two small requests. */
    const val DAILY_CAP = 3
    /** The longest pause a relay's 429 can ask for. */
    const val MAX_PAUSE_S = 7 * 86_400L

    /**
     * Whether [packageName]'s kept [shapes] may go: not a chat, health or payment app; no live tile of
     * its own except one learned on this phone ([hasRecipe] counts bundled, imported and catalog
     * ones, and the index covering it), or that one [broken]; and enough shapes over enough days for
     * the learner to work with.
     */
    fun eligible(packageName: String, shapes: List<NotificationSample>, hasRecipe: Boolean, broken: Boolean): Boolean {
        if (!Exchange.offerable(packageName)) return false
        if (hasRecipe && !broken) return false
        val useful = shapes.filter(ShapeLearner::isUseful)
        return useful.size >= ShapeLearner.MIN_SAMPLES &&
            useful.mapTo(HashSet()) { Math.floorDiv(it.posted, 86_400_000L) }.size >= ShapeLearner.MIN_DAYS
    }
}

/**
 * What the phone remembers about its submissions: the rev each app was sent at ([sent]: one per app
 * per rev, ever), the day's tries ([today] on UTC day [day], capped at [SubmitRules.DAILY_CAP]; a try
 * counts whether or not it got through, so nothing retries in a loop), and a relay's 429 pause.
 */
@Serializable
data class SubmitLedger(
    val sent: Map<String, Int> = emptyMap(),
    val day: Long = -1,
    val today: Int = 0,
    val pausedUntil: Long = 0,
) {
    private fun dayOf(now: Long) = Math.floorDiv(now, 86_400_000L)
    fun triesToday(now: Long): Int = if (dayOf(now) == day) today else 0
    fun left(now: Long): Int = if (now < pausedUntil) 0 else (SubmitRules.DAILY_CAP - triesToday(now)).coerceAtLeast(0)

    /** Whether [packageName] at [rev] may be tried now. */
    fun allows(packageName: String, rev: Int, now: Long): Boolean = sent[packageName] != rev && left(now) > 0

    /** One try spent. */
    fun tried(now: Long): SubmitLedger = copy(day = dayOf(now), today = triesToday(now) + 1)

    /** [packageName] went (or the relay already had it) at [rev]; at most [MAX_APPS] remembered, oldest first out. */
    fun sent(packageName: String, rev: Int): SubmitLedger {
        val next = LinkedHashMap(sent); next.remove(packageName); next[packageName] = rev
        while (next.size > MAX_APPS) next.remove(next.keys.first())
        return copy(sent = next)
    }

    /** The relay said 429: nothing goes for [seconds] (at least a minute, at most a week). */
    fun paused(now: Long, seconds: Long): SubmitLedger =
        copy(pausedUntil = now + seconds.coerceIn(60L, SubmitRules.MAX_PAUSE_S) * 1000L)

    fun encode(): String = SubmitCodec.json.encodeToString(serializer(), this)

    companion object {
        const val MAX_APPS = 200
        fun decode(text: String?): SubmitLedger =
            text?.let { runCatching { SubmitCodec.json.decodeFromString(serializer(), it) }.getOrNull() } ?: SubmitLedger()
    }
}

/** The relay's JSON, both ways (contract on [SubmitClient]). */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
object SubmitCodec {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** `POST <relay>challenge` body. */
    @Serializable
    data class ChallengeRequest(val format: String, @SerialName("package") val packageName: String, val rev: Int)

    /** Its 200 answer: a one-use [token] (also the attestation challenge), good until [expires] (epoch seconds). */
    @Serializable
    data class ChallengeResponse(val token: String = "", val expires: Long = 0)

    /** `POST <relay>submit`'s 202 answer. */
    @Serializable
    data class SubmitResponse(val id: String = "")

    /** A refusal's body (4xx), and a 429's [retryAfter] seconds. */
    @Serializable
    data class ErrorResponse(val error: String = "", val retryAfter: Long = 0)

    /** The exact bytes sent as `POST <relay>submit`'s body (and shown in the preview). */
    fun encode(s: Submission): String = json.encodeToString(Submission.serializer(), s)
    fun decodeSubmission(text: String): Submission = json.decodeFromString(Submission.serializer(), text)
    fun challenge(packageName: String, rev: Int): String =
        json.encodeToString(ChallengeRequest.serializer(), ChallengeRequest(SubmitPayload.FORMAT, packageName, rev))
    fun decodeChallengeRequest(text: String): ChallengeRequest = json.decodeFromString(ChallengeRequest.serializer(), text)

    /** A usable token: 16..128 URL-safe base64 characters. */
    fun token(text: String): String? = runCatching { json.decodeFromString(ChallengeResponse.serializer(), text) }.getOrNull()
        ?.token?.takeIf { it.matches(Regex("^[A-Za-z0-9_-]{16,128}$")) }

    fun accepted(text: String): String? = runCatching { json.decodeFromString(SubmitResponse.serializer(), text) }.getOrNull()?.id?.take(64)
    fun error(text: String): ErrorResponse = runCatching { json.decodeFromString(ErrorResponse.serializer(), text) }.getOrNull() ?: ErrorResponse()
}
