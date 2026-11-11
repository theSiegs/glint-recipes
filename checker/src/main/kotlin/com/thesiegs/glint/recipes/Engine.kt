package com.thesiegs.glint.recipes

import com.thesiegs.glint.recipes.learn.ShapeLearner
import java.time.ZoneId

/**
 * What the engine needs besides the notifications: the time, the phone's language (for `lang`
 * tagged regexes), the app's versionCode (for probation), the app's name ({app}) and which optIn
 * faces the owner turned on, by face index.
 */
data class RecipeContext(
    val now: Long,
    val language: String = "en",
    val versionCode: Long? = null,
    val appLabel: String? = null,
    val optIn: Set<Int> = emptySet(),
    val zone: ZoneId = ZoneId.systemDefault(),
    val h24: Boolean = false,
    /** An app's kind as the catalog names it ("car", "charging"), for kind recipes ([RecipeApp.kind]); null when unknown. */
    val kindOf: (String) -> String? = { null },
    /**
     * Whether a notification is from someone the owner knows (a People tile or a contact), for
     * [Pick.People]; worked out on the phone, never part of a sample.
     */
    val known: (NotificationSample) -> Boolean = { false },
    /**
     * The one account the tile follows ([Accounts.key] of the account [NotificationSource.account]
     * names), or null for every account. A recipe without an account source ignores it.
     */
    val account: String? = null,
)

/**
 * One face as it will draw: count faces carry the number in [title] and the word in [line]; message
 * faces the person in [title]. [item] is whose it is (0 the lead subject, 1.. the others up) and
 * [key] the notification it was read from (a tap opens it, its picture is drawn from it).
 */
data class DrawnFace(
    val index: Int,
    val kind: FaceKind,
    val title: String,
    val line: String? = null,
    val peek: String? = null,
    /** Progress faces: 0..1. */
    val fraction: Float? = null,
    /** Progress faces: ProgressStyle's segment ends and points, each 0..1 along the track. */
    val stops: List<Float> = emptyList(),
    val points: List<Float> = emptyList(),
    /** Progress faces: the app draws its own tracker icon. */
    val tracker: Boolean = false,
    /** "picture" or "largeIcon": the notification's own, as the face's whole background. */
    val picture: String? = null,
    /** Its notification is older than the recipe's `fade`: the face draws dimmed. */
    val faded: Boolean = false,
    val item: Int = 0,
    val key: String? = null,
    /** A small number in the corner ([FaceRule.corner]): how many wait, from 2 up. */
    val corner: String? = null,
    /** The quiet extra line on a large wide tile ([FaceRule.detail]): a mail's snippet. */
    val detail: String? = null,
    /** An idle face ([FaceRule.idle]): nothing is up. */
    val idle: Boolean = false,
)

/** A field's last value for one subject: [value], the time of the notification it came from, and when it was last seen up. */
data class HeldValue(val value: String, val posted: Long, val seen: Long)

/**
 * Values kept between notifications (recipes/SPEC.md §4.3: `hold`): by subject, then field. Only on
 * the phone, in memory: never in a backup. [latest] is the subject seen most recently.
 */
data class HeldState(
    val subjects: Map<String, Map<String, HeldValue>> = emptyMap(),
    val latest: String? = null,
    /**
     * The last lead's values an idle face names ("last mail 9:40 · Alex Rivera"), kept for the day
     * only (memory only, never written), whatever the owner did with its notification.
     */
    val last: Map<String, String> = emptyMap(),
) {
    companion object { val Empty = HeldState() }
}

/**
 * One run of a recipe over an app's notifications. [faces] is what the tile draws, rest face first;
 * empty means the plain app tile. [winner] is the notification a tap opens. [missingRequired] are
 * required fields the matched notification lacked (not those [notApplicable] in this language), for
 * the health watch. [probation]: the app's version is outside the recipe's range.
 */
