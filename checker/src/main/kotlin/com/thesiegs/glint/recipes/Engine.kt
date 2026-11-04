package com.thesiegs.glint.recipes

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
)

/** One face as it will draw: count faces carry the number in [title] and the word in [line]; message faces the person in [title]. */
data class DrawnFace(
    val index: Int,
    val kind: FaceKind,
    val title: String,
    val line: String? = null,
    val peek: String? = null,
    /** Progress faces: 0..1. */
    val fraction: Float? = null,
)

/** A field's last value for one subject: [value], the time of the notification it came from, and when it was last seen up. */
data class HeldValue(val value: String, val posted: Long, val seen: Long)

/**
 * Values kept between notifications (recipes/SPEC.md §4.3: `hold`): by subject, then field. Only on
 * the phone, in memory: never in a backup. [latest] is the subject seen most recently.
 */
data class HeldState(val subjects: Map<String, Map<String, HeldValue>> = emptyMap(), val latest: String? = null) {
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
    /** How many of the app's notifications up this recipe didn't take. */
    val unmatched: Int,
) {
    val drew: Boolean get() = faces.isNotEmpty()
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
        val ofApp = samples.filter { appMatches(recipe, it) }
        val matched = ofApp.filter { matches(recipe, it, ctx) && (expire == null || ctx.now - it.posted <= expire) }
            // A group summary (read only with "summaries": true) never wins over the notifications it sums up.
            .let { order(it, src.pick).sortedBy { n -> n.summary } }
        val notApplicable = recipe.fields.filterValues { !applies(it, ctx) }.keys
        val perNote = matched.associate { it.key to extract(recipe, it, ctx) }
        val subjectOf = matched.associate { it.key to subject(src.key, it, perNote[it.key].orEmpty()) }
        val subjects = matched.mapTo(LinkedHashSet()) { subjectOf.getValue(it.key) }
        val winner = matched.firstOrNull()
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
        val missingRequired = if (winner == null) emptyList()
        else recipe.fields.filter { (k, r) -> r.required && k !in notApplicable && k !in own }.keys.toList()

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

        // The built-ins (from the winner), under the recipe's own fields.
        val all = LinkedHashMap<String, String>()
        // {count} counts what a summary sums up, not the summary too (a lone summary counts itself).
        val count = matched.count { !it.summary }.takeIf { it > 0 } ?: matched.size
        builtIns(winner, count, subjects.size, ctx).forEach { (k, v) -> all[k] = v }
        all.putAll(values)
        if (winner == null) {
            // Nothing up: only held values draw, never a "0" count or an empty headline.
            all.remove("count"); all.remove("keys")
        }
        val faces = if (winner == null && values.isEmpty()) emptyList() else faces(recipe, all, ctx)
        return Evaluation(
            recipeId = recipe.id,
            matched = matched,
            winner = winner,
            values = all,
            faces = faces,
            missingRequired = missingRequired,
            notApplicable = notApplicable,
            probation = !Versions.inRange(recipe.app.versions, ctx.versionCode),
            held = newHeld,
            unmatched = ofApp.count { n -> !n.summary && matched.none { it.key == n.key } },
        )
    }

    fun appMatches(recipe: Recipe, n: NotificationSample): Boolean =
        if (recipe.app.packageName == Recipes.ANY_APP) n.packageName !in Recipes.PeopleApps
        else n.packageName == recipe.app.packageName

    /** Whether [n] is one of the notifications [recipe] reads (its app's, and every match rule). */
    fun matches(recipe: Recipe, n: NotificationSample, ctx: RecipeContext): Boolean {
        if (!appMatches(recipe, n)) return false
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

    fun order(list: List<NotificationSample>, pick: Pick): List<NotificationSample> = when (pick) {
        Pick.Newest -> list.sortedByDescending { it.posted }
        Pick.Priority -> list.sortedWith(compareByDescending<NotificationSample> { it.importance }.thenByDescending { it.priority }.thenByDescending { it.posted })
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
            if (rule.before != null) v = before(v, rule.before) ?: continue
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

    private fun before(text: String, anchor: String): String? {
        val i = text.indexOf(anchor, ignoreCase = true).takeIf { it > 0 } ?: return null
        val head = text.substring(0, i)
        val start = head.lastIndexOfAny(Breaks) + 1
        return head.substring(start).trim().takeIf { it.isNotEmpty() }
    }

    /** What one subject is ([NotificationSource.key]); a notification with no such value is its own. */
    fun subject(key: String, n: NotificationSample, values: Map<String, String>): String = when {
        key == "tag" -> n.tag?.let { "t:$it" }
        key == "group" -> n.group?.let { "g:$it" }
        key.startsWith("extra:") -> n.extras[key.removePrefix("extra:")]?.let { "x:$it" }
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
    }

    /** The faces that draw, in the recipe's order; optIn faces only once turned on. */
    fun faces(recipe: Recipe, values: Map<String, String>, ctx: RecipeContext): List<DrawnFace> =
        recipe.faces.mapIndexedNotNull { i, f ->
            if (f.optIn && i !in ctx.optIn) return@mapIndexedNotNull null
            if (!f.whenAll.all { Conditions.holds(it, values) }) return@mapIndexedNotNull null
            draw(i, f, values)
        }

    private fun draw(i: Int, f: FaceRule, values: Map<String, String>): DrawnFace? {
        fun fill(t: String?): String? = t?.let { Templates.fill(it, values) }
        // A template given that can't be filled stops the face; one not given is simply absent.
        fun need(t: String?): Pair<Boolean, String?> = if (t == null) true to null else fill(t).let { (it != null) to it }
        // The peek is the quiet extra: without its fields it's left off, the face still draws.
        val peek = fill(f.peek)
        return when (f.kind) {
            FaceKind.Headline -> {
                val title = fill(f.title) ?: return null
                val (ok, line) = need(f.line)
                if (!ok) null else DrawnFace(i, f.kind, title, line, peek)
            }
            FaceKind.Message -> {
                val who = fill(f.person) ?: return null
                val line = fill(f.line) ?: return null
                DrawnFace(i, f.kind, who, line, peek)
            }
            FaceKind.Count -> {
                val n = fill(f.count)?.trim() ?: return null
                val number = n.replace(",", "").toLongOrNull() ?: return null
                if (number <= 0) return null
                val (ok, label) = need(f.label)
                if (!ok) null else DrawnFace(i, f.kind, if (number > 99) "99+" else number.toString(), label, peek)
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
                if (!ok) null else DrawnFace(i, f.kind, title, line, peek, fraction.coerceIn(0f, 1f))
            }
        }
    }
}

/** `{field}` templates: every placeholder needs a value, else the template doesn't fill. */
object Templates {
    fun fill(template: String, values: Map<String, String>): String? {
        var missing = false
        val out = Recipes.Placeholder.replace(template) { m ->
            values[m.groupValues[1]] ?: run { missing = true; "" }
        }
        return if (missing) null else out.trim().takeIf { it.isNotEmpty() }
    }

    fun fields(template: String?): List<String> =
        template?.let { t -> Recipes.Placeholder.findAll(t).map { it.groupValues[1] }.toList() }.orEmpty()
}
