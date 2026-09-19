package eu.kanade.tachiyomi.extension.ja.soraraw

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Pixel-exact Kotlin port of soraraw.wasm `unscramble`
 * (wasm sha256: 21a15e65be2a308dbc695a50505d0c5899515cd0f308c99821d5d235e1666cd6).
 *
 * Toggle flags below exist for the validation matrix against real chapters.
 * Opcode-literal defaults: artifact-included H1, row-major grid.
 */
internal object ImageDescrambler {
    const val OK = 0
    const val ERR_ARGS = -1
    const val ERR_DIMS = -2
    const val ERR_ALLOC = -3

    // Frozen: validated against live canva2 chapters via the site's own wasm
    // (rc=0, clean output with key=chapterId, n=8, artifact-salted H1, row-major).
    private val includeArtifact = true
    private val gridColumnMajor = false

    // 64-byte ASCII digest embedded in wasm rodata at address 1041
    private const val ARTIFACT =
        "6a0248ad1ca4208275aed64d336e81595ecb149422a8e621f70e23b9f01b9c1c"
    private val HEX_TABLE = "0123456789abcdef".toByteArray(Charsets.US_ASCII)

    data class Tile(val x: Int, val y: Int, val w: Int, val h: Int)

    fun unscramble(
        pixels: ByteArray,
        width: Int,
        height: Int,
        channels: Int = 4,
        n: Int = 8,
        chapterId: String,
    ): Int {
        val key = chapterId.toByteArray(Charsets.UTF_8)
        if (channels !in 1..4) return ERR_ARGS
        if (n !in 1..64) return ERR_ARGS
        if (width < 1 || height < 1) return ERR_ARGS
        if (pixels.isEmpty() || key.isEmpty()) return ERR_ARGS
        if (key.size > 128) return ERR_ARGS
        if (pixels.size < width * height * channels) return ERR_ARGS

        val tileW = width / n
        val tileH = height / n
        if (tileW < 1 || tileH < 1) return ERR_DIMS

        // H1 = SHA256(keyBytes || embeddedArtifact)  [toggle: key only]
        val h1 = sha256(
            buildList {
                add(key)
                if (includeArtifact) add(ARTIFACT.toByteArray(Charsets.US_ASCII))
            },
        )
        val hexH1 = hexLower(h1).toString(Charsets.US_ASCII)

        // H2 = SHA256(hexH1 + ":nxn:" + n + ":" + w + "x" + h)
        val preimage = "$hexH1:nxn:$n:${width}x$height".toByteArray(Charsets.US_ASCII)
        val h2 = sha256(listOf(preimage))

        val rng = Mulberry32(seedFromH2(h2))

        val tiles = try {
            buildGrid(width, height, n, gridColumnMajor)
        } catch (e: OutOfMemoryError) {
            return ERR_ALLOC
        }

        // group tile indices by (w, h) size class
        val classes = LinkedHashMap<Pair<Int, Int>, MutableList<Int>>()
        tiles.forEachIndexed { idx, t ->
            classes.getOrPut(t.w to t.h) { mutableListOf() }.add(idx)
        }

        // per-class shuffle + rotation draws — SAME rng stream across all classes
        val permOf = mutableMapOf<Pair<Int, Int>, MutableList<Int>>()
        val rotOf = mutableMapOf<Pair<Int, Int>, MutableList<Int>>()
        for ((size, members) in classes) {
            val order = members.toMutableList()
            fisherYatesDescending(order, rng)
            permOf[size] = order
            val square = size.first == size.second
            val rots = MutableList(order.size) {
                if (square) rng.nextRot4() and 3 else rng.nextCoinFlip()
            }
            rotOf[size] = rots
        }

        // apply: for each grid slot, source tile = permuted pick, with rotation
        val staged = try {
            ByteArray(pixels.size)
        } catch (e: OutOfMemoryError) {
            return ERR_ALLOC
        }
        val posInClass = mutableMapOf<Pair<Int, Int>, Int>()
        tiles.forEachIndexed { slot, dst ->
            val size = dst.w to dst.h
            val pos = (posInClass[size] ?: 0).also { posInClass[size] = it + 1 }
            val srcIdx = permOf.getValue(size)[pos]
            val src = tiles[srcIdx]
            val rot = rotOf.getValue(size)[pos]
            blit(pixels, width, staged, width, src.x, src.y, dst.x, dst.y, dst.w, dst.h, channels, rot)
        }
        System.arraycopy(staged, 0, pixels, 0, pixels.size)
        return OK
    }

    /** Decodes [imageBytes], runs the wasm-port unscramble with key=[chapterId],
     *  n=8, and re-encodes as PNG. Returns null on any failure (fail-open). */
    fun descramble(imageBytes: ByteArray, chapterId: String): ByteArray? = try {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, opts)
            ?: return null
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()

        // IntArray(ARGB) → ByteArray(RGBA), matching the wasm's byte layout
        val rgba = ByteArray(w * h * 4)
        for (i in pixels.indices) {
            val p = pixels[i]
            rgba[i * 4 + 0] = ((p ushr 16) and 0xFF).toByte() // R
            rgba[i * 4 + 1] = ((p ushr 8) and 0xFF).toByte() // G
            rgba[i * 4 + 2] = (p and 0xFF).toByte() // B
            rgba[i * 4 + 3] = ((p ushr 24) and 0xFF).toByte() // A
        }

        val rc = unscramble(rgba, w, h, 4, 8, chapterId)
        if (rc != OK) return null

