package com.thesiegs.glint.recipes.check

import com.thesiegs.glint.recipes.Coverage
import com.thesiegs.glint.recipes.Fixture
import com.thesiegs.glint.recipes.FixtureSample
import com.thesiegs.glint.recipes.Fixtures
import com.thesiegs.glint.recipes.NotificationSample
import com.thesiegs.glint.recipes.Problem
import com.thesiegs.glint.recipes.Recipe
import com.thesiegs.glint.recipes.RecipeContext
import com.thesiegs.glint.recipes.RecipeEngine
import com.thesiegs.glint.recipes.Recipes
import com.thesiegs.glint.recipes.Scrubber
import java.io.File
import java.time.ZoneOffset
import kotlin.system.exitProcess

/**
 * The authoring kit's checker (recipes/SPEC.md §8): reads every recipe under `bundled/` and
 * `drafts/` (or the one file given), checks it against the schema, runs it over its app's fixtures
 * in `fixtures/<package>/`, checks each fixture's expectations, and scores its coverage. Fixtures are
 * also checked for strings that look unscrubbed. Pure JVM: `./gradlew :glint-recipes:checkRecipes`.
 */
object RecipeCheck {
    data class RecipeReport(
        val file: String,
        val recipe: Recipe?,
        val problems: List<Problem>,
        val coverage: Coverage?,
        val failures: List<String>,
    ) {
        val ok: Boolean get() = recipe != null && problems.none { it.error } && failures.isEmpty()
    }

    data class Report(val recipes: List<RecipeReport>, val fixtureProblems: List<String>) {
        val ok: Boolean get() = recipes.all { it.ok } && fixtureProblems.isEmpty()
    }

    /** One fixture file, read: its path and contents, or why it couldn't be read. */
    data class FixtureFile(val path: String, val fixture: Fixture?, val error: String? = null)

    /** Checks [recipeFiles] against the fixtures under [fixturesDir]. */
    fun run(recipeFiles: List<File>, fixturesDir: File?): Report {
        val fixtures = fixturesDir?.walkTopDown()?.filter { it.isFile && it.extension == "json" }?.sortedBy { it.path }?.map { f ->
            try {
                FixtureFile(f.relativeTo(fixturesDir).path.replace('\\', '/'), Fixtures.parse(f.readText()))
            } catch (e: Exception) {
                FixtureFile(f.relativeTo(fixturesDir).path.replace('\\', '/'), null, e.message?.lineSequence()?.firstOrNull())
            }
        }?.toList().orEmpty()
        val fixtureProblems = fixtures.flatMap { lint(it, fixturesDir!!) }
        val reports = recipeFiles.map { f -> checkFile(f.name, f.readText(), fixtures) }
        // Two files with one id would shadow each other on the phone.
        val dupes = reports.mapNotNull { it.recipe?.id }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return Report(
            reports.map { r -> if (r.recipe?.id in dupes) r.copy(failures = r.failures + "id \"${r.recipe?.id}\" is used by more than one file") else r },
            fixtureProblems,
        )
    }

