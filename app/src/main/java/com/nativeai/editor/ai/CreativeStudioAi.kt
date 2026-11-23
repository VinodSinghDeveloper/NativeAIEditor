package com.nativeai.editor.ai

import android.graphics.Bitmap
import android.graphics.Color
import com.nativeai.editor.core.*
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import java.io.File
import kotlin.math.*

object CreativeStudioAi {

  fun runStyleOnnx(src: Bitmap, modelFile: File): Bitmap {
    val sess = Ort.session(modelFile)
    val fixed = Ort.fixedInputHW(modelFile)
    val nhwc = Ort.isNhwcInput(modelFile)
    val w = src.width; val h = src.height
    val scale = if (max(w, h) > 768) 768f / max(w, h) else 1f
    val sw = fixed?.second ?: max(32, ((w * scale).roundToInt() / 32) * 32)
    val sh = fixed?.first ?: max(32, ((h * scale).roundToInt() / 32) * 32)
    val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
    val px = IntArray(sw * sh); scaled.getPixels(px, 0, sw, 0, 0, sw, sh)
    val hw = sw * sh; val buf = FloatArray(3 * hw)
    for (i in 0 until hw) {
      val p = px.get(i)
      val r = (((p ushr 16) and 255) / 127.5f) - 1f
      val g = (((p ushr 8) and 255) / 127.5f) - 1f
      val b = ((p and 255) / 127.5f) - 1f
      if (nhwc) { buf.set(i * 3, r); buf.set(i * 3 + 1, g); buf.set(i * 3 + 2, b) }
      else { buf.set(i, r); buf.set(hw + i, g); buf.set(2 * hw + i, b) }
    }
    val shape = if (nhwc) longArrayOf(1, sh.toLong(), sw.toLong(), 3) else longArrayOf(1, 3, sh.toLong(), sw.toLong())
    val inName = sess.inputNames.first()
    val t = Ort.fTensor(shape, buf)
    val res = sess.run(mapOf(inName to t))
    val raw = Ort.readFloatAny(res)
    res.close(); t.close()
    val outPx = IntArray(hw)
    for (i in 0 until hw) {
      val r = clampI(((if (nhwc) raw.get(i * 3) else raw.get(i)) + 1f) * 127.5f)
      val g = clampI(((if (nhwc) raw.get(i * 3 + 1) else raw.get(hw + i)) + 1f) * 127.5f)
      val b = clampI(((if (nhwc) raw.get(i * 3 + 2) else raw.get(2 * hw + i)) + 1f) * 127.5f)
      outPx.set(i, (255 shl 24) or (r shl 16) or (g shl 8) or b)
    }
    return Bitmap.createScaledBitmap(intsToBitmap(outPx, sw, sh), w, h, true)
  }

