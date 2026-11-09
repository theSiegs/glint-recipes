package com.thesiegs.glint.recipes.edit

import com.thesiegs.glint.recipes.FaceKind
import com.thesiegs.glint.recipes.FaceRule
import com.thesiegs.glint.recipes.Recipe
import com.thesiegs.glint.recipes.Recipes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * One face of an owner's edit of a live tile (tile settings › "edit what it shows", owner
 * 2026-11-06): which of the recipe's faces it is ([from], its index there), whether it is [on], and
 * what its two lines show. [top] and [under] are null for "as the recipe wrote it", [RecipeEdits.NOTHING]
 * for an empty line under (headline and progress faces only), else the name of one of the recipe's
 * fields ([RecipeEdits.choices]). Never free text: an edit only rearranges what the recipe already has.
 */
@Serializable
data class FaceEdit(
    val from: Int,
    val on: Boolean = true,
    val top: String? = null,
    val under: String? = null,
)

/**
 * An owner's edit of a recipe: its faces in their new order, the ladder top first (the first that
 * draws is the rest face, the next ones its fallbacks). Pure, so tested.
 */
@Serializable
data class RecipeEdit(val faces: List<FaceEdit>) {
    /** Face [at] one place up the ladder. */
    fun moveUp(at: Int): RecipeEdit = if (at <= 0 || at >= faces.size) this else
        copy(faces = faces.toMutableList().also { it[at - 1] = faces[at]; it[at] = faces[at - 1] })

    /** Face [at] one place down the ladder. */
    fun moveDown(at: Int): RecipeEdit = if (at < 0 || at >= faces.size - 1) this else moveUp(at + 1)

    fun setOn(at: Int, on: Boolean): RecipeEdit = change(at) { it.copy(on = on) }
    fun setTop(at: Int, field: String?): RecipeEdit = change(at) { it.copy(top = field) }
    fun setUnder(at: Int, field: String?): RecipeEdit = change(at) { it.copy(under = field) }

    private fun change(at: Int, f: (FaceEdit) -> FaceEdit) =
        if (at !in faces.indices) this else copy(faces = faces.toMutableList().also { it[at] = f(it[at]) })

    /** Whether it changes nothing: every face on, in the recipe's order, as written. */
    val isPlain: Boolean get() = faces.withIndex().all { (i, f) -> f.from == i && f.on && f.top == null && f.under == null }

    companion object {
        /** The edit that changes nothing: [r]'s faces as they are. */
        fun of(r: Recipe): RecipeEdit = RecipeEdit(r.faces.indices.map { FaceEdit(it) })
    }
}

/**
 * The pure half of editing a live tile: what each face's lines may show, the recipe an edit makes,
 * why an edit can't be kept, and the file it is kept in (files/recipes/edits/<id>.json:
 * [ENVELOPE] with the recipe it was made from, so the editor reopens on the same choices and a newer
 * catalog rev can be told apart from it).
 */
object RecipeEdits {
    const val ENVELOPE = "glint.recipe-edit/1"
    /** [FaceEdit.under]'s "nothing under it". */
    const val NOTHING = "-"

    /** Built-in fields worth a line of their own (text; not raw times or numbers). */
    val TextBuiltIns = listOf("title", "text", "bigText", "subText")

    /** The fields a line may show: the recipe's own (in its order), then the notification's own text. */
    fun choices(r: Recipe): List<String> = (r.fields.keys.toList() + TextBuiltIns).distinct()

    /** What the face's top line is called on [kind]: the title, or a message face's person; null where it can't change (a count). */
    fun topSlot(kind: FaceKind): String? = when (kind) {
        FaceKind.Headline, FaceKind.Progress -> "title"
        FaceKind.Message -> "person"
        FaceKind.Count -> null
    }

    /** What the line under it is called; null on a count face (its word stays the recipe's). */
    fun underSlot(kind: FaceKind): String? = if (kind == FaceKind.Count) null else "line"

    /** Whether the line under may be left empty: a message face always says something. */
    fun underMayBeEmpty(kind: FaceKind): Boolean = kind == FaceKind.Headline || kind == FaceKind.Progress

    /** [f]'s top line template as written. */
    fun topOf(f: FaceRule): String? = when (f.kind) {
        FaceKind.Headline, FaceKind.Progress -> f.title
        FaceKind.Message -> f.person
        FaceKind.Count -> f.count
    }

    /** [f]'s line under as written. */
    fun underOf(f: FaceRule): String? = if (f.kind == FaceKind.Count) f.label else f.line

    /** [f] with [e]'s lines: a field becomes `{field}`, [NOTHING] an empty line, null leaves it. */
    fun face(f: FaceRule, e: FaceEdit, fields: Set<String>): FaceRule {
        fun pick(choice: String?, written: String?, mayBeEmpty: Boolean): String? = when {
            choice == null -> written
            choice == NOTHING -> if (mayBeEmpty) null else written
            choice in fields -> "{$choice}"
            else -> written
        }
        return when (f.kind) {
            FaceKind.Headline, FaceKind.Progress -> f.copy(
                title = pick(e.top, f.title, false),
                line = pick(e.under, f.line, true),
            )
            FaceKind.Message -> f.copy(person = pick(e.top, f.person, false), line = pick(e.under, f.line, false))
            FaceKind.Count -> f
        }
    }

