package com.nativeai.editor.ai

import android.graphics.Bitmap
import com.nativeai.editor.core.*
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.Random
import kotlin.math.exp
import kotlin.math.sqrt

object SdTurbo {
  private fun sigma999(): Float {
    var acp = 1.0
    for (t in 0..999) {
      val beta = 0.00085 + (0.012 - 0.00085) * t / 999.0
      acp *= (1.0 - beta)
    }
    return sqrt((1.0 - acp) / acp).toFloat()
  }

  fun inpaint(
    base: Bitmap, alpha: ByteArray, prompt: String, strength: Float, files: Map<String, File>,
    onProgress: (Float) -> Unit
  ): Bitmap {
    val bbox = bboxOfAlpha(alpha, base.width, base.height, 24) ?: error("Paint or select a region first")
    val side = maxOf(bbox.width(), bbox.height())
    val sq = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(sq)
    c.drawColor(android.graphics.Color.BLACK)
    c.drawBitmap(base, -bbox.left.toFloat(), -bbox.top.toFloat(), null)
    val crop512 = Bitmap.createScaledBitmap(sq, 512, 512, true)
    sq.recycle()
    onProgress(0.05f)

    val tokenizer = ClipTokenizer(files.getValue("sd_vocab").readText(), files.getValue("sd_merges").readText())
    val ids = tokenizer.encode(prompt)
    val teSession = Ort.session(files.getValue("sd_text_encoder"))
    val teName = teSession.inputNames.first { it.contains("input_ids") || it.contains("ids") }
    val hidden = withRun(files.getValue("sd_text_encoder"), mapOf(teName to Ort.lTensor(longArrayOf(1, 77), ids))) { res ->
      var chosen: FloatArray? = null
      var chosenShape: LongArray? = null
      for (e in res) {
        val t = e.value as? ai.onnxruntime.OnnxTensor ?: continue
        val shp = t.info.shape
        if (shp.size == 3 && shp[1] == 77L) { chosen = readFloat(t); chosenShape = shp }
        else if (shp.size == 4 && shp[1] == 77L && chosen == null) {
          val layers = shp[2].toInt()
          val all = readFloat(t)
          val layer = (layers - 2).coerceAtLeast(0)
          val dim = shp[3].toInt()
          chosen = all.copyOfRange(layer * 77 * dim, (layer + 1) * 77 * dim)
          chosenShape = longArrayOf(1, 77, dim.toLong())
        }
      }
      chosen ?: error("Text encoder produced no hidden states")
      Pair(chosen!!, chosenShape!!)
    }
    onProgress(0.15f)

    val imgInput = rgbFloatNCHW(crop512, 512, 512, floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.5f, 0.5f, 0.5f))
    val veSession = Ort.session(files.getValue("sd_vae_encoder"))
    val veName = veSession.inputNames.first()
    val origLatent = withRun(files.getValue("sd_vae_encoder"), mapOf(veName to Ort.fTensor(longArrayOf(1, 3, 512, 512), imgInput))) { res ->
      val t = firstTensor(res)
      readFloat(t).map { it * 0.18215f }.toFloatArray()
    }
    onProgress(0.3f)

    val sigma = sigma999()
    val rng = Random(42L)
    val noisy = FloatArray(origLatent.size) { origLatent[it] + rng.nextGaussian().toFloat() * sigma }
    val unetFile = files.getValue("sd_unet")
    val unet = Ort.session(unetFile)
    val sampleName = unet.inputNames.first { it.contains("sample") }
    val tsName = unet.inputNames.first { it.contains("timestep") || it.contains("t") && it.length < 10 }
    val ehsName = unet.inputNames.first { it.contains("encoder_hidden_states") || it.contains("hidden") }
    val hiddenDim = hidden.second[2]
    val eps = withRun(unetFile, mapOf(
      sampleName to Ort.fTensor(longArrayOf(1, 4, 64, 64), noisy),
      tsName to Ort.tensorFor(unet, tsName, floatArrayOf(999f), longArrayOf(1)),
      ehsName to Ort.fTensor(longArrayOf(1, 77, hiddenDim), hidden.first)
    )) { res -> readFloat(firstTensor(res)) }
    onProgress(0.7f)

    val maskMat = Mat(64, 64, CvType.CV_32FC1)
    val maskFull = Mat(base.height, base.width, CvType.CV_8UC1)
    maskFull.put(0, 0, alpha)
    val maskCrop = Mat(maskFull, org.opencv.core.Rect(bbox.left, bbox.top, bbox.width(), bbox.height()))
    val maskSq = Mat()
    org.opencv.core.Core.copyMakeBorder(maskCrop, maskSq, 0, side - bbox.height(), 0, side - bbox.width(), org.opencv.core.Core.BORDER_CONSTANT, org.opencv.core.Scalar(0.0))
    Imgproc.resize(maskSq, maskMat, Size(64.0, 64.0))
    Imgproc.GaussianBlur(maskMat, maskMat, Size(5.0, 5.0), 1.5)
    val lm = FloatArray(64 * 64)
    maskMat.get(0, 0, lm)
    listOf(maskFull, maskCrop, maskSq, maskMat).forEach { it.release() }

    val s = clampF(strength, 0.05f, 1f)
    val finalLatent = FloatArray(origLatent.size)
    val plane = 64 * 64
    for (i in origLatent.indices) {
      val denoised = noisy[i] - sigma * eps[i]
      var v = origLatent[i] * (1f - s) + denoised * s
      val m = lm[i % plane]
      v = origLatent[i] * (1f - m) + v * m
      finalLatent[i] = v / 0.18215f
    }
    val vdSession = Ort.session(files.getValue("sd_vae_decoder"))
    val vdName = vdSession.inputNames.first()
    val decoded = withRun(files.getValue("sd_vae_decoder"), mapOf(vdName to Ort.fTensor(longArrayOf(1, 4, 64, 64), finalLatent))) { res ->
      val data = readFloat(firstTensor(res))
      val px = IntArray(512 * 512)
      val pl = 512 * 512
      for (i in px.indices) {
        val r = clampI((data[i] * 0.5f + 0.5f) * 255f)
        val g = clampI((data[pl + i] * 0.5f + 0.5f) * 255f)
        val b = clampI((data[2 * pl + i] * 0.5f + 0.5f) * 255f)
        px[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
      }
      intsToBitmap(px, 512, 512)
    }
    onProgress(0.9f)
    val genSq = Bitmap.createScaledBitmap(decoded, side, side, true)
    decoded.recycle()
    val genCrop = Bitmap.createBitmap(genSq, 0, 0, bbox.width(), bbox.height())
    genSq.recycle()
    val out = base.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(out)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val feathered = featherAlpha(alpha, base.width, base.height, 6f)
    val outPx = out.pixels()
    val genPx = genCrop.pixels()
    for (y in 0 until bbox.height()) for (x in 0 until bbox.width()) {
      val oi = (bbox.top + y) * base.width + bbox.left + x
      val a = (feathered[oi].toInt() and 0xFF) / 255f
      if (a <= 0.003f) continue
      val g = genPx[y * bbox.width() + x]; val o = outPx[oi]
      outPx[oi] = (255 shl 24) or
        (clampI(((o ushr 16) and 255) * (1 - a) + ((g ushr 16) and 255) * a) shl 16) or
        (clampI(((o ushr 8) and 255) * (1 - a) + ((g ushr 8) and 255) * a) shl 8) or
        clampI((o and 255) * (1 - a) + (g and 255) * a)
    }
    genCrop.recycle(); crop512.recycle()
    onProgress(1f)
    return intsToBitmap(outPx, base.width, base.height)
  }
}
