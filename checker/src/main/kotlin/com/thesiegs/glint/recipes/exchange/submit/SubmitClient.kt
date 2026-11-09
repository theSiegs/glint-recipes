package com.thesiegs.glint.recipes.exchange.submit

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * The hardware key a submission is signed with. On the phone an EC P-256 key in AndroidKeyStore,
 * made once, its attestation challenge the first relay token it was asked for; in tests a fake.
 */
interface Attestor {
    /** The key's certificate chain, leaf first, DER; makes the key (once) with [challenge] as its attestation challenge. */
    fun chain(challenge: ByteArray): List<ByteArray>

    /** [data] signed with that key: SHA256withECDSA, DER. */
    fun sign(data: ByteArray): ByteArray
}

/**
 * The relay's client (`glint.submit/1`): asks for a one-use token, then posts the submission with
 * that token, the key's chain, and a signature over the exact body bytes in the `Glint-Signature`
 * header. A blank relay URL never touches the network ([Outcome.Off]). Blocking, plain
 * [HttpURLConnection]: the caller runs it off the main thread.
 *
 * The contract the (deferred) relay implements; R is the relay base URL, ending in "/", bodies
 * UTF-8 JSON, unknown keys ignored both ways:
 *
 * 1. `POST R challenge`, body `{"format":"glint.submit/1","package":"<pkg>","rev":<int>}`.
 *    - 200 `{"token":"<16..128 of [A-Za-z0-9_-]>","expires":<epoch s>}`: one use, short lived,
 *      bound to package and rev.
 *    - 429 `{"error":"…","retryAfter":<s>}` and/or `Retry-After: <s>` (the header wins; 3600 when
 *      neither): the phone sends nothing for that long (60 s to 7 days).
 *    - other 4xx `{"error":"…"}`: refused; the try counts, nothing retries that day.
 * 2. `POST R submit`, header `Glint-Signature: <base64 DER SHA256withECDSA over the raw body bytes>`,
 *    body [Submission] as [SubmitCodec.encode] writes it (2-space pretty JSON): format, package,
 *    versionCode?, rev (0: the catalog has none), against? ("id@rev", the broken recipe), lang?,
 *    label?, shapes (scrubbed [com.thesiegs.glint.recipes.NotificationSample]s, times from
 *    [com.thesiegs.glint.recipes.Scrubber.BASE_TIME]), token (from 1), attestation (base64 DER, leaf first).
 *    - 202 (200, 201) `{"id":"<submission id>"}`: sent; the phone records package@rev.
 *    - 409 `{"error":"duplicate"}`: the relay has package@rev; recorded as sent.
 *    - 429 as above; 400/403 `{"error":"…"}`: refused.
 *
 * The relay checks: the token is its own, unexpired, unused, same package and rev; the chain verifies
 * to Google's hardware attestation root and isn't on its revocation list; the leaf's attested app is
 * com.thesiegs.glint with the release signing digest; the signature verifies with the leaf key; the
 * same scrub check ([SubmitPayload.problems]); rate limits per leaf key and per package. The key is
 * made once, so the leaf's attestation challenge is the first submission's token; later ones are
 * fresh by the signed token. The leaf key is a per-install pseudonym (rate limits need one), not a
 * device id; attestation batch certificates are shared by many phones.
 */
class SubmitClient(relay: String, private val attestor: Attestor, private val timeoutMs: Int = 15_000) {
    private val base: String = relay.trim().let { if (it.isEmpty() || it.endsWith("/")) it else "$it/" }

    sealed interface Outcome {
        /** No relay set: nothing was sent. */
        data object Off : Outcome
        data class Sent(val id: String) : Outcome
        /** The relay already has this app at this rev (409): as good as sent. */
        data object Duplicate : Outcome
        /** 429: try again no sooner than [retryAfter] seconds. */
        data class Limited(val retryAfter: Long) : Outcome
        data class Refused(val code: Int, val reason: String) : Outcome
        data class Failed(val why: String) : Outcome
    }

    /** Sends [s] (built by [SubmitPayload.build]; its token and chain are filled here). Never throws. */
    fun send(s: Submission): Outcome {
        if (base.isEmpty()) return Outcome.Off
        if (SubmitPayload.problems(s).isNotEmpty()) return Outcome.Refused(0, "not scrubbed")
        return exchange("submit", SubmitCodec.challenge(s.packageName, s.rev)) { token, chain ->
            SubmitCodec.encode(s.copy(token = token, attestation = chain))
        }
    }

