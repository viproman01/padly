package app.padly.android.transport

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal MessagePack encoder/decoder — same subset as the Swift side:
 * nil, bool, int (signed + unsigned), float32, float64, str, bin, array, map.
 * Designed for clarity over throughput on Padly's small frames.
 */
sealed class MsgPack {
    data object Nil : MsgPack()
    data class Bool(val v: Boolean) : MsgPack()
    data class Int64(val v: Long) : MsgPack()
    data class UInt64(val v: ULong) : MsgPack()
    data class Float32(val v: Float) : MsgPack()
    data class Float64(val v: Double) : MsgPack()
    data class Str(val v: String) : MsgPack()
    data class Bin(val v: ByteArray) : MsgPack() {
        override fun equals(other: Any?): Boolean = other is Bin && v.contentEquals(other.v)
        override fun hashCode(): Int = v.contentHashCode()
    }
    data class Arr(val items: List<MsgPack>) : MsgPack()
    data class Map(val pairs: List<Pair<MsgPack, MsgPack>>) : MsgPack()
}

object MessagePackCodec {
    fun encode(v: MsgPack): ByteArray {
        val out = ByteArrayOutputStream(64)
        write(v, out)
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): MsgPack {
        val cursor = intArrayOf(0)
        return read(bytes, cursor)
    }

    // MARK: write

