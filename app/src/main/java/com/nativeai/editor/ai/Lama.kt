package com.nativeai.editor.ai
import android.graphics.*
import com.nativeai.editor.core.*
import java.io.File
import kotlin.math.*
object Lama {
  fun erase(src: Bitmap, mask: Bitmap, modelFile: File): Bitmap {
    val sess = Ort.session(modelFile)
    val fixed = Ort.fixedInputHW(sess)
    val w = src.width; val h = src.height
    val scale = if (max(w, h) > 1024) 1024f / max(w, h) else 1f
    val sw = fixed?.second ?: max(8, ((w * scale).roundToInt() / 8) * 8)
    val sh = fixed?.first ?: max(8, ((h * scale).roundToInt() / 8) * 8)
    val imgPx = src.resize(sw, sh).pixels(); val mskPx = mask.resize(sw, sh).pixels()
    val hw = sw * sh; val imgBuf = FloatArray(3 * hw); val mskBuf = FloatArray(hw)
    for (i in 0 until hw) {
      val p = imgPx.get(i)
      imgBuf.set(i, ((p ushr 16) and 255) / 255f)
      imgBuf.set(hw + i, ((p ushr 8) and 255) / 255f)
      imgBuf.set(2 * hw + i, (p and 255) / 255f)
      val mp = mskPx.get(i)
      val mv = max(Color.alpha(mp), max(Color.red(mp), max(Color.green(mp), Color.blue(mp))))
      mskBuf.set(i, if (mv > 40) 1f else 0f)
    }
    val inputs = mutableMapOf<String, ai.onnxruntime.OnnxTensor>()
    for (name in Ort.inputNames(sess)) {
      if (name.contains("mask", true)) inputs.put(name, Ort.tensor(mskBuf, longArrayOf(1, 1, sh.toLong(), sw.toLong())))
      else inputs.put(name, Ort.tensor(imgBuf, longArrayOf(1, 3, sh.toLong(), sw.toLong())))
    }
    val out = sess.run(inputs); val outArr = flatten(out.value, 3 * hw)
    out.close(); inputs.values.forEach { it.close() }
    val mult = if ((outArr.maxOrNull() ?: 1f) <= 2f) 255f else 1f
    val outPx = IntArray(hw)
    for (i in 0 until hw) {
      outPx.set(i, (255 shl 24) or (clampI(outArr.get(i) * mult) shl 16) or (clampI(outArr.get(hw + i) * mult) shl 8) or clampI(outArr.get(2 * hw + i) * mult))
    }
    val repPx = intsToBitmap(outPx, sw, sh).resize(w, h).pixels()
    val origPx = src.pixels(); val fullMsk = mask.resize(w, h).pixels(); val finalPx = IntArray(w * h)
    for (i in finalPx.indices) {
      val mp = fullMsk.get(i)
      val mv = max(Color.alpha(mp), max(Color.red(mp), max(Color.green(mp), Color.blue(mp))))
      finalPx.set(i, if (mv > 25) repPx.get(i) else origPx.get(i))
    }
    return intsToBitmap(finalPx, w, h)
  }
  private fun flatten(v: Any?, exp: Int): FloatArray {
    if (v is java.nio.FloatBuffer) return FloatArray(v.remaining()).also { v.get(it) }
    val out = FloatArray(exp); var idx = 0
    fun walk(o: Any?) {
      if (idx >= exp || o == null) return
      when (o) {
        is FloatArray -> { val n = min(o.size, exp - idx); System.arraycopy(o, 0, out, idx, n); idx += n }
        is Array<*> -> for (el in o) walk(el)
      }
    }
    walk(v); return out
  }
}