    fun checkFile(name: String, text: String, fixtures: List<FixtureFile>): RecipeReport {
        val parsed = Recipes.parse(text)
        val recipe = parsed.recipe ?: return RecipeReport(name, null, parsed.problems, null, emptyList())
        val problems = parsed.problems.toMutableList()
        if (!name.removeSuffix(".json").equals(recipe.id, ignoreCase = false)) {
            problems += Problem("id", "the file is named after the id: ${recipe.id}.json", error = false)
        }
        // A card alone reads no notifications: nothing to run over fixtures, no coverage (§4.3).
        if (!recipe.readsNotifications) return RecipeReport(name, recipe, problems, null, emptyList())
        val mine = fixtures.mapNotNull { it.fixture }.filter { recipe.isGeneric || it.packageName == recipe.app.packageName }
        if (mine.isEmpty() && !recipe.isGeneric) problems += Problem("fixtures", "no fixtures in fixtures/${recipe.app.packageName}/", error = false)
        val failures = mutableListOf<String>()
        val sets = mutableListOf<List<NotificationSample>>()
        mine.forEach { fx ->
            val runs: List<Pair<List<NotificationSample>, FixtureSample?>> =
                if (fx.together) listOf(fx.samples.map { it.notification } to fx.samples.lastOrNull { it.expect != null })
                else fx.samples.map { listOf(it.notification) to it }
            runs.forEach { (set, sample) ->
                val expect = sample?.expect?.takeIf { it.recipe == null || it.recipe == recipe.id }
                // A sample marked for another recipe of the same app doesn't count toward this one's score.
                if (sample?.expect?.recipe != null && sample.expect.recipe != recipe.id) return@forEach
                if (expect?.match != false) sets += set
                if (expect == null || recipe.isGeneric && expect.recipe == null) return@forEach
                val e = RecipeEngine.evaluate(recipe, set, contextFor(set, sample.now))
                val label = "${fx.packageName}/${sample.name ?: "sample"}"
                if (!expect.match) {
                    if (e.drew) failures += "$label: should be left alone, but drew \"${e.faces.first().title}\""
                    return@forEach
                }
                val rest = e.faces.firstOrNull()
                if (rest == null) {
                    failures += "$label: drew no face" + if (e.winner == null) " (didn't match)" else " (missing: ${missingOf(recipe, e.values).joinToString().ifEmpty { "a condition" }})"
                    return@forEach
                }
                expect.kind?.let { if (it != rest.kind) failures += "$label: rest face is ${rest.kind.name.lowercase()}, expected ${it.name.lowercase()}" }
                expect.title?.let { if (it != rest.title) failures += "$label: title \"${rest.title}\", expected \"$it\"" }
                expect.line?.let { if (it != rest.line) failures += "$label: line \"${rest.line}\", expected \"$it\"" }
                expect.peek?.let { if (it != rest.peek) failures += "$label: peek \"${rest.peek}\", expected \"$it\"" }
                expect.faces?.let { if (it != e.faces.size) failures += "$label: ${e.faces.size} faces drew, expected $it" }
                expect.titles?.let { if (it != e.faces.map { f -> f.title }) failures += "$label: faces ${e.faces.map { f -> f.title }}, expected $it" }
                expect.faded?.let { if (it != rest.faded) failures += "$label: rest face ${if (rest.faded) "faded" else "not faded"}, expected ${if (it) "faded" else "not"}" }
                expect.picture?.let { if (it != (rest.picture != null)) failures += "$label: rest face picture ${rest.picture}, expected ${if (it) "one" else "none"}" }
                expect.stops?.let { if (it != rest.stops.size) failures += "$label: ${rest.stops.size} stops, expected $it" }
                expect.points?.let { if (it != rest.points.size) failures += "$label: ${rest.points.size} points, expected $it" }
            }
        }
        val coverage = Coverage.of(recipe, sets) { contextFor(it) }
        return RecipeReport(name, recipe, problems, coverage, failures)
    }

    private fun missingOf(recipe: Recipe, values: Map<String, String>): List<String> =
        recipe.faces.flatMap { f -> listOfNotNull(f.title, f.line, f.peek, f.count, f.label, f.person, f.value, f.max) }
            .flatMap { com.thesiegs.glint.recipes.Templates.fields(it) }.distinct().filter { it !in values }

    /** A fixture is read a minute after its newest sample (or at the sample's own [now]), in English, at UTC, on a 12-hour clock. */
    fun contextFor(set: List<NotificationSample>, now: Long? = null) = RecipeContext(
        now = now ?: ((set.maxOfOrNull { it.posted } ?: 0L) + 60_000L),
        language = "en",
        zone = ZoneOffset.UTC,
    )

