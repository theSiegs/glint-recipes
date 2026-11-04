package com.thesiegs.glint.exchange

import com.thesiegs.glint.recipes.Fixtures
import com.thesiegs.glint.recipes.Recipes
import com.thesiegs.glint.recipes.Sensitive
import com.thesiegs.glint.recipes.check.RecipeCheck
import java.io.File
import kotlin.system.exitProcess

/**
 * The tile exchange catalog's checker: Glint's own checker (vendored in src/main, see VERSION) over
 * recipes/<id>.json and fixtures/<package>/, plus the catalog's rules on top:
 *  - never a recipe or a fixture for a chat app (Recipes.PeopleApps) or a health or payment app
 *    (Sensitive.isSensitive);
 *  - never the generic recipe (it is built into Glint);
 *  - every recipe has fixtures, and every fixture says outright that it is scrubbed or synthetic
 *    (`"scrubbed": true` or `"synthetic": true`); the vendored checker already refuses fixtures
 *    that look unscrubbed (emails, phone numbers, links);
 *  - revoked.txt holds one `id` or `id@rev` per line.
 *
 *   checker [recipes dir] [fixtures dir] [revoked.txt]   (defaults: ../recipes ../fixtures ../revoked.txt)
 */
object CatalogCheck {
    private val IdOrIdRev = Regex("^[a-z0-9][a-z0-9-]{0,63}(@[1-9][0-9]*)?$")
    private val Declared = Regex("\"(scrubbed|synthetic)\"\\s*:\\s*true")

    /** Why [packageName] may never be in the catalog, or null. */
    fun refused(packageName: String): String? = when {
        packageName in Recipes.PeopleApps -> "$packageName is a chat app: its conversations stay on People tiles"
        Sensitive.isSensitive(packageName) -> "$packageName is a health or payment app: never in the tile exchange"
        else -> null
    }

    fun extraProblems(recipeFiles: List<File>, fixturesDir: File?, revoked: File?): List<String> = buildList {
        val fixturePackages = mutableSetOf<String>()
        fixturesDir?.walkTopDown()?.filter { it.isFile && it.extension == "json" }?.sortedBy { it.path }?.forEach { f ->
            val path = f.relativeTo(fixturesDir).path.replace('\\', '/')
            val text = f.readText()
            if (!Declared.containsMatchIn(text)) add("fixture $path: must say \"scrubbed\": true or \"synthetic\": true")
            val pkg = runCatching { Fixtures.parse(text).packageName }.getOrNull() ?: return@forEach
            fixturePackages += pkg
            refused(pkg)?.let { add("fixture $path: $it") }
        }
        recipeFiles.forEach { f ->
            val recipe = Recipes.parse(f.readText()).recipe ?: return@forEach
            val pkg = recipe.app.packageName
            if (recipe.isGeneric || pkg == Recipes.ANY_APP) add("${f.name}: the generic recipe is built into Glint, not the catalog")
            refused(pkg)?.let { add("${f.name}: $it") }
            if (!recipe.isGeneric && pkg !in fixturePackages) add("${f.name}: no fixtures in fixtures/$pkg/ (the catalog needs at least one)")
            if (f.name != "${recipe.id}.json") add("${f.name}: a catalog file is named after its id: ${recipe.id}.json")
        }
        revoked?.takeIf { it.isFile }?.readLines()?.forEachIndexed { i, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isNotEmpty() && !IdOrIdRev.matches(line)) add("revoked.txt line ${i + 1}: \"$line\" is not an id or id@rev")
        }
    }
}

fun main(args: Array<String>) {
    val recipesDir = File(args.getOrNull(0) ?: "../recipes")
    val fixturesDir = File(args.getOrNull(1) ?: "../fixtures").takeIf { it.isDirectory }
    val revoked = File(args.getOrNull(2) ?: "../revoked.txt")
    val files = recipesDir.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }.sortedBy { it.name }
    if (files.isEmpty()) {
        println("No recipes in ${recipesDir.path}")
        exitProcess(1)
    }
    val report = RecipeCheck.run(files, fixturesDir)
    RecipeCheck.print(report)
    val extra = CatalogCheck.extraProblems(files, fixturesDir, revoked)
    extra.forEach { println("✗ catalog: $it") }
    if (!report.ok || extra.isNotEmpty()) {
        println("catalog check failed")
        exitProcess(1)
    }
    println("catalog ok: ${files.size} recipes")
}