    /** The recipe [e] makes of [base]: its faces that are on, in the edit's order, with their lines. Everything else stays. */
    fun apply(base: Recipe, e: RecipeEdit): Recipe {
        val fields = choices(base).toSet()
        val faces = e.faces.filter { it.on }.mapNotNull { fe -> base.faces.getOrNull(fe.from)?.let { face(it, fe, fields) } }
        return base.copy(faces = faces)
    }

    /**
     * Why [e] can't be kept for [base] (empty: it can): it names a face [base] hasn't, or one twice, or
     * a field it hasn't; it leaves no face that draws on its own (one on, not a flip, not opt-in); or
     * the recipe it makes fails the schema.
     */
    fun problems(base: Recipe, e: RecipeEdit): List<String> = buildList {
        val fields = choices(base).toSet()
        if (e.faces.any { it.from !in base.faces.indices }) add("a face the tile doesn't have")
        if (e.faces.map { it.from }.toSet().size != e.faces.size) add("a face twice")
        e.faces.forEach { f ->
            listOfNotNull(f.top, f.under).filter { it != NOTHING }.forEach { if (it !in fields) add("\"$it\" isn't one of its fields") }
        }
        if (e.faces.none { fe -> fe.on && base.faces.getOrNull(fe.from)?.let { !it.flip && !it.optIn } == true }) {
            add("keep one face on that isn't a flip")
        }
        if (isEmpty()) Recipes.validate(apply(base, e)).filter { it.error }.forEach { add(it.toString()) }
    }

    /** The file an edit is kept in: the envelope, the edit, and the recipe it was made from. */
    fun encode(base: Recipe, e: RecipeEdit): String {
        val baseJson = Recipes.json.parseToJsonElement(Recipes.write(base))
        val edit = Recipes.json.encodeToJsonElement(RecipeEdit.serializer(), e).jsonObject
        val obj = JsonObject(mapOf("edit" to JsonPrimitive(ENVELOPE)) + edit + mapOf("base" to baseJson))
        return Recipes.json.encodeToString(JsonObject.serializer(), obj)
    }

    /** A kept edit: the recipe it was made from and the edit. */
    data class Kept(val base: Recipe, val edit: RecipeEdit) {
        /** The recipe the tile uses. */
        val recipe: Recipe get() = apply(base, edit)
    }

    /** Reads a kept edit; null when it isn't one, its recipe doesn't load, or the edit no longer fits it. */
    fun decode(text: String): Kept? = runCatching {
        val obj = Recipes.json.parseToJsonElement(text).jsonObject
        if ((obj["edit"] as? JsonPrimitive)?.contentOrNull != ENVELOPE) return null
        val p = Recipes.parse(obj["base"] ?: return null)
        val base = p.recipe?.takeIf { p.ok } ?: return null
        val edit = Recipes.json.decodeFromJsonElement(RecipeEdit.serializer(), JsonObject(obj - "edit" - "base"))
        if (problems(base, edit).isNotEmpty()) return null
        Kept(base, edit)
    }.getOrNull()

    /**
     * What an edit changed, in the recipe's own words (face numbers and field names only, never a
     * value): "faces 2, 1; face 2 off; face 1: title {note}". Empty when it changes nothing.
     */
    fun summary(base: Recipe, e: RecipeEdit): String {
        val parts = mutableListOf<String>()
        val on = e.faces.filter { it.on }.map { it.from }
        if (on != on.sorted()) {
            parts += "faces " + on.joinToString(", ") { "${it + 1}" }
        }
        e.faces.filter { !it.on }.forEach { parts += "face ${it.from + 1} off" }
        e.faces.filter { it.on && (it.top != null || it.under != null) }.forEach { f ->
            val kind = base.faces.getOrNull(f.from)?.kind ?: return@forEach
            val lines = listOfNotNull(
                f.top?.let { "${topSlot(kind)} {$it}" },
                f.under?.let { if (it == NOTHING) "no ${underSlot(kind)}" else "${underSlot(kind)} {$it}" },
            )
            if (lines.isNotEmpty()) parts += "face ${f.from + 1}: " + lines.joinToString(", ")
        }
        return parts.joinToString("; ")
    }

    /** Every string in [e] (keys and values), for checking an edit adds nothing of its own. */
    internal fun strings(e: JsonElement, out: MutableSet<String> = HashSet()): Set<String> {
        when (e) {
            is JsonObject -> e.forEach { (k, v) -> out += k; strings(v, out) }
            is kotlinx.serialization.json.JsonArray -> e.forEach { strings(it, out) }
            is JsonPrimitive -> if (e.isString) out += e.content
        }
        return out
    }
}
