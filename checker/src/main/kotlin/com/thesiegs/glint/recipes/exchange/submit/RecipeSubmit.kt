package com.thesiegs.glint.recipes.exchange.submit

import com.thesiegs.glint.recipes.Recipe
import com.thesiegs.glint.recipes.RecipeMeta
import com.thesiegs.glint.recipes.Recipes
import com.thesiegs.glint.recipes.Scrubber
import com.thesiegs.glint.recipes.edit.RecipeEdit
import com.thesiegs.glint.recipes.edit.RecipeEdits
import com.thesiegs.glint.recipes.exchange.Exchange
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * An owner's edit of a live tile, shared through the tile exchange's relay (`glint.recipe-submit/1`,
 * `POST <relay>recipe`, contract on [SubmitClient.sendRecipe]): the edited recipe's JSON and
 * nothing else. Never a notification, a value from one, or a device id: an edit only rearranges the
 * recipe's own fields and faces ([RecipeShare.problems] holds it to that). [rev] is the shared
 * recipe's rev, one above the one it was made from ([basedOn], "id@rev"); [token] and [attestation]
 * are the relay's challenge and the hardware key's chain, empty in the preview.
 */
@Serializable
data class RecipeSubmission(
    val format: String,
    @SerialName("package") val packageName: String,
    val rev: Int,
    val basedOn: String? = null,
    val recipe: JsonObject,
    val token: String,
    val attestation: List<String>,
)

/** Builds a [RecipeSubmission] from an owner's edit, and refuses one that carries anything new. */
object RecipeShare {
    const val FORMAT = "glint.recipe-submit/1"
    /** What the shared recipe's notes start with; the catalog holds such a pull request for the owner's merge. */
    const val NOTE = "Edited on a phone with Glint's tile editor"
    /** The largest recipe that goes. */
    const val MAX_BYTES = 32 shl 10
    private val Date = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /**
     * The recipe that goes: [kept]'s edit applied, one rev up, "unverified" (an edit is no longer what
     * was checked), written by Glint's tile editor on [today] (yyyy-mm-dd), with the edit's
     * [RecipeEdits.summary] in its notes.
     */
    fun recipe(base: Recipe, edit: RecipeEdit, today: String): Recipe {
        val edited = RecipeEdits.apply(base, edit)
        val what = RecipeEdits.summary(base, edit).ifEmpty { "no change" }
        return edited.copy(
            rev = base.rev + 1,
            meta = RecipeMeta(
                status = "unverified",
                author = "Glint tile editor",
                updated = today.takeIf { it.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$")) },
                notes = "$NOTE, from ${base.id} rev ${base.rev}: $what.",
            ),
        )
    }

    /** The submission for [base] edited by [edit], or null when it may not go ([problems]). */
    fun build(base: Recipe, edit: RecipeEdit, today: String): RecipeSubmission? {
        if (RecipeEdits.problems(base, edit).isNotEmpty()) return null
        val r = recipe(base, edit, today)
        val obj = Recipes.json.parseToJsonElement(Recipes.write(r)).jsonObject
        val s = RecipeSubmission(FORMAT, r.app.packageName, r.rev, "${base.id}@${base.rev}", obj, token = "", attestation = emptyList())
        return s.takeIf { problems(base, it).isEmpty() }
    }

    /**
     * Why [s] must not leave the phone (empty: it may): not a recipe that loads; the generic recipe;
     * a chat, health or payment app; package or rev not the recipe's; too large; an email, link or
     * phone number anywhere; or, outside its meta, any string [base] doesn't have but a `{field}`
     * the edit wrote: an edit adds nothing of its own, so nothing a notification said can be in it.
     */
    fun problems(base: Recipe, s: RecipeSubmission): List<String> = buildList {
        if (s.format != FORMAT) add("format ${s.format}")
        val text = Recipes.json.encodeToString(JsonObject.serializer(), s.recipe)
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) add("too large")
        val p = Recipes.parse(s.recipe)
        val r = p.recipe
        if (r == null || !p.ok) { add("not a recipe: ${p.errors.firstOrNull()}"); return@buildList }
        if (r.isGeneric) add("the generic recipe")
        if (!Exchange.offerable(r.app.packageName)) add("a chat, health or payment app")
        if (r.app.packageName != s.packageName || r.app.packageName != base.app.packageName) add("package")
        if (r.id != base.id) add("id")
        if (r.rev != s.rev || r.rev <= base.rev) add("rev")
        val known = RecipeEdits.strings(Recipes.json.parseToJsonElement(Recipes.write(base)))
        val placeholder = Regex("^\\{[A-Za-z][A-Za-z0-9_]*\\}$")
        RecipeEdits.strings(JsonObject(s.recipe - "meta")).forEach { v ->
            if (v !in known && !placeholder.matches(v)) add("a string the recipe didn't have")
        }
        // A date (meta.updated) reads as a phone number to the leak check; nothing else is let off (the relay does the same).
        RecipeEdits.strings(s.recipe).filter { !Date.matches(it) }.forEach { v -> Scrubber.leaks(v).forEach { add(it.substringBefore(' ')) } }
    }.distinct()

    /** The exact bytes sent as `POST <relay>recipe`'s body (and shown before the first share). */
    fun encode(s: RecipeSubmission): String = SubmitCodec.json.encodeToString(RecipeSubmission.serializer(), s)
}
