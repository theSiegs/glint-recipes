package com.thesiegs.glint.recipes

/**
 * Every recipe Glint knows, bundled and imported (recipes/SPEC.md §9): one per id, the highest
 * [Recipe.rev] winning and an imported one winning a tie. [best] is the recipe a package's tile uses
 * when it names none: its own enabled recipe, else [generic].
 *
 * [learned] is the third tier (tile exchange, tier 2: [com.thesiegs.glint.recipes.learn.ShapeLearner]),
 * the lowest: a learned recipe counts only for a package no bundled or imported recipe (the tile
 * exchange's catalog ones arrive as imported) is written for, enabled or not, and never for a
 * messaging app. The ones that count are in [all] like any other, and in [learnedIds].
 *
 * [edited] are the owner's own edits ([com.thesiegs.glint.recipes.edit.RecipeEdits], tile settings ›
 * "edit what it shows"), the highest: an edit replaces the recipe with its id whatever either's rev,
 * and comes first for its app, so a newer catalog rev never silently takes its place;
 * [newerThanEdit] says when one has come.
 *
 * A recipe's card (§4.3) is kept apart: [cardFor] is the card an app's tile wears by default. A
 * recipe that is a card alone isn't in [all] (it draws no live tile), so it never stands in for the
 * generic or a learned recipe.
 */
class RecipeBook(
    bundled: List<Recipe>,
    imported: List<Recipe> = emptyList(),
    learned: List<Recipe> = emptyList(),
    edited: List<Recipe> = emptyList(),
) {
    val all: List<Recipe>
    val generic: Recipe
    /** The ids of the learned recipes in [all] (those no other recipe outranks). */
    val learnedIds: Set<String>
    /** The ids of the owner's edits in [all]. */
    val editedIds: Set<String>
    /** Each package's recipe as it would be without the owner's edits (the catalog's, bundled or learned one). */
    private val plain: Map<String, List<Recipe>>
    /** Each package's card, from its enabled recipes (the owner's edit first). */
    private val cards: Map<String, RecipeCard>
    /** The recipes that are a card alone (§4.3): no live tile, so not in [all]. */
    val cardsAlone: List<Recipe>

    init {
        val byId = LinkedHashMap<String, Recipe>()
        bundled.forEach { byId[it.id] = it }
        imported.forEach { r -> val old = byId[r.id]; if (old == null || r.rev >= old.rev) byId[r.id] = r }
        val written = byId.values.filter { it.readsNotifications }.mapTo(HashSet()) { it.app.packageName }
        val kept = learned.filter { r ->
            !r.isGeneric && r.id !in byId && r.app.packageName !in written && r.app.packageName !in Recipes.PeopleApps
        }.distinctBy { it.app.packageName }
        kept.forEach { byId[it.id] = it }
        learnedIds = kept.mapTo(HashSet()) { it.id }
        plain = byId.values.filter { !it.isGeneric && it.readsNotifications }.groupBy { it.app.packageName }
        val edits = edited.filter { !it.isGeneric && it.app.packageName !in Recipes.PeopleApps }.distinctBy { it.id }
        edits.forEach { byId[it.id] = it }
        editedIds = edits.mapTo(HashSet()) { it.id }
        cards = byId.values.filter { it.card != null && it.enabled && !it.isGeneric }
            .sortedByDescending { it.id in editedIds }
            .groupBy { it.app.packageName }.mapValues { (_, rs) -> rs.first().card!! }
        all = byId.values.filter { it.readsNotifications || it.isGeneric }
        cardsAlone = byId.values.filter { !it.readsNotifications && !it.isGeneric }
        generic = byId[Recipes.GENERIC_ID] ?: Generic.recipe
    }

    fun isLearned(id: String): Boolean = id in learnedIds

    /** The card [packageName]'s tile wears by default (its enabled recipe's `card`), or null. */
    fun cardFor(packageName: String): RecipeCard? = cards[packageName]

    fun isEdited(id: String): Boolean = id in editedIds

    fun byId(id: String): Recipe? = all.firstOrNull { it.id == id } ?: generic.takeIf { it.id == id }

    /** The recipes written for [packageName] (not the generic one), enabled ones first, the owner's edit first of those. */
    fun forPackage(packageName: String): List<Recipe> =
        all.filter { it.app.packageName == packageName }
            .sortedWith(compareByDescending<Recipe> { it.enabled }.thenByDescending { it.id in editedIds })

    /** The recipe a tile for [packageName] uses: [id] when it names one that exists, else the app's own enabled one, else the generic. */
    fun best(packageName: String, id: String? = null): Recipe {
        id?.let { byId(it) }?.takeIf { it.app.packageName == packageName || it.isGeneric }?.let { return it }
        return forPackage(packageName).firstOrNull { it.enabled } ?: generic
    }

    /** [packageName]'s own enabled recipe as it would be without the owner's edit: what an edit starts from. */
    fun plainFor(packageName: String): Recipe? = plain[packageName].orEmpty().sortedByDescending { it.enabled }.firstOrNull { it.enabled }

    /**
     * The recipe that came after the owner edited [edit] (an edited recipe keeps the rev it was made
     * from): what the app would use without the edit, when that is the same recipe at a higher rev or
     * another one (a catalog recipe for an app whose learned one was edited). Never a learned one.
     * Null when none came.
     */
    fun newerThanEdit(edit: Recipe): Recipe? = plainFor(edit.app.packageName)
        ?.takeIf { it.id !in learnedIds && (it.id != edit.id || it.rev > edit.rev) }

    /** Whether [packageName] may have a recipe tile at all (never a messaging app's). */
    fun offers(packageName: String): Boolean = packageName !in Recipes.PeopleApps
}

