package com.thesiegs.glint.recipes

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * A tile recipe, format `glint.recipe/1` (recipes/SPEC.md): data that turns one app's notifications
 * into calm Glint faces. Never code: a closed vocabulary, no URLs, no intents, nothing that sends data
 * anywhere. The parser is tolerant: unknown keys are ignored (a newer recipe still loads), and
 * anything it can't use becomes a [Problem] instead of an exception.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Recipe(
    /** The format id, "glint.recipe/1" ([Recipes.FORMAT_ID]). A newer major still reads, with a warning. */
    val recipe: String = Recipes.FORMAT_ID,
    /** Stable id, lowercase words and dashes ("keep-reminder"); also the file name. */
    val id: String = "",
    /** The recipe's own revision, bumped on each change; the store keeps the highest. */
    val rev: Int = 1,
    /** The tile's caption word, lowercase ("keep"). */
    val name: String = "",
    val app: RecipeApp = RecipeApp(),
    val meta: RecipeMeta = RecipeMeta(),
    /** Off-by-default recipes (counts from noisy apps) are offered in Add a tile but never picked on their own. */
    val enabled: Boolean = true,
    val source: RecipeSource = RecipeSource(),
    /** Named values pulled out of the notifications ([FieldRule]); the built-in fields come free. */
    val fields: Map<String, FieldRule> = emptyMap(),
    /**
     * 1..[Recipes.MAX_FACES] faces, a ladder: the first that draws is the rest face and the rest of
     * the ladder stays down (one current state). [FaceRule.flip] faces draw besides it, and each other
     * subject up adds its own rest face (recipes/SPEC.md §6).
     */
    val faces: List<FaceRule> = emptyList(),
    /** What a tap on the tile does: [Tap.Notification] (its own contentIntent, else the app) or [Tap.App]. */
    val tap: Tap = Tap.Notification,
    /** Days the app may post without this recipe matching before it's flagged "needs update". */
    @JsonNames("stale_days") val staleDays: Int = Recipes.STALE_DAYS,
    /**
     * The card the app's tile wears at rest ([RecipeCard], recipes/SPEC.md §4.3), or none. A recipe
     * may be a card alone, with no source and no faces.
     */
    val card: RecipeCard? = null,
) {
    val notification: NotificationSource get() = source.notification ?: NotificationSource()
    val isGeneric: Boolean get() = id == Recipes.GENERIC_ID
    /** Whether it reads the app's notifications into faces (a live tile); false for a card alone. */
    val readsNotifications: Boolean get() = source.notification != null && faces.isNotEmpty()
}

/**
 * The card an app's tile wears at rest (recipes/SPEC.md §4.3): a loyalty, gift or membership card,
 * drawn by Glint, never artwork from anywhere. Data only, a closed set: [look] "plain" (the app's
 * color, or [color], with its glyph and name), [tap] which of the app's own shortcuts a tap opens,
 * picked by Glint from the shortcuts' labels (no ids, URLs or intents), and [color] "#rrggbb".
 */
@Serializable
data class RecipeCard(
    val look: String = "plain",
    val tap: CardTap? = null,
    val color: String? = null,
) {
    /** The shortcut a tap opens ("pay"), or "app" for the app itself. */
    val shortcut: String get() = tap?.shortcut ?: Recipes.CARD_APP

    /** [color] as 0xRRGGBB, or null for the app icon's own color. */
    val rgb: Int? get() = color?.takeIf { Recipes.CardColor.matches(it) }?.drop(1)?.toInt(16)
}

/** What a tap on a card opens: one of [Recipes.CardShortcuts]. */
@Serializable
data class CardTap(val shortcut: String = Recipes.CARD_APP)

@Serializable
data class RecipeApp(
    /** The source app's package, or "*" for the generic recipe only. */
    @SerialName("package") val packageName: String = "",
    /** The app's name as people know it ("Google Keep"), for the import preview. */
    val label: String? = null,
    /**
     * The app's `versionCode` range the recipe was written for: ">=1240000", "<2000", "1200-1300".
     * Outside it the recipe runs on probation: it still draws, and tile info says "app updated, checking".
     */
    val versions: String? = null,
)

