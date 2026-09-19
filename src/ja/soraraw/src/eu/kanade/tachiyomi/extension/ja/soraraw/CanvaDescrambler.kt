package eu.kanade.tachiyomi.extension.ja.soraraw

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.floor
import kotlin.math.min

/**
 * Port of the site's canva-mode descrambler — chunk 3852 `vL` + the seedrandom
 * ARC4 core bundled as webpack module 0xcb03. Validated against the browser.
 *
 * K = HMAC-SHA256(key = chapter.token, msg = chapter.id.toString())
 *
 * Pipeline: seedrandom(K) → split cols then rows (target 222px, clamp [185,259],
 * size arrays shuffled) → row-major tiles → classes "WxH" → paired/self
 * permutations + per-tile rot/flip draws (all seeded "K|…" strings) →
 * apply: out[slot] = transform(crop(scrambled, src)).
 */
internal object CanvaDescrambler {

    private const val TARGET = 222
    private const val SEG_MAX = 259
    private const val SEG_MIN = 185
    private const val WHITE = 0xFFFFFFFF.toInt() // opaque white ARGB

    // -------- seedrandom, old ARC4 core (0xcb03, verbatim) --------
    internal class SeedRandom(seed: String) {
        private val key = IntArray(seed.length) { seed[it].code and 0xFF }
        private val s = IntArray(256) { it }
        private var i = 0
        private var j = 0

        init {
            var jj = 0
            for (x in 0 until 256) {
                val t = s[x]
                jj = (jj + key[x % key.size] + t) and 0xFF
                s[x] = s[jj]
                s[jj] = t
            }
            // PRGA continues from i = j = 0 (KSA's j is NOT carried) and
            // discards one 256-byte batch — exactly like the bundled core.
            g(256)
        }

        private fun g(count: Int): Long {
            var ii = i
            var jj = j
            var r = 0L
            repeat(count) {
                ii = (ii + 1) and 0xFF
                val t = s[ii]
                jj = (jj + t) and 0xFF // old core: NO key re-injection in PRGA
                val u = s[jj]
                s[ii] = u
                s[jj] = t
                r = r * 256 + s[(u + t) and 0xFF]
            }
            i = ii
            j = jj
            return r
        }

        /** davidbau `double()` — float64-exact. */
        operator fun invoke(): Double {
            var n = g(6).toDouble()
            var d = 281474976710656.0 // 256^6 = 2^48
            var x = 0
            while (n < 4503599627370496.0) { // 2^52
                n = (n + x) * 256.0
                d *= 256.0
                x = g(1).toInt()
            }
            while (n >= 9007199254740992.0) { // 2^53
                n /= 2.0
                d /= 2.0
                x = x ushr 1
            }
            return (n + x) / d
        }
    }

    // -------- helpers --------