data class Evaluation(
    val recipeId: String,
    val matched: List<NotificationSample>,
    val winner: NotificationSample?,
    val values: Map<String, String>,
    val faces: List<DrawnFace>,
    val missingRequired: List<String>,
    val notApplicable: Set<String>,
    val probation: Boolean,
    val held: HeldState,
    /** How many of the app's notifications up this recipe didn't take, of those that could carry its state ([ShapeLearner.isUseful]). */
    val unmatched: Int,
    /** Each matched notification's subject, by its key ([HeldState] is kept by subject). */
    val subjects: Map<String, String> = emptyMap(),
    /** The accounts seen in what it read ([NotificationSource.account]), newest first; empty without an account source. */
    val accounts: List<String> = emptyList(),
) {
    /** It drew from what is up (an idle face, "nothing new", says nothing is). */
    val drew: Boolean get() = faces.any { !it.idle }
}

/**
 * The recipe engine (recipes/SPEC.md): pure, so the phone and the checker run the same code. Match the
 * app's notifications, order them by the recipe's pick, take the winner's fields (merged from its
 * subject's other notifications, then held values), and fill the faces whose fields are all there.
 */
object RecipeEngine {

    fun evaluate(recipe: Recipe, samples: List<NotificationSample>, ctx: RecipeContext, held: HeldState = HeldState.Empty): Evaluation {
        val src = recipe.notification
        val expire = Durations.parse(src.expire)
        // A card alone (no source) reads no notifications.
        val ofApp = if (recipe.source.notification == null) emptyList() else samples.filter { appMatches(recipe, it, ctx) }
        val read = ofApp.filter { matches(recipe, it, ctx) && (expire == null || ctx.now - it.posted <= expire) }
        // The accounts seen, newest first; the tile follows one when the owner chose it ([RecipeContext.account]).
        val accounts = src.account?.let { a -> read.sortedByDescending { it.posted }.mapNotNull { Accounts.of(a, it) }.distinctBy { it.lowercase() } }.orEmpty()
        val matched = read.filter { n -> src.account == null || ctx.account == null || Accounts.of(src.account, n)?.let(Accounts::key) == ctx.account }
            // A group summary (read only with "summaries": true) never wins over the notifications it sums up.
            .let { order(it, src.pick, ctx.known).sortedBy { n -> n.summary } }
        val idles = recipe.faces.any { it.idle }
        val notApplicable = recipe.fields.filterValues { !applies(it, ctx) }.keys
        val perNote = matched.associate { it.key to extract(recipe, it, ctx) }
        val subjectOf = matched.associate { it.key to subject(src.key, it, perNote[it.key].orEmpty()) }
        val subjects = matched.mapTo(LinkedHashSet()) { subjectOf.getValue(it.key) }
        // With an idle face a summary alone is no news (it may outlive its threads): the idle face shows.
        val winner = matched.firstOrNull()?.takeUnless { it.summary && idles }
        val subject = winner?.let { subjectOf.getValue(it.key) } ?: held.latest

        // The winner's own values, then (merge) its subject's other notifications', newest first.
        val own = LinkedHashMap<String, String>()
        val postedOf = HashMap<String, Long>()
        if (winner != null) {
            val sources = if (src.merge) listOf(winner) + matched.drop(1).filter { subjectOf[it.key] == subject }.sortedByDescending { it.posted }
            else listOf(winner)
            for (n in sources) perNote[n.key].orEmpty().forEach { (k, v) ->
                if (k !in own) { own[k] = v; postedOf[k] = n.posted }
            }
        }
        // A value older than its field's stale time is dropped.
        recipe.fields.forEach { (k, rule) ->
            val stale = Durations.parse(rule.stale) ?: return@forEach
            if (postedOf[k]?.let { ctx.now - it > stale } == true) own.remove(k)
        }
        // Held values fill what's missing, while their hold lasts; then the hold is brought up to date.
        val holdOf = { k: String -> Durations.parse(recipe.fields[k]?.hold) ?: Durations.parse(src.hold) }
        val keptForSubject = subject?.let { held.subjects[it] }.orEmpty()
        val values = LinkedHashMap(own)
        keptForSubject.forEach { (k, hv) ->
            val hold = holdOf(k) ?: return@forEach
            val stale = Durations.parse(recipe.fields[k]?.stale)
            if (k !in values && ctx.now - hv.seen <= hold && (stale == null || ctx.now - hv.posted <= stale)) values[k] = hv.value
        }
        val newHeld = updateHeld(recipe, held, subject, own, postedOf, ctx.now, holdOf)
        // Required means "the tile can't draw without it": a held value counts (a gate change without the flight number).
        val missingRequired = if (winner == null) emptyList()
        else recipe.fields.filter { (k, r) -> r.required && k !in notApplicable && k !in values }.keys.toList()

        // The built-ins (from the winner), under the recipe's own fields.
        val all = LinkedHashMap<String, String>()
        // {count} counts what a summary sums up, not the summary too (a lone summary counts itself).
        val count = matched.count { !it.summary }.takeIf { it > 0 } ?: matched.size
        builtIns(winner, count, subjects.size, ctx).forEach { (k, v) -> all[k] = v }
        all["total"] = total(matched).toString()
        all.putAll(values)
        if (winner == null) {
            // Nothing up: only held values draw, never a "0" count or an empty headline.
            all.remove("count"); all.remove("keys"); all.remove("total")
        }
        val leadPosted = winner?.posted ?: keptForSubject.values.maxOfOrNull { it.posted }
        // What an idle face names of the last lead, kept for the day ([HeldState.last]).
        val last = when {
            !idles -> emptyMap()
            winner != null -> idleFields(recipe).mapNotNull { k -> all[k]?.let { k to it } }.toMap()
            else -> held.last.takeIf { sameDay(it["posted"]?.toLongOrNull(), ctx) }.orEmpty()
        }
        val faces = when {
            winner == null && values.isEmpty() -> if (idles) idleFaces(recipe, last, ctx) else emptyList()
            else -> tileFaces(recipe, all, ctx, winner, leadPosted, Lead(matched, subject, subjectOf, perNote, held, count, subjects.size, all["total"]))
        }
        return Evaluation(
            recipeId = recipe.id,
            matched = matched,
            winner = winner,
            values = all,
            faces = faces,
            missingRequired = missingRequired,
            notApplicable = notApplicable,
            probation = !Versions.inRange(recipe.app.versions, ctx.versionCode),
            held = newHeld.copy(last = last),
            // Only what could carry the app's state counts (promos, summaries and the like say nothing about the recipe).
            unmatched = ofApp.count { n -> !n.summary && ShapeLearner.isUseful(n) && matched.none { it.key == n.key } },
            subjects = subjectOf,
            accounts = accounts,
        )
    }

