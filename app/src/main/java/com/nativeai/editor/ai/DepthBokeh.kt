package com.nativeai.editor.ai

import android.graphics.Bitmap
import com.nativeai.editor.core.*
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.max
import kotlin.math.min

object DepthBokeh {
  fun depth(base: Bitmap, model: File): FloatArray {
    val size = 256
    val input = rgbFloatNCHW(base, size, size, floatArrayOf(0.485f, 0.456f, 0.406f), floatArrayOf(0.229f, 0.224f, 0.225f))
    val name = Ort.session(model).inputNames.first()
    val small = withRun(model, mapOf(name to Ort.fTensor(longArrayOf(1, 3, size.toLong(), size.toLong()), input))) { res ->
      val t = firstTensor(res)
      val data = readFloat(t)
      var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
      for (v in data) { if (v < mn) mn = v; if (v > mx) mx = v }
      val range = if (mx - mn > 1e-6f) mx - mn else 1f
      val m = Mat(size, size, CvType.CV_32FC1)
      val norm = FloatArray(data.size) { (data[it] - mn) / range }
      m.put(0, 0, norm)
      m
    }
    val full = Mat()
    Imgproc.resize(small, full, Size(base.width.toDouble(), base.height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
    small.release()
    val out = FloatArray(base.width * base.height)
    full.get(0, 0, out)
    full.release()
    return out
  }

  fun bokeh(base: Bitmap, depth: FloatArray, amount: Float, focus: Float, range: Float): Bitmap {
    if (amount <= 0.001f) return base
    val src = base.toMat()
    val small = Mat()
    val sw = max(1, base.width / 4); val sh = max(1, base.height / 4)
    Imgproc.resize(src, small, Size(sw.toDouble(), sh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
    val sigma = 1.0 + amount * 22.0
    Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), sigma)
    Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), sigma * 0.6)
    val blurred = Mat()
    Imgproc.resize(small, blurred, Size(base.width.toDouble(), base.height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
    small.release()
    val blurBmp = blurred.toBitmap()
    src.release(); blurred.release()
    val sharp = base.pixels(); val blur = blurBmp.pixels()
    val out = IntArray(sharp.size)
    val r = max(0.02f, range)
    for (i in sharp.indices) {
      val d = depth[i]
      var t = (d - (focus - r)) / (2f * r)
      t = clampF(t)
      t = t * t * (3f - 2f * t)
      val ia = 1f - t
      val a = sharp[i]; val b = blur[i]
      out[i] = (255 shl 24) or
        (clampI(((a ushr 16) and 255) * t + ((b ushr 16) and 255) * ia) shl 16) or
        (clampI(((a ushr 8) and 255) * t + ((b ushr 8) and 255) * ia) shl 8) or
        clampI((a and 255) * t + (b and 255) * ia)
    }
    blurBmp.recycle()
    return intsToBitmap(out, base.width, base.height)
  }
}