    private fun write(v: MsgPack, out: ByteArrayOutputStream) {
        when (v) {
            is MsgPack.Nil -> out.write(0xC0)
            is MsgPack.Bool -> out.write(if (v.v) 0xC3 else 0xC2)
            is MsgPack.UInt64 -> writeUInt(v.v.toLong(), out)
            is MsgPack.Int64 -> if (v.v >= 0) writeUInt(v.v, out) else writeInt(v.v, out)
            is MsgPack.Float32 -> {
                out.write(0xCA)
                out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(v.v).array())
            }
            is MsgPack.Float64 -> {
                out.write(0xCB)
                out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(v.v).array())
            }
            is MsgPack.Str -> {
                val bytes = v.v.toByteArray(Charsets.UTF_8)
                writeStrHeader(bytes.size, out)
                out.write(bytes)
            }
            is MsgPack.Bin -> {
                writeBinHeader(v.v.size, out)
                out.write(v.v)
            }
            is MsgPack.Arr -> {
                writeArrayHeader(v.items.size, out)
                v.items.forEach { write(it, out) }
            }
            is MsgPack.Map -> {
                writeMapHeader(v.pairs.size, out)
                v.pairs.forEach { (k, value) -> write(k, out); write(value, out) }
            }
        }
    }

    private fun writeUInt(u: Long, out: ByteArrayOutputStream) {
        val v = u.toULong()
        when {
            v <= 0x7Fu -> out.write(v.toInt())
            v <= 0xFFu -> { out.write(0xCC); out.write(v.toInt()) }
            v <= 0xFFFFu -> { out.write(0xCD); writeBE(v.toLong(), 2, out) }
            v <= 0xFFFFFFFFu -> { out.write(0xCE); writeBE(v.toLong(), 4, out) }
            else -> { out.write(0xCF); writeBE(v.toLong(), 8, out) }
        }
    }

    private fun writeInt(i: Long, out: ByteArrayOutputStream) {
        when {
            i >= -32 -> out.write((i.toInt() and 0xFF))
            i >= Byte.MIN_VALUE -> { out.write(0xD0); out.write(i.toInt() and 0xFF) }
            i >= Short.MIN_VALUE -> { out.write(0xD1); writeBE(i, 2, out) }
            i >= Int.MIN_VALUE -> { out.write(0xD2); writeBE(i, 4, out) }
            else -> { out.write(0xD3); writeBE(i, 8, out) }
        }
    }

    private fun writeBE(value: Long, bytes: Int, out: ByteArrayOutputStream) {
        for (i in bytes - 1 downTo 0) {
            out.write(((value shr (i * 8)) and 0xFF).toInt())
        }
    }

    private fun writeStrHeader(count: Int, out: ByteArrayOutputStream) {
        when {
            count < 32 -> out.write(0xA0 or count)
            count <= 0xFF -> { out.write(0xD9); out.write(count) }
            count <= 0xFFFF -> { out.write(0xDA); writeBE(count.toLong(), 2, out) }
            else -> { out.write(0xDB); writeBE(count.toLong(), 4, out) }
        }
    }

    private fun writeBinHeader(count: Int, out: ByteArrayOutputStream) {
        when {
            count <= 0xFF -> { out.write(0xC4); out.write(count) }
            count <= 0xFFFF -> { out.write(0xC5); writeBE(count.toLong(), 2, out) }
            else -> { out.write(0xC6); writeBE(count.toLong(), 4, out) }
        }
    }

    private fun writeArrayHeader(count: Int, out: ByteArrayOutputStream) {
        when {
            count < 16 -> out.write(0x90 or count)
            count <= 0xFFFF -> { out.write(0xDC); writeBE(count.toLong(), 2, out) }
            else -> { out.write(0xDD); writeBE(count.toLong(), 4, out) }
        }
    }

    private fun writeMapHeader(count: Int, out: ByteArrayOutputStream) {
        when {
            count < 16 -> out.write(0x80 or count)
            count <= 0xFFFF -> { out.write(0xDE); writeBE(count.toLong(), 2, out) }
            else -> { out.write(0xDF); writeBE(count.toLong(), 4, out) }
        }
    }

    // MARK: read

    private fun read(b: ByteArray, c: IntArray): MsgPack {
        val tag = b[c[0]].toInt() and 0xFF; c[0] += 1
        return when {
            tag == 0xC0 -> MsgPack.Nil
            tag == 0xC2 -> MsgPack.Bool(false)
            tag == 0xC3 -> MsgPack.Bool(true)
            tag == 0xCA -> MsgPack.Float32(Float.fromBits(readBE(b, c, 4).toInt()))
            tag == 0xCB -> MsgPack.Float64(Double.fromBits(readBE(b, c, 8)))
            tag == 0xCC -> MsgPack.UInt64(readBE(b, c, 1).toULong())
            tag == 0xCD -> MsgPack.UInt64(readBE(b, c, 2).toULong())
            tag == 0xCE -> MsgPack.UInt64(readBE(b, c, 4).toULong())
            tag == 0xCF -> MsgPack.UInt64(readBE(b, c, 8).toULong())
            tag == 0xD0 -> MsgPack.Int64(b[c[0]++].toLong())
            tag == 0xD1 -> MsgPack.Int64(readBE(b, c, 2).let { if (it >= 0x8000) it - 0x10000 else it })
            tag == 0xD2 -> MsgPack.Int64(readBE(b, c, 4).let { if (it >= 0x8000_0000L) it - 0x1_0000_0000L else it })
            tag == 0xD3 -> MsgPack.Int64(readBE(b, c, 8))
            tag == 0xD9 -> readStr(b, c, readBE(b, c, 1).toInt())
            tag == 0xDA -> readStr(b, c, readBE(b, c, 2).toInt())
            tag == 0xDB -> readStr(b, c, readBE(b, c, 4).toInt())
            tag == 0xC4 -> readBin(b, c, readBE(b, c, 1).toInt())
            tag == 0xC5 -> readBin(b, c, readBE(b, c, 2).toInt())
            tag == 0xC6 -> readBin(b, c, readBE(b, c, 4).toInt())
            tag == 0xDC -> readArr(b, c, readBE(b, c, 2).toInt())
            tag == 0xDD -> readArr(b, c, readBE(b, c, 4).toInt())
            tag == 0xDE -> readMap(b, c, readBE(b, c, 2).toInt())
            tag == 0xDF -> readMap(b, c, readBE(b, c, 4).toInt())
            tag <= 0x7F -> MsgPack.UInt64(tag.toULong())
            tag >= 0xE0 -> MsgPack.Int64((tag - 0x100).toLong())
            tag and 0xE0 == 0xA0 -> readStr(b, c, tag and 0x1F)
            tag and 0xF0 == 0x90 -> readArr(b, c, tag and 0x0F)
            tag and 0xF0 == 0x80 -> readMap(b, c, tag and 0x0F)
            else -> error("unsupported tag 0x${tag.toString(16)}")
        }
    }

    private fun readBE(b: ByteArray, c: IntArray, n: Int): Long {
        var v: Long = 0
        for (i in 0 until n) {
            v = (v shl 8) or (b[c[0] + i].toLong() and 0xFF)
        }
        c[0] += n
        return v
    }

    private fun readStr(b: ByteArray, c: IntArray, n: Int): MsgPack.Str {
        val s = String(b, c[0], n, Charsets.UTF_8); c[0] += n; return MsgPack.Str(s)
    }
    private fun readBin(b: ByteArray, c: IntArray, n: Int): MsgPack.Bin {
        val slice = b.copyOfRange(c[0], c[0] + n); c[0] += n; return MsgPack.Bin(slice)
    }
    private fun readArr(b: ByteArray, c: IntArray, n: Int): MsgPack.Arr {
        val items = ArrayList<MsgPack>(n); repeat(n) { items.add(read(b, c)) }; return MsgPack.Arr(items)
    }
    private fun readMap(b: ByteArray, c: IntArray, n: Int): MsgPack.Map {
        val pairs = ArrayList<Pair<MsgPack, MsgPack>>(n)
        repeat(n) { pairs.add(read(b, c) to read(b, c)) }
        return MsgPack.Map(pairs)
    }
}
