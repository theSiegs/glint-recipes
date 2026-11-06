package com.thesiegs.glint.recipes.exchange

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 signature checking (RFC 8032 §5.1.7), verify only: a straight port of the RFC's own
 * reference code (§6) on [BigInteger]. Android has no platform Ed25519 before API 33 and minSdk is
 * 26, and a whole crypto library is a lot of APK for one check a day over public data, so this is
 * the small, well-known algorithm itself. It isn't constant-time, which a verify of a public index
 * with a public key doesn't need. Tested on the RFC's vectors and the catalog's own signatures.
 */
object Ed25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
    /** The group order L. */
    private val Q: BigInteger = BigInteger.ONE.shiftLeft(252) + BigInteger("27742317777372353535851937790883648493")
    private val TWO = BigInteger.valueOf(2)
    private val D: BigInteger = (BigInteger.valueOf(-121665) * inv(BigInteger.valueOf(121666))).mod(P)
    private val SQRT_M1: BigInteger = TWO.modPow((P - BigInteger.ONE).shiftRight(2), P)
    private val MASK_255: BigInteger = BigInteger.ONE.shiftLeft(255) - BigInteger.ONE

    /** A point in extended coordinates (x = X/Z, y = Y/Z, x·y = T/Z). */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val Zero = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    private val G: Point = run {
        val gy = (BigInteger.valueOf(4) * inv(BigInteger.valueOf(5))).mod(P)
        val gx = recoverX(gy, 0)!!
        Point(gx, gy, BigInteger.ONE, (gx * gy).mod(P))
    }

    /** Whether [signature] (64 bytes) is [publicKey]'s (32 bytes) over [message]. Never throws. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = runCatching {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decompress(publicKey) ?: return false
        val rBytes = signature.copyOfRange(0, 32)
        val r = decompress(rBytes) ?: return false
        val s = littleEndian(signature.copyOfRange(32, 64))
        if (s >= Q) return false
        val sha = MessageDigest.getInstance("SHA-512")
        sha.update(rBytes); sha.update(publicKey); sha.update(message)
        val h = littleEndian(sha.digest()).mod(Q)
        equal(mul(s, G), add(r, mul(h, a)))
    }.getOrDefault(false)

    private fun inv(x: BigInteger): BigInteger = x.modPow(P - TWO, P)

    private fun add(p: Point, q: Point): Point {
        val a = ((p.y - p.x) * (q.y - q.x)).mod(P)
        val b = ((p.y + p.x) * (q.y + q.x)).mod(P)
        val c = (TWO * p.t * q.t * D).mod(P)
        val d = (TWO * p.z * q.z).mod(P)
        val e = b - a; val f = d - c; val g = d + c; val h = b + a
        return Point((e * f).mod(P), (g * h).mod(P), (f * g).mod(P), (e * h).mod(P))
    }

    private fun mul(scalar: BigInteger, point: Point): Point {
        var s = scalar; var p = point; var q = Zero
        while (s.signum() > 0) {
            if (s.testBit(0)) q = add(q, p)
            p = add(p, p)
            s = s.shiftRight(1)
        }
        return q
    }

    private fun equal(p: Point, q: Point): Boolean =
        (p.x * q.z - q.x * p.z).mod(P).signum() == 0 && (p.y * q.z - q.y * p.z).mod(P).signum() == 0

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val x2 = ((y * y - BigInteger.ONE) * inv(D * y * y + BigInteger.ONE)).mod(P)
        if (x2.signum() == 0) return if (sign != 0) null else BigInteger.ZERO
        var x = x2.modPow((P + BigInteger.valueOf(3)).shiftRight(3), P)
        if ((x * x - x2).mod(P).signum() != 0) x = (x * SQRT_M1).mod(P)
        if ((x * x - x2).mod(P).signum() != 0) return null
        if ((if (x.testBit(0)) 1 else 0) != sign) x = P - x
        return x
    }

    private fun decompress(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        val n = littleEndian(bytes)
        val sign = if (n.testBit(255)) 1 else 0
        val y = n.and(MASK_255)
        val x = recoverX(y, sign) ?: return null
        return Point(x, y, BigInteger.ONE, (x * y).mod(P))
    }

    private fun littleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())
}
