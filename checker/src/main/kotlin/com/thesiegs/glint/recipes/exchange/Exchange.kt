package com.thesiegs.glint.recipes.exchange

import com.thesiegs.glint.recipes.Recipe
import com.thesiegs.glint.recipes.Recipes
import com.thesiegs.glint.recipes.Sensitive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Base64

/**
 * The tile exchange's signed index (format glint.exchange/1): which catalog recipe each package
 * has, and which recipes are revoked. `index.json` is signed with Ed25519 (`index.json.sig`, the
 * signature in base64); each `r/<id>.<rev>.json` is trusted only when its SHA-256 is the index's.
 * Unknown keys are ignored, so the format can grow.
 */
@Serializable
data class ExchangeIndex(
    val format: String = "",
    val seq: Long = 0,
    val issued: String = "",
    val apps: Map<String, ExchangeEntry> = emptyMap(),
    val revoked: List<String> = emptyList(),
    /** The app categories file ([AppCategories]); an older index has none. */
    val categories: ExchangeFile? = null,
) {
    /** Whether recipe [id] (or its [rev], when given) is revoked: an "id" entry revokes every rev, "id@rev" just that one. */
    fun isRevoked(id: String, rev: Int? = null): Boolean =
        revoked.any { it == id || (rev != null && it == "$id@$rev") }

    /** The packages the catalog has a usable live tile for: not revoked, never a chat, health or payment app. */
    fun covered(): Set<String> = apps.keys.filterTo(LinkedHashSet()) { entryFor(it) != null }

    /** The [covered] packages whose live tile is checked ([Exchange.ready]): the only ones Glint deals. */
    fun dealable(): Set<String> = covered().filterTo(LinkedHashSet()) { Exchange.ready(apps[it]?.status) }

    /** [packageName]'s entry, when the catalog has one it may use. */
    fun entryFor(packageName: String): ExchangeEntry? =
        apps[packageName]?.takeIf { Exchange.offerable(packageName) && !isRevoked(it.id, it.rev) && CachedFile.of(it.name) != null }
}

@Serializable
data class ExchangeEntry(
    val id: String,
    val rev: Int,
    val sha256: String,
    /** The recipe's `meta.status` ("verified", "built-in", "unverified", "learned"); an older index has none. */
    val status: String? = null,
) {
    /** The file's name, `<id>.<rev>.json` (an id that would make an unsafe name isn't used, [ExchangeIndex.entryFor]). */
    val name: String get() = "$id.$rev.json"
    /** The file's path under the catalog's base URL. */
    val path: String get() = "r/$name"
}

/** A file the index vouches for by its SHA-256 (the app categories, `c/<hash>.json`). */
@Serializable
data class ExchangeFile(
    val path: String,
    val sha256: String,
    val count: Int = 0,
) {
    /** Whether [path] is one the catalog makes (`c/` and hex): never a path that leaves the base URL. */
    val safe: Boolean get() = SafePath.matches(path) && Hex.matches(sha256)

    private companion object {
        val SafePath = Regex("""^c/[0-9a-f]{16,64}\.json$""")
        val Hex = Regex("""^[0-9a-fA-F]{64}$""")
    }
}

/** A key Glint trusts: its name ("exchange-1") and the raw 32-byte Ed25519 public key. */
class ExchangeKey(val id: String, val key: ByteArray) {
    companion object {
        fun of(id: String, base64: String) = ExchangeKey(id, Base64.getDecoder().decode(base64.trim()))
    }
}

/** What [Exchange.accept] made of a downloaded index. */
sealed interface Verdict {
    data class Accepted(val index: ExchangeIndex, val keyId: String) : Verdict
    data class Rejected(val reason: String) : Verdict
}

/** A cached recipe file: its recipe id and rev, by its name `<id>.<rev>.json`. */
data class CachedFile(val id: String, val rev: Int) {
    val name: String get() = "$id.$rev.json"

    companion object {
        private val Name = Regex("""^([a-z0-9][a-z0-9._-]*)\.([0-9]+)\.json$""")
        fun of(name: String): CachedFile? = Name.find(name)?.let { m -> m.groupValues[2].toIntOrNull()?.let { CachedFile(m.groupValues[1], it) } }
    }
}

