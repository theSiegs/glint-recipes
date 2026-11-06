package com.thesiegs.glint.recipes

import kotlinx.serialization.Serializable

/**
 * How a recipe is doing on this phone (recipes/SPEC.md §9), kept as times and counts only, never
 * text. [lastAppPost]: the app last posted anything; [lastMatch]: this recipe last drew from one of
 * its notifications; [missesRunning]: matched notifications in a row that lacked a required field;
 * [shape]: the fingerprint of the last matched notification's shape ([Shapes.of]), [shapeChangedAt]
 * when it last changed (a warning only).
 */
@Serializable
data class RecipeHealth(
    val firstSeen: Long? = null,
    val lastAppPost: Long = 0,
    val lastMatch: Long = 0,
    val missesRunning: Int = 0,
    val lastMissing: List<String> = emptyList(),
    val shape: String? = null,
    val shapeChangedAt: Long = 0,
    /** The key of the last notification counted, so one notification shown many times counts once. */
    val lastKey: String? = null,
    val lastPosted: Long = 0,
)

/** What tile info says about a recipe ([HealthWatch.status]). */
enum class HealthStatus {
    /** Drawing as written. */
    Well,
    /** The app's version is outside the recipe's range: still drawing, "app updated, checking". */
    Probation,
    /** A required field missing [Recipes.UNWELL_AFTER] times running: the plain app tile, "needs update". */
    Unwell,
    /** The recipe matched here before; the app keeps posting useful notifications, but nothing matched for its stale days: "needs update". */
    NeedsUpdate,
}

object HealthWatch {
    private const val DAY = 86_400_000L

    /**
     * [h] after [e]: a new matched notification counts its required fields once (by key and post
     * time); one the app has up that didn't match moves [RecipeHealth.lastAppPost].
     */
    fun record(h: RecipeHealth, e: Evaluation, now: Long): RecipeHealth {
        var out = if (h.firstSeen == null) h.copy(firstSeen = now) else h
        // The app has something up this recipe didn't take: it's posting, whether or not we match.
        if (e.unmatched > 0) out = out.copy(lastAppPost = now)
        val w = e.winner ?: return out
        if (e.missingRequired.isEmpty()) out = out.copy(lastMatch = now)
        // Misses count once per notification (by key and post time), however often it's looked at.
        if (w.key == out.lastKey && w.posted == out.lastPosted) return out
        val shape = Shapes.of(w)
        out = out.copy(
            lastKey = w.key, lastPosted = w.posted,
            shape = shape, shapeChangedAt = if (out.shape != null && out.shape != shape) now else out.shapeChangedAt,
        )
        return if (e.missingRequired.isEmpty()) out.copy(missesRunning = 0, lastMissing = emptyList())
        else out.copy(missesRunning = out.missesRunning + 1, lastMissing = e.missingRequired)
    }

    fun status(h: RecipeHealth, recipe: Recipe, now: Long, probation: Boolean): HealthStatus = when {
        h.missesRunning >= Recipes.UNWELL_AFTER -> HealthStatus.Unwell
        // The app keeps posting (promos and the like don't count: Evaluation.unmatched) and nothing matched in
        // staleDays. A recipe that never matched on this phone has no data yet, not a broken shape.
        h.lastMatch > 0 && h.lastAppPost > 0 && now - h.lastAppPost < recipe.staleDays * DAY &&
            now - h.lastMatch > recipe.staleDays * DAY -> HealthStatus.NeedsUpdate
        probation -> HealthStatus.Probation
        else -> HealthStatus.Well
    }

    /** Tile info's line for [s]; null when well. */
    fun words(s: HealthStatus): String? = when (s) {
        HealthStatus.Well -> null
        HealthStatus.Probation -> "app updated, checking"
        HealthStatus.Unwell, HealthStatus.NeedsUpdate -> "the app changed; this recipe needs an update"
    }
}

/**
 * A notification's shape without its content (like the source probe's): channel, category,
 * template, flags, which fields are present and the action semantics. Changes in it are only a
 * warning: apps add extras all the time.
 */
object Shapes {
    fun of(n: NotificationSample): String = buildList {
        add("c=${n.channel}"); add("k=${n.category}"); add("t=${n.template}")
        if (n.ongoing) add("ongoing"); if (n.summary) add("summary"); if (n.promoted) add("promoted")
        listOf("title" to n.title, "text" to n.text, "bigText" to n.bigText, "subText" to n.subText, "infoText" to n.infoText,
            "summaryText" to n.summaryText, "conversationTitle" to n.conversationTitle, "shortCritical" to n.shortCritical)
            .filter { it.second != null }.forEach { add(it.first) }
        if (n.lines.isNotEmpty()) add("lines"); if (n.messages.isNotEmpty()) add("messages")
        if ((n.max ?: 0) > 0) add("progress")
        n.actions.mapNotNull { it.semantic }.sorted().forEach { add("a=$it") }
        n.extras.keys.sorted().forEach { add("x=$it") }
    }.joinToString(";").let { s -> "%08x".format(s.hashCode()) }
}