@Serializable
data class RecipeMeta(
    /**
     * "unverified" (written from public knowledge), "built-in" (Glint ships it on for everyone, still
     * to be checked on real samples), "verified" (checked on real samples), "draft", "learned" (worked
     * out on the phone from the app's own notification shapes, [com.thesiegs.glint.recipes.learn.ShapeLearner]).
     */
    val status: String = "unverified",
    /** Who or what wrote it ("Glint", "an agent"), never a person's name. */
    val author: String? = null,
    /** When it was last changed, yyyy-mm-dd. */
    val updated: String? = null,
    val notes: String? = null,
)

/** Where a recipe reads from. v0 has one source, the app's notifications; later ones join here. */
@Serializable
data class RecipeSource(val notification: NotificationSource? = null)

/**
 * The notification source: which of the app's notifications it reads ([match]), what makes two of
 * them the same subject ([key]: an order, a flight), which one wins ([pick]), and how long values
 * live ([hold], [expire]).
 */
@Serializable
data class NotificationSource(
    val match: RecipeMatch = RecipeMatch(),
    /**
     * What one subject is: "tag", "group", "id" (each notification its own), or "field:<name>" (a
     * field's value, e.g. a flight number). Values are held per subject; `{keys}` counts the subjects
     * up; each subject up is one item, and the tile flips between items.
     */
    val key: String = "id",
    val pick: Pick = Pick.Newest,
    /** Fill a field the winner lacks from the same subject's other notifications, newest first. */
    val merge: Boolean = false,
    /** How long a field's last value is kept once its notification is gone ("18h"); none by default. */
    val hold: String? = null,
    /** A notification older than this no longer counts ("6h"); none by default. */
    val expire: String? = null,
    /** Faces from a notification older than this draw dimmed ("4h"): still true, but not news. */
    val fade: String? = null,
)

/**
 * Which notifications a recipe reads, untranslated signals first (recipes/SPEC.md §4): channel id
 * (never the channel's name), category, template, flags, numbers, MessagingStyle and action
 * semantics; text regexes last, tagged with the [lang] they're written for. Every list is "any of";
 * an empty one doesn't filter. Channel, category and group entries match exactly, or as a prefix when
 * they end in "*".
 */
@Serializable
data class RecipeMatch(
    val channels: List<String> = emptyList(),
    @JsonNames("not_channels") val notChannels: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    @JsonNames("not_category", "notCategory") val notCategories: List<String> = emptyList(),
    /** Short style names: "BigTextStyle", "InboxStyle", "ProgressStyle", "None" for none. */
    val templates: List<String> = emptyList(),
    val groups: List<String> = emptyList(),
    /** Group summaries are left out unless this is true. */
    val summaries: Boolean = false,
    /** "any" (default), "only" or "never": ongoing (can't be swiped away) notifications. */
    val ongoing: String = "any",
    /** "any" (default), "only" or "never": a progress bar. */
    val progress: String = "any",
    /**
     * "never" (default), "any" or "only": MessagingStyle. Conversations belong to People tiles, so a
     * recipe leaves them alone unless it says otherwise.
     */
    val messaging: String = "never",
    /** Action semantics any of which it must carry ("reply", "markAsRead", "archive", "delete", "call"). */
    val actions: List<String> = emptyList(),
    /** Regexes, any found in the title, text or big text. Skipped in a language not in [lang]. */
    val anchors: List<String> = emptyList(),
    /** Regexes on the title alone. Skipped like [anchors]. */
    val titles: List<String> = emptyList(),
    /** Regexes that drop a notification (a promo's words). Skipped like [anchors]. */
    val exclude: List<String> = emptyList(),
    /** The languages the text regexes are written for ("en"); empty is any. */
    @Serializable(with = StringListSerializer::class) val lang: List<String> = emptyList(),
)

/**
 * One field: where it comes from ([from], a source name or a list tried in order), then an optional
 * cut ([after], [regex]), then [then] transforms, then [map]. A field with no value is
 * missing, and a face that needs it doesn't draw. A [regex] or [after] field names the
 * [lang] it's written for; in another language it's "not applicable", not broken. A [required]
 * field missing on 3 matched notifications running makes the recipe unwell.
 */
@Serializable
data class FieldRule(
    @Serializable(with = StringListSerializer::class) val from: List<String> = listOf("text"),
    /** The text after the first place this literal anchor occurs (case-insensitive), up to a break. */
    val after: String? = null,
    /** A regex (RE2): the named group `v`, else group 1, else the whole match. */
    val regex: String? = null,
    @Serializable(with = StringListSerializer::class) val lang: List<String> = emptyList(),
    @Serializable(with = StringListSerializer::class) val then: List<String> = emptyList(),
    /** Whole-value replacements after the transforms, compared case-insensitively. */
    val map: Map<String, String> = emptyMap(),
    val default: String? = null,
    val required: Boolean = false,
    /** The value is dropped once its notification is older than this ("3h"). */
    val stale: String? = null,
    /** This field's own hold, over the source's. */
    val hold: String? = null,
)