    private fun lint(f: FixtureFile, dir: File): List<String> {
        val fx = f.fixture ?: return listOf("${f.path}: not a fixture (${f.error})")
        val out = mutableListOf<String>()
        val folder = f.path.substringBefore('/', "")
        if (fx.packageName.isEmpty()) out += "${f.path}: no \"package\""
        else if (folder.isNotEmpty() && folder != fx.packageName) out += "${f.path}: in fixtures/$folder/ but for ${fx.packageName}"
        if (!fx.scrubbed) out += "${f.path}: \"scrubbed\": false — only scrubbed or synthetic samples go in the repo"
        fx.samples.forEachIndexed { i, s ->
            val n = s.notification
            (listOfNotNull(n.title, n.text, n.bigText, n.subText, n.infoText, n.summaryText, n.conversationTitle, n.shortCritical) +
                n.lines + n.messages.mapNotNull { it.text }).forEach { v ->
                Scrubber.leaks(v).forEach { out += "${f.path}: sample ${s.name ?: i}: looks unscrubbed ($it)" }
            }
        }
        if (dir.path.isEmpty()) out += "no fixtures folder"
        return out
    }

    fun print(r: Report, out: (String) -> Unit = ::println) {
        r.recipes.forEach { rr ->
            val rec = rr.recipe
            if (rec == null) {
                out("✗ ${rr.file}")
            } else {
                val status = rec.meta.status + if (!rec.enabled) ", off by default" else ""
                out("${if (rr.ok) "✓" else "✗"} ${rec.id}  rev ${rec.rev}  (${rec.app.packageName}, $status)")
                rr.coverage?.let { c ->
                    out("    coverage ${c.score}%: ${c.summary()}")
                    if (rec.fields.isNotEmpty() && c.matched > 0) out("    fields: " + rec.fields.keys.joinToString(", ") { "$it ${c.fieldPercent(it)}%" })
                }
                rec.card?.let { c -> out("    card: ${c.look}, tap ${c.shortcut}" + (c.color?.let { ", color $it" } ?: "") + if (!rec.readsNotifications) " (a card alone)" else "") }
            }
            rr.problems.forEach { out("    $it") }
            rr.failures.forEach { out("    fail: $it") }
        }
        r.fixtureProblems.forEach { out("✗ fixture $it") }
        val bad = r.recipes.count { !it.ok }
        out(if (r.ok) "${r.recipes.size} recipes ok" else "$bad of ${r.recipes.size} recipes failed" + if (r.fixtureProblems.isNotEmpty()) ", ${r.fixtureProblems.size} fixture problems" else "")
    }

    /** The recipes a root holds: the JSON files in bundled/ and drafts/. */
    fun recipeFiles(root: File): List<File> =
        listOf("bundled", "drafts").flatMap { d -> root.resolve(d).listFiles().orEmpty().filter { it.isFile && it.extension == "json" }.sortedBy { it.name } }
}

/**
 * `checkRecipes [root|recipe.json ...]`: with a folder, every recipe in it (bundled/ and drafts/)
 * with fixtures/; with files, those recipes, with the fixtures folder found next to them (or in the
 * current folder). Exits 1 when anything fails.
 */
fun main(args: Array<String>) {
    val paths = args.map { File(it) }.ifEmpty { listOf(File(".")) }
    val files = paths.flatMap { p -> if (p.isDirectory) RecipeCheck.recipeFiles(p) else listOf(p) }
    val fixtures = paths.firstNotNullOfOrNull { p ->
        generateSequence(if (p.isDirectory) p.absoluteFile else p.absoluteFile.parentFile) { it.parentFile }.take(3)
            .map { it.resolve("fixtures") }.firstOrNull { it.isDirectory }
    } ?: File("fixtures").takeIf { it.isDirectory }
    if (files.isEmpty()) {
        println("No recipes found in ${paths.joinToString()}")
        exitProcess(1)
    }
    val report = RecipeCheck.run(files, fixtures)
    RecipeCheck.print(report)
    if (!report.ok) exitProcess(1)
}