    /**
     * How many wait in every group read (`{total}`): a summary's own number (`Notification.number`,
     * no words) when it set one, else the notifications it sums up; a summary alone with no number
     * counts nothing (it may outlive its threads).
     */
    fun total(matched: List<NotificationSample>): Int =
        matched.groupBy { it.group ?: "k:${it.key}" }.values.sumOf { g ->
            val kids = g.count { !it.summary }
            val number = g.filter { it.summary }.maxOfOrNull { it.number } ?: 0
            if (number > 0) maxOf(number, kids) else kids
        }

    /** The fields an idle face names: what [HeldState.last] keeps of the lead. */
    private fun idleFields(recipe: Recipe): Set<String> = recipe.faces.filter { it.idle }
        .flatMap { f -> listOfNotNull(f.title, f.line, f.peek, f.detail).flatMap(Templates::fields) }.toSet()

    private fun sameDay(t: Long?, ctx: RecipeContext): Boolean {
        if (t == null || t <= 0) return false
        val day = { ms: Long -> java.time.Instant.ofEpochMilli(ms).atZone(ctx.zone).toLocalDate() }
        return day(t) == day(ctx.now)
    }

    /** Nothing up: the first idle face that draws ("nothing new"), from what's kept of the last lead; its peek is left off without it. */
    private fun idleFaces(recipe: Recipe, last: Map<String, String>, ctx: RecipeContext): List<DrawnFace> {
        val values = LinkedHashMap(last)
        ctx.appLabel?.takeIf { it.isNotBlank() }?.let { values["app"] = it }
        return listOfNotNull(recipe.faces.withIndex().firstNotNullOfOrNull { (i, f) ->
            if (!f.idle || f.optIn && i !in ctx.optIn || !f.whenAll.all { Conditions.holds(it, values) }) null
            else draw(i, f, values, ctx, null)?.copy(idle = true, detail = f.detail?.let { Templates.fill(it, values, ctx) })
        })
    }