  fun nativeCartoon(src: Bitmap, mode: Int): Bitmap {
    val rgba = Mat(); Utils.bitmapToMat(src, rgba)
    val bgr = Mat(); Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR); val dst = Mat()
    when (mode) {
      0 -> {
        val color = Mat(); Imgproc.bilateralFilter(bgr, color, 9, 75.0, 75.0)
        val gray = Mat(); Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY); Imgproc.medianBlur(gray, gray, 7)
        val edges = Mat(); Imgproc.adaptiveThreshold(gray, edges, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY, 9, 2.0)
        val eb = Mat(); Imgproc.cvtColor(edges, eb, Imgproc.COLOR_GRAY2BGR); Core.bitwise_and(color, eb, dst)
      }
      1 -> {
        val gray = Mat(); Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val inv = Mat(); Core.bitwise_not(gray, inv)
        val blur = Mat(); Imgproc.GaussianBlur(inv, blur, Size(21.0, 21.0), 0.0)
        val sk = Mat(); Core.divide(gray, blur, sk, 256.0); Imgproc.cvtColor(sk, dst, Imgproc.COLOR_GRAY2BGR)
      }
      else -> {
        val gray = Mat(); Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val eq = Mat(); Imgproc.createCLAHE(2.2, Size(8.0, 8.0)).apply(gray, eq)
        Imgproc.cvtColor(eq, dst, Imgproc.COLOR_GRAY2BGR)
      }
    }
    val outRgba = Mat(); Imgproc.cvtColor(dst, outRgba, Imgproc.COLOR_BGR2RGBA)
    return Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(outRgba, it) }
  }

  fun colorizeBw(src: Bitmap, modelFile: File?): Bitmap {
    if (modelFile == null || !modelFile.exists()) {
      val rgba = Mat(); Utils.bitmapToMat(src, rgba)
      val lab = Mat(); Imgproc.cvtColor(rgba, lab, Imgproc.COLOR_RGBA2RGB); Imgproc.cvtColor(lab, lab, Imgproc.COLOR_RGB2Lab)
      val ch = mutableListOf<Mat>(); Core.split(lab, ch)
      Core.add(ch.get(1), Scalar(8.0), ch.get(1)); Core.add(ch.get(2), Scalar(14.0), ch.get(2))
      Core.merge(ch, lab); Imgproc.cvtColor(lab, rgba, Imgproc.COLOR_Lab2RGB); Imgproc.cvtColor(rgba, rgba, Imgproc.COLOR_RGB2RGBA)
      return Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(rgba, it) }
    }
    val sess = Ort.session(modelFile)
    val fixed = Ort.fixedInputHW(modelFile) ?: (256 to 256)
    val sh = fixed.first; val sw = fixed.second
    val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
    val px = IntArray(sw * sh); scaled.getPixels(px, 0, sw, 0, 0, sw, sh)
    val hw = sw * sh; val buf = FloatArray(3 * hw)
    for (i in 0 until hw) {
      val p = px.get(i)
      val l = (0.299f * ((p ushr 16) and 255) + 0.587f * ((p ushr 8) and 255) + 0.114f * (p and 255)) / 255f
      buf.set(i, l); buf.set(hw + i, l); buf.set(2 * hw + i, l)
    }
    val inName = sess.inputNames.first()
    val t = Ort.fTensor(longArrayOf(1, 3, sh.toLong(), sw.toLong()), buf)
    val res = sess.run(mapOf(inName to t))
    val raw = Ort.readFloatAny(res)
    res.close(); t.close()
    val mult = if ((raw.maxOrNull() ?: 1f) <= 2f) 255f else 1f
    val outPx = IntArray(hw)
    for (i in 0 until hw) outPx.set(i, (255 shl 24) or (clampI(raw.get(i) * mult) shl 16) or (clampI(raw.get(hw + i) * mult) shl 8) or clampI(raw.get(2 * hw + i) * mult))
    return Bitmap.createScaledBitmap(intsToBitmap(outPx, sw, sh), src.width, src.height, true)
  }

  fun restoreOldPhoto(src: Bitmap, gfpganFile: File?, detFile: File?): Bitmap {
    val rgba = Mat(); Utils.bitmapToMat(src, rgba)
    val bgr = Mat(); Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR); val clean = Mat()
    Photo.fastNlMeansDenoisingColored(bgr, clean, 6f, 6f, 7, 21)
    val gray = Mat(); Imgproc.cvtColor(clean, gray, Imgproc.COLOR_BGR2GRAY); val topHat = Mat()
    Imgproc.morphologyEx(gray, topHat, Imgproc.MORPH_TOPHAT, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0)))
    val mask = Mat(); Imgproc.threshold(topHat, mask, 45.0, 255.0, Imgproc.THRESH_BINARY)
    val healed = Mat(); Photo.inpaint(clean, mask, healed, 3.0, Photo.INPAINT_TELEA)
    val lab = Mat(); Imgproc.cvtColor(healed, lab, Imgproc.COLOR_BGR2Lab)
    val ch = mutableListOf<Mat>(); Core.split(lab, ch); Imgproc.createCLAHE(1.8, Size(8.0, 8.0)).apply(ch.get(0), ch.get(0))
    Core.merge(ch, lab); Imgproc.cvtColor(lab, healed, Imgproc.COLOR_Lab2BGR); Imgproc.cvtColor(healed, rgba, Imgproc.COLOR_BGR2RGBA)
    return Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(rgba, it) }
  }

  fun autoRemoveCrowd(src: Bitmap, segFile: File, lamaFile: File, keepMainSubject: Boolean = true): Bitmap {
    val modelId = if (segFile.name.contains("rmbg", true)) "rmbg" else "u2netp"
    val alpha = Segmenter.foregroundAlpha(src, modelId, segFile)
    val w = src.width; val h = src.height
    val bin = Mat(h, w, CvType.CV_8UC1)
    val binBytes = ByteArray(w * h) { i -> if ((alpha.get(i).toInt() and 255) > 90) 255.toByte() else 0 }
    bin.put(0, 0, binBytes)

    val labels = Mat(); val stats = Mat(); val centroids = Mat()
    val n = Imgproc.connectedComponentsWithStats(bin, labels, stats, centroids)
    if (n <= 1) return src
    var mainLabel = 1; var maxArea = 0
    for (i in 1 until n) { val a = stats.get(i, Imgproc.CC_STAT_AREA).get(0).toInt(); if (a > maxArea) { maxArea = a; mainLabel = i } }

    val erasePx = IntArray(w * h); val lblArr = IntArray(w * h); labels.get(0, 0, lblArr); var found = false
    for (i in 0 until w * h) {
      val l = lblArr.get(i)
      if (if (keepMainSubject) (l > 0 && l != mainLabel) else (l > 0)) { erasePx.set(i, Color.argb(255, 255, 59, 48)); found = true }
    }
    if (!found) return src
    val eMat = Mat(h, w, CvType.CV_8UC1)
    val eBytes = ByteArray(w * h) { i -> if (erasePx.get(i) != 0) 255.toByte() else 0 }
    eMat.put(0, 0, eBytes)
    Imgproc.dilate(eMat, eMat, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(15.0, 15.0)))
    eMat.get(0, 0, eBytes)
    return Lama.inpaint(src, eBytes, false, lamaFile) {}
  }

  fun applyPreset(src: Bitmap, preset: String): Bitmap {
    if (preset == "Original") return src
    val rgba = Mat(); Utils.bitmapToMat(src, rgba); val bgr = Mat(); Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
    val f = Mat(); bgr.convertTo(f, CvType.CV_32FC3, 1.0 / 255.0); val ch = mutableListOf<Mat>(); Core.split(f, ch)
    when (preset) {
      "Teal & Orange" -> { Core.multiply(ch.get(2), Scalar(1.12), ch.get(2)); Core.multiply(ch.get(0), Scalar(1.08), ch.get(0)); Core.subtract(ch.get(1), Scalar(0.03), ch.get(1)) }
      "Kodak Gold 200" -> { Core.multiply(ch.get(2), Scalar(1.15), ch.get(2)); Core.multiply(ch.get(1), Scalar(1.06), ch.get(1)); Core.multiply(ch.get(0), Scalar(0.88), ch.get(0)) }
      "Moody Dark" -> { Core.multiply(ch.get(0), Scalar(0.84), ch.get(0)); Core.multiply(ch.get(1), Scalar(0.84), ch.get(1)); Core.multiply(ch.get(2), Scalar(0.88), ch.get(2)) }
      "Cyberpunk Neon" -> { Core.multiply(ch.get(0), Scalar(1.22), ch.get(0)); Core.multiply(ch.get(2), Scalar(1.18), ch.get(2)); Core.multiply(ch.get(1), Scalar(0.92), ch.get(1)) }
      "Golden Hour" -> { Core.add(ch.get(2), Scalar(0.08), ch.get(2)); Core.add(ch.get(1), Scalar(0.04), ch.get(1)); Core.multiply(ch.get(0), Scalar(0.85), ch.get(0)) }
      "Vintage 90s" -> { Core.add(ch.get(0), Scalar(0.06), ch.get(0)); Core.add(ch.get(1), Scalar(0.05), ch.get(1)); Core.add(ch.get(2), Scalar(0.04), ch.get(2)); Core.multiply(ch.get(2), Scalar(0.94), ch.get(2)) }
      "HDR Vivid" -> {
        Core.merge(ch, f); val hsv = Mat(); Imgproc.cvtColor(f, hsv, Imgproc.COLOR_BGR2HSV)
        val hch = mutableListOf<Mat>(); Core.split(hsv, hch); Core.multiply(hch.get(1), Scalar(1.32), hch.get(1))
        Core.merge(hch, hsv); Imgproc.cvtColor(hsv, f, Imgproc.COLOR_HSV2BGR); Core.split(f, ch)
      }
    }
    Core.merge(ch, f); val outBgr = Mat(); f.convertTo(outBgr, CvType.CV_8UC3, 255.0); Imgproc.cvtColor(outBgr, rgba, Imgproc.COLOR_BGR2RGBA)
    return Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(rgba, it) }
  }
}