/**
 * One face. [kind] picks Glint's own face for it ([FaceKind]); the texts are templates where
 * `{field}` is a field's value, `{field|in}` the same read as a time and said relative to now
 * ([Times]), and everything else is the recipe's own words. A face draws only when every field it
 * names has a value and each [whenAll] condition holds. Faces are a ladder: only the first that draws
 * shows, unless [flip] (a different thing worth flipping to: a count, the latest subject). An [optIn]
 * face (amounts, names, health) draws only once the owner turns it on in tile settings.
 */
@Serializable
data class FaceRule(
    val kind: FaceKind = FaceKind.Headline,
    val title: String? = null,
    val line: String? = null,
    val peek: String? = null,
    /** Count faces: the number ("{count}"); a non-number doesn't draw. */
    val count: String? = null,
    /** Count faces: the word under the number ("new", "unread"). */
    val label: String? = null,
    /** Message faces: who ("{sender}"). */
    val person: String? = null,
    /** Progress faces: how far ("{progress}") out of [max] ("{max}"), or a "{percent}". */
    val value: String? = null,
    val max: String? = null,
    @SerialName("when") @Serializable(with = StringListSerializer::class) val whenAll: List<String> = emptyList(),
    @JsonNames("opt_in") val optIn: Boolean = false,
    /** Not a rung of the ladder: drawn besides the rest face, as a flip (a count, the latest subject). */
    val flip: Boolean = false,
    /**
     * The notification's own picture as the face's whole background, from Large up (calm tiles: the
     * whole tile or nothing): "picture" (BigPicture: a doorbell snapshot, a video's thumbnail) or
     * "largeIcon" (a cover, a product photo; only when it's big enough). Without one the face draws plain.
     */
    val picture: String? = null,
)

/** Glint's face kinds a recipe may map to (recipes/SPEC.md §6). An unknown kind is an error. */
@Serializable
enum class FaceKind {
    /** A big line and a smaller one: what happened ("gate b12", "boards 1:05 pm"). */
    @SerialName("headline") Headline,
    /** A big number and its word ("3", "unread"). */
    @SerialName("count") Count,
    /** Who, large, and what they said. */
    @SerialName("message") Message,
    /** A headline over a track: a delivery, a download, a flight under way. */
    @SerialName("progress") Progress,
}

@Serializable
enum class Pick {
    @SerialName("newest") Newest,
    /** Ongoing ones first, then the newest. */
    @SerialName("ongoing") Ongoing,
}

@Serializable
enum class Tap {
    /** The winning notification's own contentIntent, else the app. */
    @SerialName("notification") Notification,
    /** The app's launcher activity. */
    @SerialName("app") App,
}

/** Something wrong with a recipe: an [error] stops it loading, a warning doesn't. */
data class Problem(val where: String, val message: String, val error: Boolean = true) {
    override fun toString(): String = (if (error) "error" else "warning") + " at $where: $message"
}

/** A parsed recipe and what was wrong with it; [recipe] is null when it couldn't be read at all. */
data class Parsed(val recipe: Recipe?, val problems: List<Problem>) {
    val ok: Boolean get() = recipe != null && problems.none { it.error }
    val errors: List<Problem> get() = problems.filter { it.error }
}

object Recipes {
    /** The format this build reads. */
    const val FORMAT_ID = "glint.recipe/1"
    const val FORMAT_MAJOR = 1
    const val MAX_FACES = 6
    const val MAX_FIELDS = 32
    /** The recipe's own words in a template, per template. */
    const val MAX_LITERAL = 40
    const val MAX_REGEX = 300
    /** Text longer than this is cut before any regex runs. */
    const val MAX_INPUT = 2_000
    const val STALE_DAYS = 7
    /** At most this many subjects (orders, flights) draw a face of their own; the tile flips between them. */
    const val MAX_ITEMS = 3
    /** A required field missing on this many matched notifications running: the recipe is unwell. */
    const val UNWELL_AFTER = 3
    /** The generic recipe's id: any app with notifications, when it has no recipe of its own. */
    const val GENERIC_ID = "generic"

