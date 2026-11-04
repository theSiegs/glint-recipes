package com.thesiegs.glint.recipes.learn

import com.thesiegs.glint.recipes.Fixtures
import com.thesiegs.glint.recipes.NotificationSample
import com.thesiegs.glint.recipes.Recipe
import com.thesiegs.glint.recipes.Recipes
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import kotlin.system.exitProcess

/**
 * The shape learner, headless (tile exchange CI): reads every fixture file (recipes/SPEC.md §8.1,
 * scrubbed samples only) in a folder, learns one recipe per app, and prints it. Pure JVM, no Android.
 */
object LearnCli {
    data class Result(val learned: List<Recipe>, val skipped: Map<String, String>)

    /** Every app's samples in [dir]'s fixture files (any depth), learned in package order. */
    fun run(dir: File, lang: String = "en"): Result {
        val byApp = LinkedHashMap<String, MutableList<NotificationSample>>()
        val labels = HashMap<String, String>()
        val skipped = LinkedHashMap<String, String>()
        dir.walkTopDown().filter { it.isFile && it.extension == "json" }.sortedBy { it.path }.forEach { f ->
            val fx = runCatching { Fixtures.parse(f.readText()) }.getOrElse {
                skipped[f.name] = "not a fixture (${it.message?.lineSequence()?.firstOrNull()})"; return@forEach
            }
            if (!fx.scrubbed) { skipped[f.name] = "not scrubbed: never learned from"; return@forEach }
            if (fx.packageName.isEmpty()) { skipped[f.name] = "no \"package\""; return@forEach }
            byApp.getOrPut(fx.packageName) { mutableListOf() } += fx.samples.map { it.notification }
            fx.label?.let { labels.putIfAbsent(fx.packageName, it) }
        }
        val learned = byApp.keys.sorted().mapNotNull { pkg ->
            val a = ShapeLearner.attempt(pkg, byApp.getValue(pkg), labels[pkg], lang)
            if (a.recipe == null) skipped[pkg] = a.why
            a.recipe
        }
        return Result(learned, skipped)
    }
}

/**
 * `learnRecipe <fixtures dir> [lang] [--out=<dir>]`: prints the learned recipe (one app) or a JSON
 * array of them (several), and with `--out` also writes each as `<id>.json` there. Why an app taught
 * nothing goes to stderr. Exits 1 when nothing was learned.
 */
fun main(args: Array<String>) {
    val out = args.firstOrNull { it.startsWith("--out=") }?.removePrefix("--out=")?.let(::File)
    val plain = args.filterNot { it.startsWith("--") }
    val dir = File(plain.firstOrNull() ?: ".")
    if (!dir.isDirectory) {
        System.err.println("Not a folder: ${dir.path}")
        exitProcess(2)
    }
    val result = LearnCli.run(dir, plain.getOrNull(1) ?: "en")
    result.skipped.forEach { (what, why) -> System.err.println("$what: $why") }
    when (result.learned.size) {
        0 -> exitProcess(1)
        1 -> println(Recipes.write(result.learned.single()))
        else -> println(Recipes.json.encodeToString(ListSerializer(Recipe.serializer()), result.learned))
    }
    out?.let { o ->
        o.mkdirs()
        result.learned.forEach { r -> o.resolve("${r.id}.json").writeText(Recipes.write(r)) }
    }
}