/**
 * The generic recipe, used for any app with notifications and no recipe of its own: its newest
 * notification's title as a calm headline, the next ones as flips, and how many are up when there's
 * more than one. Bundled
 * as recipes/bundled/generic.json too; this copy is the fallback when that can't be read.
 */
object Generic {
    val recipe = Recipe(
        id = Recipes.GENERIC_ID,
        name = "app",
        app = RecipeApp(packageName = Recipes.ANY_APP, label = "Any app"),
        meta = RecipeMeta(status = "verified", author = "Glint", notes = "Any app's newest notification, and the count."),
        source = RecipeSource(NotificationSource(match = RecipeMatch(notCategories = listOf("promo", "recommendation", "sys", "status")), fade = "6h")),
        faces = listOf(
            FaceRule(kind = FaceKind.Headline, title = "{title}", line = "{text}", peek = "{posted|ago}", picture = "picture"),
            FaceRule(kind = FaceKind.Headline, title = "{title}", line = "{posted|ago}", picture = "picture"),
            FaceRule(kind = FaceKind.Count, count = "{count}", label = "new", whenAll = listOf("count>1"), flip = true),
        ),
    )
}

/** Loading the bundled recipes, from this module's resources (glint/recipes/index.txt and its files). */
object Bundled {
    const val DIR = "glint/recipes"

    /** Each bundled file, parsed: (file name, result). */
    fun load(loader: ClassLoader = Bundled::class.java.classLoader): List<Pair<String, Parsed>> {
        val index = loader.getResourceAsStream("$DIR/index.txt")?.bufferedReader()?.use { it.readText() } ?: return emptyList()
        return index.lines().map { it.trim() }.filter { it.endsWith(".json") }.mapNotNull { name ->
            val text = loader.getResourceAsStream("$DIR/$name")?.bufferedReader()?.use { it.readText() } ?: return@mapNotNull null
            name to Recipes.parse(text)
        }
    }

    fun recipes(loader: ClassLoader = Bundled::class.java.classLoader): List<Recipe> =
        load(loader).mapNotNull { (_, p) -> p.recipe?.takeIf { p.ok } }
}

/**
 * How well a recipe fits a set of samples (the checker's and the import preview's score): of the
 * samples meant for it, how many it matched and how many gave it a face, and how often each field
 * had a value among the matched ones.
 */
data class Coverage(
    val total: Int,
    val matched: Int,
    val drew: Int,
    val fieldHits: Map<String, Int>,
    /** Samples where a required field was missing. */
    val missingRequired: Int,
) {
    val score: Int get() = if (total == 0) 0 else drew * 100 / total
    fun fieldPercent(name: String): Int = if (matched == 0) 0 else (fieldHits[name] ?: 0) * 100 / matched

    /** "9 of 10 samples drew a face" and the weakest fields. */
    fun summary(): String = if (total == 0) "no samples" else buildString {
        append("$drew of $total samples drew a face ($score%)")
        if (matched < total) append(", $matched matched")
        if (missingRequired > 0) append(", $missingRequired missing a required field")
    }

    companion object {
        /** [recipe] over [sets], each the notifications up at one time; the first of each set's app is the sample. */
        fun of(recipe: Recipe, sets: List<List<NotificationSample>>, ctxFor: (List<NotificationSample>) -> RecipeContext): Coverage {
            var matched = 0; var drew = 0; var missing = 0
            val hits = HashMap<String, Int>()
            sets.forEach { set ->
                val e = RecipeEngine.evaluate(recipe, set, ctxFor(set))
                if (e.winner != null) {
                    matched++
                    recipe.fields.keys.forEach { k -> if (e.values[k] != null) hits[k] = (hits[k] ?: 0) + 1 }
                    if (e.missingRequired.isNotEmpty()) missing++
                }
                if (e.drew) drew++
            }
            return Coverage(sets.size, matched, drew, hits, missing)
        }
    }
}
