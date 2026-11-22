package com.nativeai.editor.ai

import android.graphics.Bitmap
import android.graphics.Matrix
import com.nativeai.editor.core.*
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.sqrt

data class AdjustParams(
  val exposure: Float = 0f,
  val contrast: Float = 0f,
  val highlights: Float = 0f,
  val shadows: Float = 0f,
  val saturation: Float = 0f,
  val vibrance: Float = 0f,
  val temperature: Float = 0f,
  val sharpness: Float = 0f,
  val vignette: Float = 0f
) {
  fun isIdentity() = exposure == 0f && contrast == 0f && highlights == 0f && shadows == 0f &&
    saturation == 0f && vibrance == 0f && temperature == 0f && sharpness == 0f && vignette == 0f
}

object Adjust {
  fun apply(base: Bitmap, p: AdjustParams): Bitmap {
    if (p.isIdentity()) return base
    val w = base.width; val h = base.height
    val px = base.pixels()
    val exp = Math.pow(2.0, p.exposure.toDouble()).toFloat()
    val con = 1f + p.contrast
    val temp = p.temperature * 30f
    val cx = w / 2f; val cy = h / 2f
    val maxD = sqrt(cx * cx + cy * cy)
    for (y in 0 until h) for (x in 0 until w) {
      val i = y * w + x
      val src = px[i]
      var r = ((src ushr 16) and 255).toFloat()
      var g = ((src ushr 8) and 255).toFloat()
      var b = (src and 255).toFloat()
      r *= exp; g *= exp; b *= exp
      r += temp; b -= temp
      r = (r - 128f) * con + 128f; g = (g - 128f) * con + 128f; b = (b - 128f) * con + 128f
      val lum = 0.299f * r + 0.587f * g + 0.114f * b
      if (p.highlights != 0f && lum > 128f) {
        val t = (lum - 128f) / 127f
        val d = p.highlights * t * t * 70f
        r += d; g += d; b += d
      }
      if (p.shadows != 0f && lum < 128f) {
        val t = (128f - lum) / 128f
        val d = p.shadows * t * t * 70f
        r += d; g += d; b += d
      }
      if (p.saturation != 0f) {
        val l = 0.299f * r + 0.587f * g + 0.114f * b
        val s = 1f + p.saturation
        r = l + (r - l) * s; g = l + (g - l) * s; b = l + (b - l) * s
      }
      if (p.vibrance != 0f) {
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        val satNorm = (mx - mn) / 255f
        val l = 0.299f * r + 0.587f * g + 0.114f * b
        val s = 1f + p.vibrance * (1f - satNorm)
        r = l + (r - l) * s; g = l + (g - l) * s; b = l + (b - l) * s
      }
      if (p.vignette > 0f) {
        val dx = x - cx; val dy = y - cy
        val d = sqrt(dx * dx + dy * dy) / maxD
        var t = clampF((d - 0.45f) / 0.55f)
        t = t * t
        val f = 1f - p.vignette * t * 0.85f
        r *= f; g *= f; b *= f
      }
      px[i] = ((src ushr 24) shl 24) or (clampI(r) shl 16) or (clampI(g) shl 8) or clampI(b)
    }
    var result = intsToBitmap(px, w, h)
    if (p.sharpness > 0f) {
      val srcMat = result.toMat()
      val blur = org.opencv.core.Mat()
      Imgproc.GaussianBlur(srcMat, blur, Size(0.0, 0.0), 1.4)
      val out = org.opencv.core.Mat()
      val a = p.sharpness * 1.3
      org.opencv.core.Core.addWeighted(srcMat, 1.0 + a, blur, -a, 0.0, out)
      val sharpened = out.toBitmap()
      srcMat.release(); blur.release(); out.release()
      result.recycle()
      result = sharpened
    }
    return result
  }

  fun rotate90(b: Bitmap): Bitmap {
    val m = Matrix(); m.postRotate(90f)
    return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
  }

  fun flip(b: Bitmap, horizontal: Boolean): Bitmap {
    val m = Matrix(); m.postScale(if (horizontal) -1f else 1f, if (horizontal) 1f else -1f)
    return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
  }

  fun crop(b: Bitmap, l: Float, t: Float, r: Float, bo: Float): Bitmap {
    val x = (l * b.width).toInt().coerceIn(0, b.width - 1)
    val y = (t * b.height).toInt().coerceIn(0, b.height - 1)
    val x2 = (r * b.width).toInt().coerceIn(x + 1, b.width)
    val y2 = (bo * b.height).toInt().coerceIn(y + 1, b.height)
    return Bitmap.createBitmap(b, x, y, x2 - x, y2 - y)
  }
}