    /**
     * Sends an owner's edited recipe (`glint.recipe-submit/1`, built by [RecipeShare.build] and
     * checked against the recipe it was made from, [base]), the same way: `POST R challenge` with
     * `{"format":"glint.recipe-submit/1","package","rev"}` (rev: the shared recipe's), then `POST R recipe`
     * with the signed body ([RecipeShare.encode]: format, package, rev, basedOn, recipe, token,
     * attestation). The relay checks it as `submit` (token, chain, signature, limits), checks the
     * recipe, and opens a pull request on the catalog changing only recipes/<id>.json, which waits
     * for the owner's merge. 202 `{"id":"<pull request>"}`, 409 duplicate, 429, 4xx as for `submit`.
     * Never throws.
     */
    fun sendRecipe(s: RecipeSubmission, base: com.thesiegs.glint.recipes.Recipe): Outcome {
        if (this.base.isEmpty()) return Outcome.Off
        if (RecipeShare.problems(base, s).isNotEmpty()) return Outcome.Refused(0, "not a plain edit")
        val challenge = SubmitCodec.json.encodeToString(
            SubmitCodec.ChallengeRequest.serializer(), SubmitCodec.ChallengeRequest(RecipeShare.FORMAT, s.packageName, s.rev),
        )
        return exchange("recipe", challenge) { token, chain -> RecipeShare.encode(s.copy(token = token, attestation = chain)) }
    }

    /** The challenge → signed post exchange both sends share; [body] writes the post's body with the token and chain. */
    private fun exchange(path: String, challenge: String, body: (String, List<String>) -> String): Outcome = try {
        val c = post("challenge", challenge.toByteArray(Charsets.UTF_8), null)
        when (c.code) {
            200 -> {
                val token = SubmitCodec.token(c.text)
                if (token == null) Outcome.Failed("no token") else {
                    val b64 = Base64.getEncoder()
                    val chain = attestor.chain(token.toByteArray(Charsets.UTF_8)).map { b64.encodeToString(it) }
                    if (chain.isEmpty()) Outcome.Failed("no attestation") else {
                        val bytes = body(token, chain).toByteArray(Charsets.UTF_8)
                        val sig = b64.encodeToString(attestor.sign(bytes))
                        val r = post(path, bytes, sig)
                        when (r.code) {
                            200, 201, 202 -> Outcome.Sent(SubmitCodec.accepted(r.text).orEmpty())
                            409 -> Outcome.Duplicate
                            429 -> Outcome.Limited(r.retryAfter())
                            else -> Outcome.Refused(r.code, SubmitCodec.error(r.text).error.take(120))
                        }
                    }
                }
            }
            429 -> Outcome.Limited(c.retryAfter())
            else -> Outcome.Refused(c.code, SubmitCodec.error(c.text).error.take(120))
        }
    } catch (e: IOException) {
        Outcome.Failed(e.javaClass.simpleName)
    } catch (e: java.security.GeneralSecurityException) {
        Outcome.Failed(e.javaClass.simpleName)
    } catch (e: RuntimeException) {
        // AndroidKeyStore throws ProviderException when a device can't attest.
        Outcome.Failed(e.javaClass.simpleName)
    }

    private class Answer(val code: Int, val text: String, val retryHeader: String?) {
        fun retryAfter(): Long = retryHeader?.trim()?.toLongOrNull() ?: SubmitCodec.error(text).retryAfter.takeIf { it > 0 } ?: 3_600L
    }

    private fun post(path: String, body: ByteArray, signature: String?): Answer {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.requestMethod = "POST"
            c.doOutput = true
            c.instanceFollowRedirects = false
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("Accept", "application/json")
            signature?.let { c.setRequestProperty(SIGNATURE_HEADER, it) }
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            return Answer(code, stream?.let { read(it) }.orEmpty(), c.getHeaderField("Retry-After"))
        } finally {
            c.disconnect()
        }
    }

    private fun read(input: InputStream): String = input.use {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = it.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_ANSWER) throw IOException("answer too large")
        }
        String(out.toByteArray(), Charsets.UTF_8)
    }

    companion object {
        const val SIGNATURE_HEADER = "Glint-Signature"
        private const val MAX_ANSWER = 16 shl 10
    }
}