object Exchange {
    const val FORMAT = "glint.exchange/1"
    const val INDEX = "index.json"
    const val SIG = "index.json.sig"
    const val DEFAULT_BASE = "https://thesiegs.github.io/glint-recipes/"

    /** The keys Glint ships (exchange-1 now, exchange-2 for rotation): an index signed by either is accepted. */
    val Keys: List<ExchangeKey> = listOf(
        ExchangeKey.of("exchange-1", "RPjFgOTCSoJHIN6mdve1eM1ehgYSuBrketbP7IloApo="),
        ExchangeKey.of("exchange-2", "U4OXyesZZJworjpLe+IEYphvnMPZxriY5Wu8spy7GfQ="),
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = false; coerceInputValues = true }

    /** The index in [text], or null when it isn't one (unknown keys are fine; a wrong format isn't). */
    fun parse(text: String): ExchangeIndex? = runCatching { json.decodeFromString(ExchangeIndex.serializer(), text) }
        .getOrNull()?.takeIf { it.format == FORMAT }

    /** The key in [keys] whose signature [sigText] (base64) is over [indexBytes]; null when none. */
    fun signer(indexBytes: ByteArray, sigText: String, keys: List<ExchangeKey> = Keys): ExchangeKey? {
        val sig = runCatching { Base64.getDecoder().decode(sigText.trim()) }.getOrNull() ?: return null
        return keys.firstOrNull { Ed25519.verify(it.key, indexBytes, sig) }
    }

    /**
     * A downloaded index, checked: signed by one of [keys], a glint.exchange/1 index, and no older
     * than the one cached ([cachedSeq]; a lower seq is a rollback and is refused, an equal one is the same index).
     */
    fun accept(indexBytes: ByteArray, sigText: String, keys: List<ExchangeKey> = Keys, cachedSeq: Long? = null): Verdict {
        val key = signer(indexBytes, sigText, keys) ?: return Verdict.Rejected("bad signature")
        val index = parse(String(indexBytes, Charsets.UTF_8)) ?: return Verdict.Rejected("not a $FORMAT index")
        if (cachedSeq != null && index.seq < cachedSeq) return Verdict.Rejected("rollback: seq ${index.seq} < $cachedSeq")
        return Verdict.Accepted(index, key.id)
    }

    /** Whether [bytes] are the file [entry] names: its SHA-256 matches. */
    fun matches(entry: ExchangeEntry, bytes: ByteArray): Boolean = sha256(bytes).equals(entry.sha256.trim(), ignoreCase = true)

    /**
     * Statuses checked against a real app's notifications (owner-written from real samples, or
     * Google's built-in set): the only catalog live tiles Glint deals or turns on by itself.
     */
    val Ready = setOf("verified", "built-in")

    fun ready(status: String?): Boolean = status in Ready

    /**
     * A catalog recipe as the book holds it: a checked one as it is; an unchecked one (a guessed
     * "unverified" recipe or a drafted "learned" one) off, so no app tile turns live with it and
     * the dealer never offers it. It can still be tried on purpose (placed by its id), and a tile
     * that doesn't draw is the plain app tile.
     */
    fun asHeld(r: Recipe): Recipe = if (ready(r.meta.status) || !r.enabled) r else r.copy(enabled = false)

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Whether the exchange may deal or fetch a live tile for [packageName]: never a chat, health or payment app. */
    fun offerable(packageName: String): Boolean = packageName !in Recipes.PeopleApps && !Sensitive.isSensitive(packageName)

    /**
     * The packages to fetch now: those with a tile on Start ([onStart]) the index covers, whose
     * cached file ([cached], package to the rev held) is missing or older than the index's rev.
     */
    fun toFetch(index: ExchangeIndex, onStart: Set<String>, cached: Map<String, Int>): Set<String> =
        onStart.filterTo(LinkedHashSet()) { pkg ->
            val e = index.entryFor(pkg) ?: return@filterTo false
            (cached[pkg] ?: -1) < e.rev
        }

    /** The cached files to delete: revoked by id or id@rev. */
    fun toDrop(index: ExchangeIndex, files: List<CachedFile>): List<CachedFile> = files.filter { index.isRevoked(it.id, it.rev) }
}