    /** [held] without [subjects]: the owner dismissed their notifications, so nothing of them is kept. */
    fun forget(held: HeldState, subjects: Set<String>): HeldState {
        if (subjects.isEmpty() || subjects.none { it in held.subjects }) return held
        val kept = held.subjects - subjects
        return HeldState(kept, held.latest?.takeIf { it in kept }, held.last)
    }

    /** What the other subjects' faces are read from, besides the lead's own values. */
    private class Lead(
        val matched: List<NotificationSample>, val subject: String?, val subjectOf: Map<String, String>,
        val perNote: Map<String, Map<String, String>>, val held: HeldState, val count: Int, val keys: Int,
        val total: String? = null,
    )

    /**
     * The tile's faces (recipes/SPEC.md §6): the lead subject's rest face (the first rung of the
     * ladder that draws), each other subject's rest face (an order, a flight: one item per face, at
     * most [Recipes.MAX_ITEMS] in all), then the lead's flip faces. A face the same as one before it
     * is left out, so the tile never flips to what it already shows.
     */
    private fun tileFaces(
        recipe: Recipe, values: Map<String, String>, ctx: RecipeContext, winner: NotificationSample?, posted: Long?, lead: Lead,
    ): List<DrawnFace> {
        val (rest, flips) = item(recipe, values, ctx, winner, 0, posted)
        // Each other subject's own winner: the first of it in pick order (summaries are last).
        val others = if (winner == null) emptyList() else lead.matched.asSequence()
            .filter { !it.summary && lead.subjectOf[it.key] != lead.subject }
            .distinctBy { lead.subjectOf[it.key] }
            .take(Recipes.MAX_ITEMS - 1).toList()
        val more = others.mapIndexedNotNull { i, n ->
            val v = LinkedHashMap(builtIns(n, lead.count, lead.keys, ctx))
            lead.total?.let { v["total"] = it }
            v.putAll(subjectValues(recipe, n, lead.subjectOf.getValue(n.key), lead, ctx))
            item(recipe, v, ctx, n, i + 1, n.posted).first
        }
        return (listOfNotNull(rest) + more + flips).distinctBy { Triple(it.kind, it.title, it.line) }
    }

    /** Another subject's values: its winner's, merged from its other notifications, then its held ones (as the lead's). */
    private fun subjectValues(recipe: Recipe, top: NotificationSample, s: String, lead: Lead, ctx: RecipeContext): Map<String, String> {
        val src = recipe.notification
        val own = LinkedHashMap<String, String>()
        val postedOf = HashMap<String, Long>()
        val sources = if (!src.merge) listOf(top)
        else listOf(top) + lead.matched.filter { it.key != top.key && lead.subjectOf[it.key] == s }.sortedByDescending { it.posted }
        for (n in sources) lead.perNote[n.key].orEmpty().forEach { (k, v) ->
            if (k !in own) { own[k] = v; postedOf[k] = n.posted }
        }
        recipe.fields.forEach { (k, rule) ->
            val stale = Durations.parse(rule.stale) ?: return@forEach
            if (postedOf[k]?.let { ctx.now - it > stale } == true) own.remove(k)
        }
        lead.held.subjects[s].orEmpty().forEach { (k, hv) ->
            val hold = Durations.parse(recipe.fields[k]?.hold) ?: Durations.parse(src.hold) ?: return@forEach
            val stale = Durations.parse(recipe.fields[k]?.stale)
            if (k !in own && ctx.now - hv.seen <= hold && (stale == null || ctx.now - hv.posted <= stale)) own[k] = hv.value
        }
        return own
    }

