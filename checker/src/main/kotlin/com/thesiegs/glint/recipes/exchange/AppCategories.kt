package com.thesiegs.glint.recipes.exchange

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * One app in the catalog's categories file: its [category] (one of [AppCategories.ALL]), its display
 * [name], and which of Glint's glances its tile borrows ([glance], one of [AppCategories.GLANCES]).
 */
data class CatalogApp(val category: String?, val name: String?, val glance: String? = null, val kind: String? = null)

/**
 * The tile exchange's app categories (format glint.categories/1, the catalog's README "App
 * categories"): `{"format": ..., "apps": {"<package>": {"category": "game", "name": "Roblox"}}}`,
 * trusted through the signed index's `categories` entry ([ExchangeFile]). An entry may also say which
 * glance the app's tile borrows (`"glance": "photos"`); older Glint ignores that key. Tolerant: a value
 * may also be a plain category string; an unknown category or glance, an odd package or an odd value
 * is skipped, never fatal.
 */
object AppCategories {
    const val FORMAT = "glint.categories/1"

    /** Hearth HA's app categories plus messaging: the only ones kept. */
    val ALL = setOf("game", "video", "audio", "image", "social", "news", "maps", "productivity", "accessibility", "messaging")

    /**
     * The glances an app may borrow (owner 2026-11-09): Glint's photos, music, calendar, clock and
     * weather glances; `car`, reserved for a car glance Glint doesn't have yet; `calls`, a calling app
     * (owner 2026-11-10, Google Voice the first) whose tile wears voicemail, missed call and text faces
     * and whose voicemail Arrivals deals, its glance its own items'; `none`, the app's own glance,
     * never a borrowed one.
     */
    val GLANCES = setOf("photos", "music", "calendar", "clock", "weather", "car", "calls", "none")

    /**
     * The kinds an app may be (review 2026-11-10 §2.3, owner 2026-11-10): what kind recipes and the
     * Car tile key on instead of hand lists of packages. `charging`: a charger network's app (its
     * session fuses into the Car tile's). `assistant`: an AI assistant or bot app (Claude, ChatGPT,
     * Gemini): it may post chat-style notifications, but no person writes in it ([com.thesiegs.glint.recipes.AppKinds.isAssistant]).
     */
    val KINDS = setOf("car", "charging", "airline", "delivery", "ride", "assistant")

    private const val MAX_APPS = 50_000
    private const val NAME_MAX = 60
    private val Package = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")
    private val json = Json { ignoreUnknownKeys = true }

    /** The apps in [text], or null when it isn't a glint.categories/1 file. */
    fun parse(text: String): Map<String, CatalogApp>? {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        if ((root["format"] as? JsonPrimitive)?.contentOrNull != FORMAT) return null
        val apps = root["apps"] as? JsonObject ?: return null
        val out = HashMap<String, CatalogApp>()
        for ((pkg, v) in apps) {
            if (out.size >= MAX_APPS) break
            if (pkg.length > 255 || !Package.matches(pkg)) continue
            val app = when (v) {
                is JsonPrimitive -> CatalogApp(v.contentOrNull?.takeIf { v.isString && it in ALL }, null)
                is JsonObject -> CatalogApp(
                    (v["category"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in ALL },
                    (v["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.take(NAME_MAX)?.takeIf { it.isNotEmpty() },
                    (v["glance"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in GLANCES },
                    (v["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in KINDS },
                )
                else -> null
            } ?: continue
            if (app.category != null || app.name != null || app.glance != null || app.kind != null) out[pkg] = app
        }
        return out
    }
}