    /** A card's looks ([RecipeCard.look]). */
    val CardLooks = setOf("plain")
    /** The app shortcuts a card's tap may open ([CardTap.shortcut]), found by their labels; "app" is the app itself. */
    val CardShortcuts = listOf("pay", "scan", "order", "reload", "app")
    const val CARD_APP = "app"
    internal val CardColor = Regex("^#[0-9A-Fa-f]{6}$")

    /** The [RecipeMeta.status] values a recipe may have. */
    val Statuses = setOf("unverified", "built-in", "verified", "draft", "learned")
    const val ANY_APP = "*"

    /**
     * Apps whose notifications are people's messages: they stay on the People path (People tiles,
     * Arrivals), never a recipe (recipes research 2 §4).
     */
    val PeopleApps = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "org.thoughtcrime.securesms", "org.telegram.messenger", "com.facebook.orca", "com.discord",
        "com.google.android.apps.googlevoice", "com.Slack", "com.microsoft.teams", "com.snapchat.android",
    )

    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        allowTrailingComma = true
        allowComments = true
        explicitNulls = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    private val IdShape = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
    private val FieldShape = Regex("^[A-Za-z][A-Za-z0-9_]{0,31}$")
    private val PackageShape = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val FormatShape = Regex("^glint\\.recipe/(\\d+)$")
    // The closing brace escaped too: Android's ICU regex rejects a bare "}" (the JVM's accepts it).
    // "{eta|in}": a field, then optionally a time format (Times).
    internal val Placeholder = Regex("\\{([A-Za-z][A-Za-z0-9_]*)(?:\\|([A-Za-z]+))?\\}")

    /** Reads [text] as a recipe, checking it against the schema; never throws. */
    fun parse(text: String): Parsed {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return Parsed(null, listOf(Problem("file", "not JSON: ${e.message?.lineSequence()?.firstOrNull()}")))
        }
        return parse(element)
    }

    fun parse(element: JsonElement): Parsed {
        val obj = element as? JsonObject ?: return Parsed(null, listOf(Problem("file", "a recipe is a JSON object")))
        val problems = mutableListOf<Problem>()
        val format = (obj["recipe"] as? JsonPrimitive)?.contentOrNull
        val major = format?.let { FormatShape.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
        when {
            format == null -> problems += Problem("recipe", "missing \"recipe\": \"$FORMAT_ID\" (the format id)")
            major == null -> problems += Problem("recipe", "the format id is \"$FORMAT_ID\"")
            major > FORMAT_MAJOR -> problems += Problem("recipe", "$format is newer than this build reads ($FORMAT_ID); unknown parts are ignored", error = false)
        }
        val recipe = try {
            json.decodeFromJsonElement<Recipe>(obj)
        } catch (e: Exception) {
            return Parsed(null, problems + Problem("file", e.message?.lineSequence()?.firstOrNull() ?: "unreadable"))
        }
        problems += validate(recipe)
        // An unknown face kind is an error, not the default kind it would be read as.
        (obj["faces"] as? JsonArray)?.forEachIndexed { i, f ->
            val kind = ((f as? JsonObject)?.get("kind") as? JsonPrimitive)?.contentOrNull ?: return@forEachIndexed
            if (kind !in FaceKinds) problems += Problem("faces[$i].kind", "unknown face kind \"$kind\" (${FaceKinds.joinToString()})")
        }
        // Unknown top-level keys: tolerated, but said, so a typo ("feilds") doesn't pass in silence.
        obj.keys.filter { it !in KnownKeys }.forEach { problems += Problem(it, "unknown key, ignored", error = false) }
        return Parsed(recipe, problems)
    }

    private val FaceKinds = listOf("headline", "count", "message", "progress")

    /** What a face's `picture` may be ([FaceRule.picture]). */
    val Pictures = setOf("picture", "largeIcon")

    /**
     * Whether a tile drawing [r] must be looked at again as time passes (holds, expiries, fading,
     * stale values, relative times): only those tiles take the shared minute tick.
     */
    fun needsClock(r: Recipe): Boolean {
        val src = r.notification
        if (src.hold != null || src.expire != null || src.fade != null) return true
        if (r.fields.values.any { it.hold != null || it.stale != null }) return true
        return r.faces.any { f ->
            listOfNotNull(f.title, f.line, f.peek, f.count, f.label, f.person, f.value, f.max).any { t ->
                Placeholder.findAll(t).any { it.groupValues[2].isNotEmpty() || it.groupValues[1] in Fields.Ticking }
            }
        }
    }

    private val KnownKeys = setOf(
        "recipe", "id", "rev", "name", "app", "meta", "enabled", "source", "fields", "faces", "tap", "staleDays", "stale_days", "card",
        "\$schema", "comment",
    )

    /** The rules [parse] checks once the JSON has its shape. */
    fun validate(r: Recipe): List<Problem> = buildList {
        if (!IdShape.matches(r.id)) add(Problem("id", "use lowercase letters, digits and dashes (\"gmail-new\")"))
        if (r.rev < 1) add(Problem("rev", "a revision is 1 or more"))
        if (r.name.isBlank()) add(Problem("name", "give the tile a caption word (\"gmail\")"))
        if (r.name.length > MAX_LITERAL) add(Problem("name", "at most $MAX_LITERAL characters"))
        val pkg = r.app.packageName
        if (pkg == ANY_APP) {
            if (r.id != GENERIC_ID) add(Problem("app.package", "\"*\" is only for the generic recipe"))
        } else if (!PackageShape.matches(pkg)) add(Problem("app.package", "an Android package name (\"com.google.android.gm\")"))
        if (pkg in PeopleApps) add(Problem("app.package", "$pkg is a messaging app: its conversations stay on People tiles, never a recipe"))
        r.app.versions?.let { if (Versions.parse(it) == null) add(Problem("app.versions", "a versionCode range: \">=1240000\", \"<2000\" or \"1200-1300\"")) }
        // A recipe reads notifications into faces, or is a card alone, or both (§4.3).
        val reads = r.source.notification != null
        if (!reads && r.card == null) add(Problem("source", "v0 reads notifications: \"source\": { \"notification\": { ... } } (or the recipe is a \"card\" alone)"))
        if (!reads && r.card != null && r.faces.isNotEmpty()) add(Problem("source", "faces read notifications: add \"source\": { \"notification\": { ... } }, or leave the faces out for a card alone"))
        if (r.faces.isEmpty() && (reads || r.card == null)) add(Problem("faces", "at least one face"))
        r.card?.let { c ->
            if (pkg == ANY_APP) add(Problem("card", "a card is for one app, never the generic recipe"))
            if (c.look !in CardLooks) add(Problem("card.look", "\"${c.look}\" isn't a card look (${CardLooks.joinToString()})"))
            c.tap?.let { if (it.shortcut !in CardShortcuts) add(Problem("card.tap.shortcut", "\"${it.shortcut}\" isn't a card tap (${CardShortcuts.joinToString()})")) }
            c.color?.let { if (!CardColor.matches(it)) add(Problem("card.color", "a hex color, \"#00704a\"")) }
        }
        if (r.faces.size > MAX_FACES) add(Problem("faces", "at most $MAX_FACES faces"))
        if (r.faces.isNotEmpty() && r.faces.all { it.optIn }) add(Problem("faces", "at least one face that isn't optIn, so the tile has a rest face"))
        if (r.fields.size > MAX_FIELDS) add(Problem("fields", "at most $MAX_FIELDS fields"))
        val src = r.notification
        val m = src.match
        val tri = setOf("any", "only", "never")
        if (m.ongoing !in tri) add(Problem("source.notification.match.ongoing", "\"any\", \"only\" or \"never\""))
        if (m.progress !in tri) add(Problem("source.notification.match.progress", "\"any\", \"only\" or \"never\""))
        if (m.messaging !in tri) add(Problem("source.notification.match.messaging", "\"any\", \"only\" or \"never\""))
        m.actions.forEach { if (it !in Semantics.Names) add(Problem("source.notification.match.actions", "unknown semantic \"$it\" (${Semantics.Names.joinToString()})")) }
        val textRegexes = m.anchors.map { "anchors" to it } + m.titles.map { "titles" to it } + m.exclude.map { "exclude" to it }
        textRegexes.forEach { (k, a) -> regexProblem(a)?.let { add(Problem("source.notification.match.$k", it)) } }
        if (textRegexes.isNotEmpty() && m.lang.isEmpty()) add(Problem("source.notification.match.lang", "text regexes name the language they're written for (\"lang\": [\"en\"])", error = false))
        if (reads && m.channels.isEmpty() && m.categories.isEmpty() && m.templates.isEmpty() && m.groups.isEmpty() && !r.isGeneric) {
            add(Problem("source.notification.match", "no untranslated signal (channels, categories, templates, groups): the recipe leans on wording alone", error = false))
        }
        if (!Keys.isKnown(src.key, r.fields.keys)) add(Problem("source.notification.key", "\"tag\", \"group\", \"id\" or \"field:<a field>\""))
        listOfNotNull(
            src.hold?.let { "source.notification.hold" to it }, src.expire?.let { "source.notification.expire" to it },
            src.fade?.let { "source.notification.fade" to it },
        ).forEach { (k, v) ->
            if (Durations.parse(v) == null) add(Problem(k, "a duration like \"90m\", \"18h\" or \"2d\""))
        }
        if (r.staleDays !in 1..90) add(Problem("staleDays", "1 to 90 days"))
        val known = Fields.BuiltIn + r.fields.keys
        r.fields.forEach { (name, rule) ->
            val at = "fields.$name"
            if (!FieldShape.matches(name)) add(Problem(at, "field names are letters, digits and _"))
            if (name in Fields.BuiltIn) add(Problem(at, "\"$name\" is a built-in field; this one replaces it", error = false))
            if (rule.from.isEmpty()) add(Problem("$at.from", "name at least one source"))
            rule.from.forEach { s -> if (!Sources.isKnown(s)) add(Problem("$at.from", "unknown source \"$s\" (${Sources.Names.joinToString()})")) }
            rule.regex?.let { regexProblem(it)?.let { p -> add(Problem("$at.regex", p)) } }
            if ((rule.regex != null || rule.after != null) && rule.lang.isEmpty()) {
                add(Problem("$at.lang", "a regex or anchor names the language it's written for (\"lang\": [\"en\"])", error = false))
            }
            rule.then.forEach { t -> if (!Transforms.isKnown(t)) add(Problem("$at.then", "unknown transform \"$t\" (${Transforms.Names.joinToString()})")) }
            rule.default?.let { if (it.length > MAX_LITERAL) add(Problem("$at.default", "at most $MAX_LITERAL characters")) }
            rule.map.values.forEach { if (it.length > MAX_LITERAL) add(Problem("$at.map", "each value at most $MAX_LITERAL characters")) }
            listOfNotNull(rule.stale?.let { "stale" to it }, rule.hold?.let { "hold" to it }).forEach { (k, v) ->
                if (Durations.parse(v) == null) add(Problem("$at.$k", "a duration like \"90m\", \"18h\" or \"2d\""))
            }
        }
        r.faces.forEachIndexed { i, f ->
            val at = "faces[$i]"
            fun template(name: String, t: String?) {
                if (t == null) return
                Placeholder.findAll(t).forEach { m ->
                    val field = m.groupValues[1]
                    val format = m.groupValues[2]
                    if (field !in known) add(Problem("$at.$name", "{$field} isn't a field (define it in \"fields\" or use a built-in: ${Fields.BuiltIn.joinToString()})"))
                    if (format.isNotEmpty() && format !in Times.Formats) add(Problem("$at.$name", "{$field|$format}: the time formats are ${Times.Formats.joinToString()}"))
                    if (format.isEmpty() && field in Fields.RawTimes && field !in r.fields) {
                        add(Problem("$at.$name", "{$field} is a time in ms; give it a format, as {$field|ago}", error = false))
                    }
                }
                val words = Placeholder.replace(t, "")
                if (words.length > MAX_LITERAL) add(Problem("$at.$name", "the recipe's own words are at most $MAX_LITERAL characters"))
                if ('{' in words || '}' in words) add(Problem("$at.$name", "a brace that isn't a {field}"))
            }
            template("title", f.title); template("line", f.line); template("peek", f.peek)
            template("count", f.count); template("label", f.label); template("person", f.person)
            template("value", f.value); template("max", f.max)
            when (f.kind) {
                FaceKind.Headline -> if (f.title == null) add(Problem(at, "a headline face needs a title"))
                FaceKind.Count -> if (f.count == null) add(Problem(at, "a count face needs a count (\"{count}\")"))
                FaceKind.Message -> if (f.person == null || f.line == null) add(Problem(at, "a message face needs a person and a line"))
                FaceKind.Progress -> if (f.title == null || f.value == null) add(Problem(at, "a progress face needs a title and a value"))
            }
            f.whenAll.forEach { c -> Conditions.problem(c, known)?.let { add(Problem("$at.when", it)) } }
            f.picture?.let { p ->
                if (p !in Pictures) add(Problem("$at.picture", "\"picture\" or \"largeIcon\""))
                if (f.kind == FaceKind.Progress) add(Problem("$at.picture", "a progress face draws the live update face, with the app's own logo; no picture", error = false))
            }
        }
        if (r.meta.status !in Statuses) add(Problem("meta.status", "\"unverified\", \"built-in\", \"verified\", \"draft\" or \"learned\"", error = false))
    }

    private fun regexProblem(re: String): String? {
        if (re.length > MAX_REGEX) return "at most $MAX_REGEX characters"
        return try {
            Rx.compile(re); null
        } catch (e: Exception) {
            "not a regex this engine reads (RE2 syntax: no backreferences or lookaround): ${e.message?.lineSequence()?.firstOrNull()}"
        }
    }

    /** [r] as the JSON a recipe file holds, its format id first (a default, so not written otherwise). */
    fun write(r: Recipe): String {
        val body = json.encodeToJsonElement(Recipe.serializer(), r) as JsonObject
        return json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("recipe" to JsonPrimitive(r.recipe)) + body))
    }
}

