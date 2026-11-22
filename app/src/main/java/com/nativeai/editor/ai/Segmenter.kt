package com.nativeai.editor.ai

import android.graphics.Bitmap
import com.nativeai.editor.core.*
import java.io.File

object Segmenter {
  fun foregroundAlpha(base: Bitmap, modelId: String, model: File): ByteArray {
    val size = if (modelId == "rmbg") 1024 else 320
    val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    val std = floatArrayOf(0.229f, 0.224f, 0.225f)
    val input = rgbFloatNCHW(base, size, size, mean, std)
    val session = Ort.session(model)
    val name = session.inputNames.first()
    val alphaSmall = withRun(model, mapOf(name to Ort.fTensor(longArrayOf(1, 3, size.toLong(), size.toLong()), input))) { res ->
      val t = firstTensor(res)
      val data = readFloat(t)
      var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
      for (v in data) { if (v < mn) mn = v; if (v > mx) mx = v }
      val range = if (mx - mn > 1e-6f) mx - mn else 1f
      val px = IntArray(size * size) {
        val a = clampI(((data[it] - mn) / range) * 255f)
        (a shl 24) or 0xFFFFFF
      }
      intsToBitmap(px, size, size)
    }
    val full = Bitmap.createScaledBitmap(alphaSmall, base.width, base.height, true)
    val alpha = maskAlpha(full)
    alphaSmall.recycle(); full.recycle()
    return alpha
  }
}