        // ByteArray(RGBA) → IntArray(ARGB)
        for (i in pixels.indices) {
            val r = rgba[i * 4 + 0].toInt() and 0xFF
            val g = rgba[i * 4 + 1].toInt() and 0xFF
            val b = rgba[i * 4 + 2].toInt() and 0xFF
            val a = rgba[i * 4 + 3].toInt() and 0xFF
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        val result = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val bos = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.PNG, 100, bos)
        result.recycle()
        bos.toByteArray()
    } catch (e: OutOfMemoryError) {
        null
    }
    // + SeedRandom (ARC4, ~40 lines, direct from the dossier §6.5)
    // + split(222px, clamp 185–259, shuffled sizes)
    // + size-class pairing with |pair|/|tf|/|perm| domain strings
    // + rot/flip choice tables

    // ---------- primitives ----------

    private fun sha256(parts: List<ByteArray>): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    private fun hexLower(bytes: ByteArray): ByteArray {
        val out = ByteArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_TABLE[v ushr 4]
            out[i * 2 + 1] = HEX_TABLE[v and 0x0F]
        }
        return out
    }

    private fun seedFromH2(h2: ByteArray): Int {
        val le = (h2[0].toInt() and 0xFF) or
            ((h2[1].toInt() and 0xFF) shl 8) or
            ((h2[2].toInt() and 0xFF) shl 16) or
            ((h2[3].toInt() and 0xFF) shl 24)
        return Integer.reverseBytes(le) // bswap32 → big-endian read of the LE word
    }

    private fun buildGrid(w: Int, h: Int, n: Int, columnMajor: Boolean): List<Tile> {
        val tw = w / n
        val rw = w - tw * n
        val th = h / n
        val rh = h - th * n
        val out = ArrayList<Tile>(n * n)
        if (!columnMajor) {
            var y = 0
            for (r in 0 until n) {
                val hh = if (r < rh) th + 1 else th
                var x = 0
                for (c in 0 until n) {
                    val ww = if (c < rw) tw + 1 else tw
                    out.add(Tile(x, y, ww, hh))
                    x += ww
                }
                y += hh
            }
        } else {
            var x = 0
            for (c in 0 until n) {
                val ww = if (c < rw) tw + 1 else tw
                var y = 0
                for (r in 0 until n) {
                    val hh = if (r < rh) th + 1 else th
                    out.add(Tile(x, y, ww, hh))
                    y += hh
                }
                x += ww
            }
        }
        return out
    }

    private fun fisherYatesDescending(a: MutableList<Int>, rng: Mulberry32) {
        var count = a.size
        var ptr = a.size - 1
        while (count > 1) {
            val j = rng.nextBounded(count)
            if (j !in 0 until count) break
            val t = a[ptr]
            a[ptr] = a[j]
            a[j] = t
            ptr -= 1
            count -= 1
        }
    }

    private fun blit(
        src: ByteArray,
        srcW: Int,
        dst: ByteArray,
        dstW: Int,
        sx: Int,
        sy: Int,
        dx: Int,
        dy: Int,
        w: Int,
        h: Int,
        ch: Int,
        rot: Int,
    ) {
        when (rot and 3) {
            0 -> for (r in 0 until h) {
                System.arraycopy(
                    src,
                    ((sy + r) * srcW + sx) * ch,
                    dst,
                    ((dy + r) * dstW + dx) * ch,
                    w * ch,
                )
            }
            2 -> for (r in 0 until h) {
                for (c in 0 until w) {
                    for (k in 0 until ch) {
                        dst[((dy + r) * dstW + dx + c) * ch + k] =
                            src[((sy + h - 1 - r) * srcW + sx + w - 1 - c) * ch + k]
                    }
                }
            }
            1 -> for (r in 0 until h) {
                for (c in 0 until w) {
                    for (k in 0 until ch) {
                        dst[((dy + c) * dstW + dx + (h - 1 - r)) * ch + k] =
                            src[((sy + r) * srcW + sx + c) * ch + k]
                    }
                }
            }
            else -> for (r in 0 until h) {
                for (c in 0 until w) {
                    for (k in 0 until ch) {
                        dst[((dy + (w - 1 - c)) * dstW + dx + r) * ch + k] =
                            src[((sy + r) * srcW + sx + c) * ch + k]
                    }
                }
            }
        }
    }

    internal class Mulberry32(var state: Int) {
        private fun nextUnit(): Double {
            state += 0x6D2B79F5.toInt() // wraps
            val t1 = (state xor (state ushr 15)) * (state or 1)
            val t2 = (t1 xor (t1 ushr 7)) * (t1 or 61)
            val t3 = (t1 + t2) xor t1
            val u = (t3 xor (t3 ushr 14)).toUInt().toDouble()
            return u * 2.3283064365386963e-10 // 0x1p-32
        }

        fun nextBounded(count: Int): Int {
            val scaled = nextUnit() * count.toDouble()
            return if (abs(scaled) < 2147483648.0) scaled.toInt() else Int.MIN_VALUE
        }

        fun nextRot4(): Int {
            val scaled = nextUnit() * 4.0
            return if (abs(scaled) < 2147483648.0) scaled.toInt() else Int.MIN_VALUE
        }

        /** Wasm-validated: non-square classes draw {0,2} via this path, not rot4. */
        fun nextCoinFlip(): Int = if (nextUnit() < 0.5) 0 else 2
    }
}