    /**
     * One item's faces: its rest face, the first rung of the ladder (faces not [FaceRule.flip]) that
     * draws, and, for the lead ([item] 0) only, the flip faces that draw. optIn faces draw only once
     * turned on. A face from a notification posted before the recipe's `fade` draws dimmed.
     */
    private fun item(
        recipe: Recipe, values: Map<String, String>, ctx: RecipeContext, from: NotificationSample?, item: Int, posted: Long?,
    ): Pair<DrawnFace?, List<DrawnFace>> {
        val fade = Durations.parse(recipe.notification.fade)
        val faded = fade != null && posted != null && ctx.now - posted > fade
        fun drawn(i: Int, f: FaceRule): DrawnFace? {
            if (f.optIn && i !in ctx.optIn) return null
            if (f.idle || !f.whenAll.all { Conditions.holds(it, values) }) return null
            return draw(i, f, values, ctx, from)?.copy(
                item = item, key = from?.key, faded = faded,
                corner = f.corner?.let { Templates.fill(it, values, ctx) }?.trim()?.replace(",", "")?.toLongOrNull()?.takeIf { it > 1 }
                    ?.let { if (it > 99) "99+" else it.toString() },
                detail = f.detail?.let { Templates.fill(it, values, ctx) },
            )
        }
        val rest = recipe.faces.withIndex().firstNotNullOfOrNull { (i, f) -> if (f.flip) null else drawn(i, f) }
        val flips = if (item > 0) emptyList() else recipe.faces.withIndex().mapNotNull { (i, f) -> if (f.flip) drawn(i, f) else null }
        return rest to flips
    }

    fun appMatches(recipe: Recipe, n: NotificationSample, ctx: RecipeContext? = null): Boolean = when {
        // A kind recipe: every app of its kind, as the catalog names them.
        recipe.app.kind != null -> recipe.app.packageName == Recipes.ANY_APP && ctx?.kindOf?.invoke(n.packageName) == recipe.app.kind
        recipe.app.packageName == Recipes.ANY_APP -> n.packageName !in Recipes.PeopleApps
        else -> n.packageName == recipe.app.packageName
    }

    /** Whether [n] is one of the notifications [recipe] reads (its app's, and every match rule). */
    fun matches(recipe: Recipe, n: NotificationSample, ctx: RecipeContext): Boolean {
        if (!appMatches(recipe, n, ctx)) return false
        val m = recipe.notification.match
        if (n.summary && !m.summaries) return false
        if (m.channels.isNotEmpty() && m.channels.none { like(it, n.channel) }) return false
        if (m.notChannels.any { like(it, n.channel) }) return false
        if (m.categories.isNotEmpty() && m.categories.none { like(it, n.category) }) return false
        if (m.notCategories.any { like(it, n.category) }) return false
        if (m.templates.isNotEmpty() && m.templates.none { it.equals(n.template ?: "None", ignoreCase = true) }) return false
        if (m.groups.isNotEmpty() && m.groups.none { g -> n.group?.let { it == g || g.endsWith("*") && it.startsWith(g.dropLast(1)) || it.contains("g:$g") } == true }) return false
        if (!tri(m.ongoing, n.ongoing)) return false
        if (!tri(m.progress, (n.max ?: 0) > 0 || n.indeterminate)) return false
        if (!tri(m.messaging, n.template == "MessagingStyle" || n.messages.isNotEmpty())) return false
        if (m.actions.isNotEmpty() && m.actions.none { s -> n.actions.any { it.semantic == s } }) return false
        if (m.lang.isEmpty() || ctx.language in m.lang) {
            val all = listOfNotNull(n.title, n.text, n.bigText).joinToString("\n")
            if (m.anchors.isNotEmpty() && m.anchors.none { Rx.find(it, all) }) return false
            if (m.titles.isNotEmpty() && m.titles.none { Rx.find(it, n.title.orEmpty()) }) return false
            if (m.exclude.any { Rx.find(it, all) }) return false
        }
        return true
    }

