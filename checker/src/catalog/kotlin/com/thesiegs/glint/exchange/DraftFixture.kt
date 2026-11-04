package com.thesiegs.glint.exchange

import com.thesiegs.glint.recipes.Expect
import com.thesiegs.glint.recipes.Fixture
import com.thesiegs.glint.recipes.FixtureSample
import com.thesiegs.glint.recipes.Fixtures
import com.thesiegs.glint.recipes.NotificationSample
import com.thesiegs.glint.recipes.Recipe
import com.thesiegs.glint.recipes.RecipeEngine
import com.thesiegs.glint.recipes.Recipes
import com.thesiegs.glint.recipes.Scrubber
import com.thesiegs.glint.recipes.check.RecipeCheck
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.system.exitProcess

/**
 * The fixture a drafted recipe goes into the catalog with (the intake's nightly drafting): a few of
 * the app's distinct scrubbed shapes, scrubbed again and re-timed, marked synthetic. Each expects
 * only what a later naming edit must not change: the shapes the recipe draws still draw a face, and
 * the ones it leaves alone stay alone. Expectations name the recipe, so another recipe of the same
 * app isn't held to them.
 */
object DraftFixture {
    const val MAX_DRAWN = 6
    const val MAX_LEFT_ALONE = 2

    fun of(recipe: Recipe, samples: List<NotificationSample>, label: String?): Fixture? {
        val pkg = recipe.app.packageName
        val mine = samples.filter { it.packageName.isEmpty() || it.packageName == pkg }
            .sortedWith(compareBy<NotificationSample>({ it.posted }, { it.channel.orEmpty() }, { it.title.orEmpty() }, { it.text.orEmpty() }))
            .distinctBy { listOf(it.channel, it.category, it.template, it.title, it.text, it.bigText, it.ongoing, it.max != null, it.summary) }
        if (mine.isEmpty()) return null
        val shapes = Scrubber.scrub(mine.map { it.copy(packageName = pkg) }, keep = setOfNotNull(label))
        val drawn = mutableListOf<FixtureSample>()
        val alone = mutableListOf<FixtureSample>()
        shapes.forEach { s ->
            val n = s.copy(key = "", packageName = "")
            val ctx = RecipeCheck.contextFor(listOf(s))
            when {
                RecipeEngine.evaluate(recipe, listOf(s), ctx).drew ->
                    if (drawn.size < MAX_DRAWN) drawn += FixtureSample("shape ${drawn.size + 1}", n, Expect(recipe = recipe.id))
                !RecipeEngine.matches(recipe, s, ctx) ->
                    if (alone.size < MAX_LEFT_ALONE) alone += FixtureSample("left alone ${alone.size + 1}", n, Expect(recipe = recipe.id, match = false))
            }
        }
        if (drawn.isEmpty()) return null
        return Fixture(
            packageName = pkg,
            label = label,
            source = "synthetic",
            scrubbed = true,
            notes = "Synthetic: ${drawn.size + alone.size} scrubbed notification shapes, scrubbed again and re-timed by the tile exchange's drafting job.",
            samples = drawn + alone,
        )
    }

    /** [f] as JSON that says outright what it is (`"source"`, `"scrubbed"`), as the catalog's rules want. */
    fun write(f: Fixture): String {
        val body = Recipes.json.encodeToJsonElement(Fixture.serializer(), f) as JsonObject
        val head = mapOf("fixture" to JsonPrimitive(f.fixture), "package" to JsonPrimitive(f.packageName),
            "source" to JsonPrimitive(f.source), "scrubbed" to JsonPrimitive(f.scrubbed))
        return Recipes.json.encodeToString(JsonObject.serializer(), JsonObject(head + body))
    }
}

/**
 * `draftFixture <recipe.json> <samples dir> <out.json>`: writes the drafted recipe's synthetic
 * fixture. Exits 1 when the samples give none (no scrubbed sample of the app draws a face).
 */
fun main(args: Array<String>) {
    if (args.size != 3) {
        System.err.println("usage: draftFixture <recipe.json> <samples dir> <out.json>")
        exitProcess(2)
    }
    val recipe = Recipes.parse(File(args[0]).readText()).recipe ?: run {
        System.err.println("${args[0]}: not a valid recipe")
        exitProcess(2)
    }
    var label: String? = null
    val samples = File(args[1]).walkTopDown().filter { it.isFile && it.extension == "json" }.sortedBy { it.path }.flatMap { f ->
        val fx = runCatching { Fixtures.parse(f.readText()) }.getOrNull()
        if (fx == null || !fx.scrubbed || fx.packageName != recipe.app.packageName) emptyList()
        else { if (label == null) label = fx.label; fx.samples.map { it.notification } }
    }.toList()
    val fixture = DraftFixture.of(recipe, samples, label ?: recipe.app.label) ?: run {
        System.err.println("${recipe.app.packageName}: no scrubbed sample draws a face")
        exitProcess(1)
    }
    File(args[2]).apply { parentFile?.mkdirs() }.writeText(DraftFixture.write(fixture) + "\n")
}