/** Reads a string or a list of strings as a list (`"from": "text"` or `"from": ["bigText", "text"]`). */
internal object StringListSerializer : KSerializer<List<String>> {
    private val list = ListSerializer(String.serializer())
    override val descriptor = list.descriptor
    override fun serialize(encoder: Encoder, value: List<String>) = list.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<String> {
        val input = decoder as? JsonDecoder ?: return list.deserialize(decoder)
        return when (val e = input.decodeJsonElement()) {
            is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(e.contentOrNull)
            else -> emptyList()
        }
    }
}

/** "90s", "45m", "18h", "2d" → milliseconds; null when it isn't one. */
object Durations {
    private val Shape = Regex("^\\s*(\\d{1,5})\\s*([smhd])\\s*$")
    fun parse(text: String?): Long? {
        val m = Shape.matchEntire(text ?: return null) ?: return null
        val n = m.groupValues[1].toLong()
        return n * when (m.groupValues[2]) { "s" -> 1_000L; "m" -> 60_000L; "h" -> 3_600_000L; else -> 86_400_000L }
    }
}

/** An app `versionCode` range (">=1240000", ">1", "<=9", "<2000", "1200-1300", "1240000"). */
object Versions {
    data class Range(val min: Long?, val max: Long?) {
        fun contains(v: Long): Boolean = (min == null || v >= min) && (max == null || v <= max)
    }

