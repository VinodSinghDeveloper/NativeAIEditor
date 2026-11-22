package com.nativeai.editor.ai

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Minimal ONNX protobuf reader: extracts the "emap" initializer (512x512) required by InSwapper.
object OnnxProto {
  private class Pb(val b: ByteArray, var p: Int, val end: Int) {
    fun eof() = p >= end
    fun varint(): Long {
      var r = 0L; var s = 0
      while (true) {
        val x = b[p++].toInt() and 0xFF
        r = r or ((x and 0x7F).toLong() shl s)
        if (x and 0x80 == 0) return r
        s += 7
      }
    }
    fun slice(): ByteArray { val n = varint().toInt(); val a = b.copyOfRange(p, p + n); p += n; return a }
    fun skip(w: Int) { when (w) { 0 -> varint(); 1 -> p += 8; 2 -> slice(); 5 -> p += 4; else -> error("wire $w") } }
  }

  private fun fields(data: ByteArray, visit: (field: Int, wire: Int, bytes: ByteArray?, v: Long) -> Unit) {
    val pb = Pb(data, 0, data.size)
    while (!pb.eof()) {
      val key = pb.varint(); val f = (key ushr 3).toInt(); val w = (key and 7).toInt()
      if (w == 2) visit(f, w, pb.slice(), 0) else { val v = if (w == 0) pb.varint() else 0L; if (w != 0) pb.skip(w); visit(f, w, null, v) }
    }
  }

  private fun tensorName(t: ByteArray): String? {
    var name: String? = null
    fields(t) { f, _, b, _ -> if (f == 8 && b != null) name = String(b, Charsets.UTF_8) }
    return name
  }

  private fun tensorFloats(t: ByteArray): FloatArray {
    var raw: ByteArray? = null
    val packed = ArrayList<Float>()
    fields(t) { f, w, b, _ ->
      when {
        f == 9 && b != null -> raw = b
        f == 4 && w == 2 && b != null -> {
          val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
          while (bb.remaining() >= 4) packed.add(bb.float)
        }
        f == 4 && w == 5 -> {
          val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0)
          bb.clear()
        }
      }
    }
    val r = raw
    if (r != null) {
      val bb = ByteBuffer.wrap(r).order(ByteOrder.LITTLE_ENDIAN)
      val out = FloatArray(r.size / 4)
      for (i in out.indices) out[i] = bb.float
      return out
    }
    return packed.toFloatArray()
  }

  fun emap(file: File): FloatArray {
    val bytes = file.readBytes()
    var graph: ByteArray? = null
    fields(bytes) { f, _, b, _ -> if (f == 7 && b != null && graph == null) graph = b }
    val g = graph ?: error("ONNX graph not found")
    var named: ByteArray? = null; var last: ByteArray? = null
    fields(g) { f, _, b, _ ->
      if (f == 5 && b != null) { last = b; if (tensorName(b) == "emap") named = b }
    }
    val t = named ?: last ?: error("No initializer found in inswapper model")
    val floats = tensorFloats(t)
    check(floats.size == 512 * 512) { "Unexpected emap size ${floats.size}" }
    return floats
  }
}
