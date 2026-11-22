package com.nativeai.editor.ai

import android.graphics.Bitmap
import com.nativeai.editor.core.*
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.cos
import kotlin.math.sin

object Style {

  private fun labOf(b: Bitmap): Mat {
    val rgba = b.toMat()
    val rgb = Mat()
    val lab = Mat()
    Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
    Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
    rgba.release(); rgb.release()
    return lab
  }

  private fun labToBitmap(lab: Mat): Bitmap {
    val rgb = Mat()
    val rgba = Mat()
    Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB)
    Imgproc.cvtColor(rgb, rgba, Imgproc.COLOR_RGB2RGBA)
    val out = rgba.toBitmap()
    rgb.release(); rgba.release()
    return out
  }

  fun recolor(
    base: Bitmap, alpha: ByteArray, labMode: Boolean,
    hueShift: Float, satMul: Float, lightAdd: Float
  ): Bitmap {
    val w = base.width; val h = base.height
    val rgba = base.toMat()
    val rgb = Mat()
    val conv = Mat()
    Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
    Imgproc.cvtColor(rgb, conv, if (labMode) Imgproc.COLOR_RGB2Lab else Imgproc.COLOR_RGB2HSV)
    val data = ByteArray(w * h * 3)
    conv.get(0, 0, data)

    val angle = hueShift * Math.PI.toFloat() / 180f
    val ca = cos(angle); val sa = sin(angle)

    for (i in 0 until w * h) {
      val j = i * 3
      if (labMode) {
        val l = (data[j].toInt() and 255).toFloat()
        val a0 = (data[j + 1].toInt() and 255) - 128f
        val b0 = (data[j + 2].toInt() and 255) - 128f
        val ra = (a0 * ca - b0 * sa) * satMul
        val rb = (a0 * sa + b0 * ca) * satMul
        data[j] = clampI(l + lightAdd).toByte()
        data[j + 1] = clampI(ra + 128f).toByte()
        data[j + 2] = clampI(rb + 128f).toByte()
      } else {
        val hh = (data[j].toInt() and 255).toFloat()
        val ss = (data[j + 1].toInt() and 255).toFloat()
        val vv = (data[j + 2].toInt() and 255).toFloat()
        var nh = hh + hueShift / 2f
        nh = ((nh % 180f) + 180f) % 180f
        data[j] = clampI(nh).toByte()
        data[j + 1] = clampI(ss * satMul).toByte()
        data[j + 2] = clampI(vv + lightAdd).toByte()
      }
    }
    conv.put(0, 0, data)

    val backRgb = Mat()
    val backRgba = Mat()
    Imgproc.cvtColor(conv, backRgb, if (labMode) Imgproc.COLOR_Lab2RGB else Imgproc.COLOR_HSV2RGB)
    Imgproc.cvtColor(backRgb, backRgba, Imgproc.COLOR_RGB2RGBA)
    val transformed = backRgba.toBitmap()

    rgba.release(); rgb.release(); conv.release(); backRgb.release(); backRgba.release()
    return blendByAlpha(base, transformed, alpha).also { transformed.recycle() }
  }

  fun textureBlend(base: Bitmap, alpha: ByteArray, texture: Bitmap, strength: Float): Bitmap {
    val w = base.width; val h = base.height
    val texScaled = Bitmap.createScaledBitmap(texture, w, h, true)
    val baseLab = labOf(base)
    val texLab = labOf(texScaled)

    val bCh = ArrayList<Mat>(); val tCh = ArrayList<Mat>()
    Core.split(baseLab, bCh); Core.split(texLab, tCh)
    val lB = ByteArray(w * h); val lT = ByteArray(w * h)
    bCh[0].get(0, 0, lB); tCh[0].get(0, 0, lT)

    val s = clampF(strength)
    for (i in 0 until w * h) {
      val lb = (lB[i].toInt() and 255).toFloat()
      val lt = (lT[i].toInt() and 255).toFloat()
      lB[i] = clampI(lb * (1f - s) + lt * s).toByte()
    }
    bCh[0].put(0, 0, lB)
    Core.merge(bCh, baseLab)
    val transformed = labToBitmap(baseLab)

    baseLab.release(); texLab.release()
    (bCh + tCh).forEach { it.release() }
    if (texScaled !== texture) texScaled.recycle()
    return blendByAlpha(base, transformed, alpha).also { transformed.recycle() }
  }
}