    private val Op = Regex("^\\s*(>=|>|<=|<|=)?\\s*(\\d{1,19})\\s*$")
    private val Span = Regex("^\\s*(\\d{1,19})\\s*-\\s*(\\d{1,19})\\s*$")

    fun parse(text: String): Range? {
        Span.matchEntire(text)?.let { return Range(it.groupValues[1].toLong(), it.groupValues[2].toLong()) }
        val m = Op.matchEntire(text) ?: return null
        val n = m.groupValues[2].toLongOrNull() ?: return null
        return when (m.groupValues[1]) {
            ">=" -> Range(n, null)
            ">" -> Range(n + 1, null)
            "<=" -> Range(null, n)
            "<" -> Range(null, n - 1)
            else -> Range(n, n)
        }
    }

    /** Whether [versionCode] is in [range]; an unknown version or no range is in. */
    fun inRange(range: String?, versionCode: Long?): Boolean {
        if (range == null || versionCode == null) return true
        return parse(range)?.contains(versionCode) ?: true
    }
}

/** The notification action semantics a recipe may name, as `Notification.Action.SEMANTIC_ACTION_*`. */
object Semantics {
    val ByCode = mapOf(
        1 to "reply", 2 to "markAsRead", 3 to "markAsUnread", 4 to "delete", 5 to "archive",
        6 to "mute", 7 to "unmute", 8 to "thumbsUp", 9 to "thumbsDown", 10 to "call",
    )
    val Names: Set<String> = ByCode.values.toSet()
    fun of(code: Int): String? = ByCode[code]
}

/** How a recipe keys its subjects ([NotificationSource.key]). */
object Keys {
    fun isKnown(key: String, fields: Set<String>): Boolean = when {
        key in setOf("tag", "group", "id") -> true
        key.startsWith("field:") -> key.removePrefix("field:") in fields
        else -> false
    }
}