    private fun hmacSha256Hex(key: String, msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // JS Durstenfeld: for i = len-1 downTo 1: j = floor(rng() * (i+1))
    private fun durstenfeld(a: MutableList<Int>, rng: SeedRandom) {
        for (i in a.size - 1 downTo 1) {
            val j = floor(rng() * (i + 1)).toInt()
            val t = a[i]
            a[i] = a[j]
            a[j] = t
        }
    }

    private fun split1d(total: Int, rng: SeedRandom): IntArray {
        var cnt = maxOf(1, Math.round(total / TARGET.toDouble()).toInt())
        var seg = total / cnt
        while (seg > SEG_MAX) {
            cnt++
            seg = total / cnt
        }
        while (seg < SEG_MIN && cnt > 1) {
            cnt--
            seg = total / cnt
        }
        val base = total / cnt
        val rem = total - base * cnt
        val arr = ArrayList<Int>(cnt)
        repeat(rem) { arr.add(base + 1) }
        repeat(cnt - rem) { arr.add(base) }
        durstenfeld(arr, rng) // the size array itself is shuffled
        return arr.toIntArray()
    }

    private class Tile(val index: Int, val x: Int, val y: Int, val w: Int, val h: Int)
    private class Tf(val rot: Int, val flipH: Boolean, val flipV: Boolean)
    private class Op(val srcIdx: Int, val tf: Tf)
    private class Transformed(val pixels: IntArray, val w: Int, val h: Int)

    private fun tfChooser(rng: SeedRandom, allowSwap: Boolean, isSquare: Boolean): Tf {
        val rot = when {
            allowSwap -> if (rng() < 0.5) 1 else 3
            isSquare -> floor(rng() * 4).toInt()
            else -> if (rng() < 0.5) 0 else 2
        }
        return Tf(rot, rng() < 0.5, rng() < 0.5)
    }

    /**
     * Replicates the site's canvas transform exactly:
     *   p_out = R(rot)·S(flips)·p + R(rot)·T2 + T1
     * with T2 = (flipH ? oW : 0, flipV ? oH : 0) and T1 the per-rot translate.
     * Non-square tiles with odd rot + some flip combos fall partially outside
     * the output — the site clips those too; dropped pixels stay white.
     */
    private fun transformTile(src: IntArray, w: Int, h: Int, tf: Tf): Transformed {
        val odd = tf.rot % 2 == 1
        val oW = if (odd) h else w
        val oH = if (odd) w else h

        val r00: Int
        val r01: Int
        val r10: Int
        val r11: Int
        when (tf.rot) {
            1 -> {
                r00 = 0
                r01 = 1
                r10 = -1
                r11 = 0
            } // rotate(-90°)
            2 -> {
                r00 = -1
                r01 = 0
                r10 = 0
                r11 = -1
            } // rotate(180°)
            3 -> {
                r00 = 0
                r01 = -1
                r10 = 1
                r11 = 0
            } // rotate(+90°)
            else -> {
                r00 = 1
                r01 = 0
                r10 = 0
                r11 = 1
            }
        }
        val sx = if (tf.flipH) -1 else 1
        val sy = if (tf.flipV) -1 else 1
        val a00 = r00 * sx
        val a01 = r01 * sy
        val a10 = r10 * sx
        val a11 = r11 * sy

        val t2x = if (tf.flipH) oW else 0
        val t2y = if (tf.flipV) oH else 0
        val t1x: Int
        val t1y: Int
        when (tf.rot) {
            1 -> {
                t1x = 0
                t1y = oW
            }
            3 -> {
                t1x = oH
                t1y = 0
            }
            2 -> {
                t1x = oW
                t1y = oH
            }
            else -> {
                t1x = 0
                t1y = 0
            }
        }
        val bx = r00 * t2x + r01 * t2y + t1x
        val by = r10 * t2x + r11 * t2y + t1y

        val out = IntArray(oW * oH) { WHITE }
        // Canvas maps pixel centers, not indices: matrix rows summing negative
        // need a -1 correction, else every flipped/rotated axis drops its first
        // pixel row/col and shows a 1px white line instead.
        val cx = if (a00 + a01 < 0) -1 else 0
        val cy = if (a10 + a11 < 0) -1 else 0
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val ox = a00 * x + a01 * y + bx + cx
                val oy = a10 * x + a11 * y + by + cy
                if (ox in 0 until oW && oy in 0 until oH) out[oy * oW + ox] = src[row + x]
            }
        }
        return Transformed(out, oW, oH)
    }

    // -------- entry point --------

    fun descramble(imageBytes: ByteArray, chapterId: String, token: String): ByteArray? = try {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, opts)
            ?: return null
        val w = bmp.width
        val h = bmp.height
        val scrambled = IntArray(w * h)
        bmp.getPixels(scrambled, 0, w, 0, 0, w, h)
        bmp.recycle()

        val k = hmacSha256Hex(token, chapterId)

        val rngSplit = SeedRandom(k)
        val cols = split1d(w, rngSplit)
        val rows = split1d(h, rngSplit)

        val tiles = ArrayList<Tile>(cols.size * rows.size)
        var idx = 0
        var y = 0
        for (rh in rows) {
            var x = 0
            for (cw in cols) {
                tiles.add(Tile(idx++, x, y, cw, rh))
                x += cw
            }
            y += rh
        }

        val classes = LinkedHashMap<String, MutableList<Int>>()
        for (t in tiles) classes.getOrPut("${t.w}x${t.h}") { mutableListOf() }.add(t.index)

        val ops = HashMap<Int, Op>()
        val seen = HashSet<String>()
        for ((A, members) in classes) {
            if (A in seen) continue
            val wh = A.split('x')
            val aw = wh[0].toInt()
            val ah = wh[1].toInt()
            val isSquare = aw == ah
            val bKey = "${ah}x$aw"
            val partner = if (bKey != A) classes[bKey] else null
            if (partner != null) {
                seen.add(A)
                seen.add(bKey)
                val rr = SeedRandom("$k|pair|$A|$bKey")
                val sa = members.toMutableList()
                val sb = partner.toMutableList()
                durstenfeld(sa, rr)
                durstenfeld(sb, rr) // one shared stream: A first, then B
                val m = min(sa.size, sb.size)
                for (kk in 0 until m) {
                    ops[sa[kk]] = Op(sb[kk], tfChooser(SeedRandom("$k|tf|AtoB|$A|$kk"), true, false))
                }
                for (kk in 0 until m) {
                    ops[sb[kk]] = Op(sa[kk], tfChooser(SeedRandom("$k|tf|BtoA|$bKey|$kk"), true, false))
                }
                if (sa.size > m) {
                    val rest = sa.drop(m)
                    val rs = rest.toMutableList()
                    durstenfeld(rs, SeedRandom("$k|perm|A|$A"))
                    for (kk in rest.indices) {
                        ops[rest[kk]] = Op(rs[kk], tfChooser(SeedRandom("$k|tf|A|$A|rest|$kk"), false, isSquare))
                    }
                }
                if (sb.size > m) {
                    val rest = sb.drop(m)
                    val rs = rest.toMutableList()
                    durstenfeld(rs, SeedRandom("$k|perm|B|$bKey"))
                    for (kk in rest.indices) {
                        ops[rest[kk]] = Op(rs[kk], tfChooser(SeedRandom("$k|tf|B|$bKey|rest|$kk"), false, isSquare))
                    }
                }
            } else {
                seen.add(A)
                val order = members.toMutableList()
                durstenfeld(order, SeedRandom("$k|perm|$A"))
                for (kk in members.indices) {
                    ops[members[kk]] = Op(order[kk], tfChooser(SeedRandom("$k|tf|self|$A|$kk"), false, isSquare))
                }
            }
        }
        if (ops.size != tiles.size) return null

        val out = IntArray(w * h) { WHITE }
        for ((slot, op) in ops) {
            val dst = tiles[slot]
            val srcT = tiles[op.srcIdx]
            val srcBuf = IntArray(srcT.w * srcT.h)
            for (r in 0 until srcT.h) {
                System.arraycopy(scrambled, (srcT.y + r) * w + srcT.x, srcBuf, r * srcT.w, srcT.w)
            }
            val t = transformTile(srcBuf, srcT.w, srcT.h, op.tf)
            if (t.w != dst.w || t.h != dst.h) continue // port bug guard — leave slot white
            for (r in 0 until t.h) {
                System.arraycopy(t.pixels, r * t.w, out, (dst.y + r) * w + dst.x, t.w)
            }
        }

        val result = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        val bos = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.PNG, 100, bos)
        result.recycle()
        bos.toByteArray()
    } catch (e: OutOfMemoryError) {
        null
    }
}