    private fun like(rule: String, value: String?): Boolean {
        if (value == null) return false
        return if (rule.endsWith("*")) value.startsWith(rule.dropLast(1)) else value == rule
    }

    private fun tri(rule: String, has: Boolean): Boolean = when (rule) {
        "only" -> has
        "never" -> !has
        else -> true
    }

    fun order(list: List<NotificationSample>, pick: Pick, known: (NotificationSample) -> Boolean = { false }): List<NotificationSample> = when (pick) {
        Pick.Newest -> list.sortedByDescending { it.posted }
        // Someone the owner knows first (a People tile, a contact), then the newest.
        Pick.People -> list.sortedWith(compareByDescending<NotificationSample> { !it.summary && known(it) }.thenByDescending { it.posted })
        Pick.Ongoing -> list.sortedWith(compareByDescending<NotificationSample> { it.ongoing || it.promoted }.thenByDescending { it.posted })
    }

    private fun applies(rule: FieldRule, ctx: RecipeContext): Boolean = rule.lang.isEmpty() || ctx.language in rule.lang

    /** Every field [recipe] defines, read off [n]; a field not in this language is left out. */
    fun extract(recipe: Recipe, n: NotificationSample, ctx: RecipeContext): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        recipe.fields.forEach { (name, rule) ->
            if (!applies(rule, ctx)) return@forEach
            val v = field(rule, n) ?: rule.default
            if (v != null) out[name] = v
        }
        return out
    }

    /** One field: the first source that gives a value through the cut and the transforms. */
    fun field(rule: FieldRule, n: NotificationSample): String? {
        for (s in rule.from) {
            var v = Sources.read(s, n) ?: continue
            if (rule.after != null) v = after(v, rule.after) ?: continue
            if (rule.regex != null) v = Rx.capture(rule.regex, v) ?: continue
            var ok = true
            for (t in rule.then) {
                val next = Transforms.apply(t, v)
                if (next == null) { ok = false; break }
                v = next
            }
            if (!ok) continue
            rule.map.entries.firstOrNull { it.key.equals(v.trim(), ignoreCase = true) }?.let { v = it.value }
            v = v.trim()
            if (v.isNotEmpty()) return v
        }
        return null
    }

    private val Breaks = charArrayOf('\n', '·', '•', ',', ';', '|')

    private fun after(text: String, anchor: String): String? {
        val i = text.indexOf(anchor, ignoreCase = true).takeIf { it >= 0 } ?: return null
        val rest = text.substring(i + anchor.length)
        val end = rest.indexOfAny(Breaks).let { if (it < 0) rest.length else it }
        return rest.substring(0, end).trim().trimEnd('.', '!').takeIf { it.isNotEmpty() }
    }

    /** What one subject is ([NotificationSource.key]); a notification with no such value is its own. */
    fun subject(key: String, n: NotificationSample, values: Map<String, String>): String = when {
        key == "tag" -> n.tag?.let { "t:$it" }
        key == "group" -> n.group?.let { "g:$it" }
        key.startsWith("field:") -> values[key.removePrefix("field:")]?.let { "f:${it.lowercase()}" }
        else -> null
    } ?: "k:${n.key}"

    private fun updateHeld(
        recipe: Recipe, held: HeldState, subject: String?, own: Map<String, String>, postedOf: Map<String, Long>, now: Long,
        holdOf: (String) -> Long?,
    ): HeldState {
        val anyHold = Durations.parse(recipe.notification.hold) != null || recipe.fields.values.any { Durations.parse(it.hold) != null }
        if (!anyHold) return HeldState.Empty
        val subjects = HashMap<String, Map<String, HeldValue>>()
        // Old subjects keep what's still inside its hold.
        held.subjects.forEach { (s, fields) ->
            val kept = fields.filter { (k, hv) -> holdOf(k)?.let { now - hv.seen <= it } == true }
            if (kept.isNotEmpty()) subjects[s] = kept
        }
        if (subject != null && own.isNotEmpty()) {
            val merged = HashMap(subjects[subject].orEmpty())
            own.forEach { (k, v) -> if (holdOf(k) != null) merged[k] = HeldValue(v, postedOf[k] ?: now, now) }
            if (merged.isNotEmpty()) subjects[subject] = merged
        }
        val latest = if (own.isNotEmpty()) subject else held.latest?.takeIf { it in subjects }
        return HeldState(subjects, latest)
    }

    private fun builtIns(w: NotificationSample?, count: Int, keys: Int, ctx: RecipeContext): Map<String, String> = buildMap {
        put("count", count.toString())
        put("keys", keys.toString())
        ctx.appLabel?.takeIf { it.isNotBlank() }?.let { put("app", it) }
        if (w == null) return@buildMap
        listOf("title", "text", "bigText", "subText", "infoText", "summaryText", "conversationTitle", "shortCritical", "sender", "message", "progress", "max")
            .forEach { k -> Sources.read(k, w)?.let { put(k, it.trim()) } }
        val max = w.max ?: 0
        if (max > 0 && w.progress != null) put("percent", "${(w.progress * 100 / max).coerceIn(0, 100)}%")
        if (w.lines.isNotEmpty()) put("lineCount", w.lines.size.toString())
        if (w.posted > 0) put("time", clockTime(w.posted, ctx.zone, ctx.h24))
        if (w.whenTime > 0) put("at", clockTime(w.whenTime, ctx.zone, ctx.h24))
        // Raw times, for a format ({posted|ago}, {when|in}); a running chronometer as words.
        if (w.posted > 0) put("posted", w.posted.toString())
        if (w.whenTime > 0) put("when", w.whenTime.toString())
        if (w.chronometer) Times.chrono(w.whenTime, w.countDown, ctx.now)?.let { put("chrono", it) }
        // Typed facts from the words ([Facts], review 2026-11-10 §2.3): one vocabulary with Live Updates and the Car tile.
        val t = Sources.factText(w)
        if (t.isNotEmpty()) {
            val (level, limit) = Facts.levelAndLimit(t)
            if (!containsKey("percent")) level?.let { put("percent", "$it%") }
            limit?.let { put("limitPercent", "$it%") }
            Facts.distance(t)?.let { (v, unit) -> put("distance", (if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()) + " " + unit) }
            Facts.powerKw(t)?.takeIf { it > 0 }?.let { put("power", (if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()) + " kW") }
            val eta = Facts.doneBy(t, w.posted.takeIf { it > 0 } ?: ctx.now, java.util.TimeZone.getTimeZone(ctx.zone))
                ?: Facts.minutesLeft(t)?.let { (w.posted.takeIf { p -> p > 0 } ?: ctx.now) + it * 60_000L }
            eta?.let { put("eta", it.toString()) }
            Facts.route(t)?.let { (a, b) -> put("route", "${a.lowercase()} → ${b.lowercase()}") }
        }
    }

    private fun draw(i: Int, f: FaceRule, values: Map<String, String>, ctx: RecipeContext, from: NotificationSample?): DrawnFace? {
        fun fill(t: String?): String? = t?.let { Templates.fill(it, values, ctx) }
        val picture = f.picture?.takeIf { p ->
            from != null && (p == "picture" && from.hasPicture || p == "largeIcon" && from.hasLargeIcon)
        }
        // A template given that can't be filled stops the face; one not given is simply absent.
        fun need(t: String?): Pair<Boolean, String?> = if (t == null) true to null else fill(t).let { (it != null) to it }
        // The peek is the quiet extra: without its fields it's left off, the face still draws.
        val peek = fill(f.peek)
        return when (f.kind) {
            FaceKind.Headline -> {
                val title = fill(f.title) ?: return null
                val (ok, line) = need(f.line)
                if (!ok) null else DrawnFace(i, f.kind, title, line, peek, picture = picture)
            }
            FaceKind.Message -> {
                val who = fill(f.person) ?: return null
                val line = fill(f.line) ?: return null
                DrawnFace(i, f.kind, who, line, peek, picture = picture)
            }
            FaceKind.Count -> {
                val n = fill(f.count)?.trim() ?: return null
                val number = n.replace(",", "").toLongOrNull() ?: return null
                if (number <= 0) return null
                val (ok, label) = need(f.label)
                if (!ok) null else DrawnFace(i, f.kind, if (number > 99) "99+" else number.toString(), label, peek, picture = picture)
            }
            FaceKind.Eta -> {
                // When, big; the route or place under it.
                val title = fill(f.title) ?: return null
                val (ok, line) = need(f.line)
                if (!ok) null else DrawnFace(i, f.kind, title, line, peek, picture = picture)
            }
            FaceKind.Gauge -> {
                // A value toward a limit: [max] is the limit ("{limitPercent}"); 100 when a percent has none.
                val title = fill(f.title) ?: return null
                val v = fill(f.value)?.trim()?.removeSuffix("%")?.trim()?.toFloatOrNull() ?: return null
                val max = (if (f.max != null) fill(f.max)?.trim()?.removeSuffix("%")?.trim()?.toFloatOrNull() ?: return null else 100f)
                if (max <= 0f) return null
                val (ok, line) = need(f.line)
                if (!ok) null else DrawnFace(i, f.kind, title, line, peek, (v / max).coerceIn(0f, 1f))
            }
            FaceKind.Progress -> {
                val title = fill(f.title) ?: return null
                val v = fill(f.value)?.trim() ?: return null
                val fraction = if (v.endsWith("%")) v.dropLast(1).trim().toFloatOrNull()?.div(100f)
                else {
                    val max = fill(f.max)?.trim()?.toFloatOrNull()
                    val cur = v.toFloatOrNull()
                    if (cur == null || max == null || max <= 0f) null else cur / max
                } ?: return null
                val (ok, line) = need(f.line)
                if (!ok) return null
                val (stops, points) = track(from)
                DrawnFace(i, f.kind, title, line, peek, fraction.coerceIn(0f, 1f), stops, points, tracker = from?.tracker == true)
            }
        }
    }

    /** ProgressStyle's segment ends and points along the track, 0..1 (as Glint's own Live Update face reads them). */
    fun track(n: NotificationSample?): Pair<List<Float>, List<Float>> {
        if (n == null) return emptyList<Float>() to emptyList()
        val total = n.segments.sum().takeIf { it > 0 } ?: n.max?.takeIf { it > 0 } ?: return emptyList<Float>() to emptyList()
        val stops = n.segments.runningReduce(Int::plus).dropLast(1).map { (it.toFloat() / total).coerceIn(0f, 1f) }
        val points = n.points.map { (it.toFloat() / total).coerceIn(0f, 1f) }
        return stops to points
    }
}

/**
 * `{field}` templates: every placeholder needs a value, else the template doesn't fill. `{field|in}`
 * says the value as a time relative to [RecipeContext.now] ([Times]); without a context, or when the
 * format doesn't fit the value, it doesn't fill either.
 */
object Templates {
    fun fill(template: String, values: Map<String, String>, ctx: RecipeContext? = null): String? {
        var missing = false
        val out = Recipes.Placeholder.replace(template) { m ->
            val v = values[m.groupValues[1]]
            val format = m.groupValues[2]
            val shown = when {
                v == null -> null
                format.isEmpty() -> v
                ctx == null -> null
                else -> Times.format(format, v, ctx)
            }
            shown ?: run { missing = true; "" }
        }
        return if (missing) null else out.trim().takeIf { it.isNotEmpty() }
    }

    fun fields(template: String?): List<String> =
        template?.let { t -> Recipes.Placeholder.findAll(t).map { it.groupValues[1] }.toList() }.orEmpty()
}
