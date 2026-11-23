package com.nativeai.editor.ai

import android.graphics.Bitmap
import com.nativeai.editor.core.*
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

object Lama {
  fun inpaint(base: Bitmap, alpha: ByteArray, inverted: Boolean, model: File, onProgress: (Float) -> Unit): Bitmap {
    val w = base.width; val h = base.height
    val eff = ByteArray(alpha.size) { val a = alpha[it].toInt() and 0xFF; (if (inverted) 255 - a else a).toByte() }
    onProgress(0.1f)
    val fixed = Ort.fixedInputHW(model)
    val long = max(w, h)
    val s = if (long > 1280) 1280f / long else 1f
    val sw = fixed?.second ?: max(8, ((w * s).roundToInt() / 8) * 8)
    val sh = fixed?.first ?: max(8, ((h * s).roundToInt() / 8) * 8)
    val scaled = Bitmap.createScaledBitmap(base, sw, sh, true)
    val img = rgbFloatNCHW(scaled, sw, sh, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
    val maskSmall = Bitmap.createScaledBitmap(alphaToMask(eff, w, h), sw, sh, true)
    val ma = maskAlpha(maskSmall)
    val mask = FloatArray(sw * sh) { if ((ma[it].toInt() and 0xFF) > 127) 1f else 0f }
    onProgress(0.25f)
    val session = Ort.session(model)
    val names = session.inputNames
    val maskName = names.firstOrNull { it.contains("mask", true) } ?: names.last()
    val imgName = names.first { it != maskName }
    val inputs = mapOf(
      imgName to Ort.fTensor(longArrayOf(1, 3, sh.toLong(), sw.toLong()), img),
      maskName to Ort.fTensor(longArrayOf(1, 1, sh.toLong(), sw.toLong()), mask)
    )
    val out = withRun(model, inputs) { res ->
      val t = firstTensor(res)
      val shp = t.info.shape
      val ow = shp[shp.size - 1].toInt(); val oh = shp[shp.size - 2].toInt()
      nchwToBitmap(readFloat(t), ow, oh)
    }
    onProgress(0.8f)
    val full = if (out.width == w && out.height == h) out else Bitmap.createScaledBitmap(out, w, h, true)
    val feathered = featherAlpha(eff, w, h, 3f)
    val result = blendByAlpha(base, full, feathered)
    if (scaled !== base) scaled.recycle()
    maskSmall.recycle()
    if (out !== full) out.recycle()
    onProgress(1f)
    return result
  }
}
