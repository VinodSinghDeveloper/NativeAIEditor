package com.nativeai.editor.ai

import android.graphics.Bitmap
import com.nativeai.editor.core.*
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

class MobileSam(private val encFile: File, private val decFile: File) {
  private var emb: OnnxTensorHolder? = null
  private var key: Bitmap? = null
  private var scale = 1f

  private class OnnxTensorHolder(val tensor: ai.onnxruntime.OnnxTensor) { fun close() = tensor.close() }

  fun ensureEmbedding(bmp: Bitmap) {
    if (key === bmp && emb != null) return
    emb?.close()
    val long = max(bmp.width, bmp.height)
    scale = 1024f / long
    val nw = (bmp.width * scale).roundToInt(); val nh = (bmp.height * scale).roundToInt()
    val resized = Bitmap.createScaledBitmap(bmp, nw, nh, true)
    val px = resized.pixels()
    val mean = floatArrayOf(123.675f, 116.28f, 103.53f)
    val std = floatArrayOf(58.395f, 57.12f, 57.375f)
    val data = FloatArray(3 * 1024 * 1024)
    val plane = 1024 * 1024
    for (y in 0 until nh) for (x in 0 until nw) {
      val p = px[y * nw + x]; val i = y * 1024 + x
      data[i] = (((p ushr 16) and 255) - mean[0]) / std[0]
      data[plane + i] = (((p ushr 8) and 255) - mean[1]) / std[1]
      data[2 * plane + i] = ((p and 255) - mean[2]) / std[2]
    }
    val enc = Ort.session(encFile)
    val inputName = enc.inputNames.first()
    val t = withRun(encFile, mapOf(inputName to Ort.fTensor(longArrayOf(1, 3, 1024, 1024), data))) { res ->
      copyOut(firstTensor(res))
    }
    emb = OnnxTensorHolder(t)
    key = bmp
    if (resized !== bmp) resized.recycle()
  }

  fun tap(bmp: Bitmap, x: Float, y: Float): ByteArray {
    ensureEmbedding(bmp)
    val e = emb!!.tensor
    val dec = Ort.session(decFile)
    val inputs = HashMap<String, ai.onnxruntime.OnnxTensor>()
    for (name in dec.inputNames) {
      when {
        name.contains("image_embeddings") -> inputs[name] = e
        name.contains("point_coords") -> inputs[name] = Ort.fTensor(longArrayOf(1, 1, 2), floatArrayOf(x * scale, y * scale))
        name.contains("point_labels") -> inputs[name] = Ort.fTensor(longArrayOf(1, 1), floatArrayOf(1f))
        name.contains("mask_input") -> inputs[name] = Ort.fTensor(longArrayOf(1, 1, 256, 256), FloatArray(256 * 256))
        name.contains("has_mask_input") -> inputs[name] = Ort.fTensor(longArrayOf(1), floatArrayOf(0f))
        name.contains("orig_im_size") -> inputs[name] = Ort.tensorFor(dec, name, floatArrayOf(bmp.height.toFloat(), bmp.width.toFloat()), longArrayOf(1, 2))
      }
    }
    val owned = inputs.filterValues { it !== e }
    val res = dec.run(inputs)
    try {
      var bestIdx = 0
      var masks: ai.onnxruntime.OnnxTensor? = null
      for (entry in res) {
        val t = entry.value as? ai.onnxruntime.OnnxTensor ?: continue
        val shp = t.info.shape
        if (entry.key.contains("iou") && shp.isNotEmpty()) {
          val ious = readFloat(t)
          bestIdx = ious.indices.maxByOrNull { ious[it] } ?: 0
        } else if (shp.size == 4 && shp[0] == 1L) masks = t
      }
      val m = masks ?: firstTensor(res)
      val shp = m.info.shape
      val mh = shp[shp.size - 2].toInt(); val mw = shp[shp.size - 1].toInt()
      val n = if (shp.size == 4) shp[1].toInt() else 1
      val idx = bestIdx.coerceIn(0, n - 1)
      val data = readFloat(m)
      val plane = mh * mw
      val small = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
      val px = IntArray(plane) {
        val on = data[idx * plane + it] > 0f
        if (on) (255 shl 24) or 0xFFFFFF else 0
      }
      small.setPixels(px, 0, mw, 0, 0, mw, mh)
      val full = if (mw == bmp.width && mh == bmp.height) small else Bitmap.createScaledBitmap(small, bmp.width, bmp.height, true)
      val alpha = maskAlpha(full)
      if (full !== small) full.recycle()
      small.recycle()
      return featherAlpha(alpha, bmp.width, bmp.height, 2f)
    } finally {
      res.close()
      owned.values.forEach { it.close() }
    }
  }

  fun close() { emb?.close(); emb = null; key = null }
}
