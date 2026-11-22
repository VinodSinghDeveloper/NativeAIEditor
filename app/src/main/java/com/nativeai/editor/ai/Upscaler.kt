package com.nativeai.editor.ai

import android.graphics.Bitmap
import com.nativeai.editor.core.*
import java.io.File
import kotlin.math.max
import kotlin.math.min

object Upscaler {
  fun upscale(base: Bitmap, model: File, maxOut: Int = 4096, onProgress: (Float) -> Unit): Bitmap {
    var input = base
    if (max(base.width, base.height) * 4 > maxOut) {
      input = base.scaledLongSide(maxOut / 4)
    }
    val fixed = Ort.fixedInputHW(model)
    val ts = fixed?.let { min(it.first, it.second) } ?: 128
    val overlap = max(8, ts / 8)
    val inW = input.width; val inH = input.height
    val name = Ort.session(model).inputNames.first()
    var scale = 4
    var outW = inW * 4; var outH = inH * 4
    val outPx = IntArray(outW * outH)
    val tilesX = (inW + ts - 1) / ts; val tilesY = (inH + ts - 1) / ts
    val total = tilesX * tilesY
    var done = 0
    for (ty in 0 until inH step ts) for (tx in 0 until inW step ts) {
      val cx0 = max(0, tx - overlap); val cy0 = max(0, ty - overlap)
      val cx1 = min(inW, tx + ts + overlap); val cy1 = min(inH, ty + ts + overlap)
      val cw = cx1 - cx0; val ch = cy1 - cy0
      val tile = Bitmap.createBitmap(input, cx0, cy0, cw, ch)
      val data = rgbFloatNCHW(tile, cw, ch, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
      val tileOut = withRun(model, mapOf(name to Ort.fTensor(longArrayOf(1, 3, ch.toLong(), cw.toLong()), data))) { res ->
        val t = firstTensor(res)
        val shp = t.info.shape
        val arr = readFloat(t)
        if (shp.size == 4 && shp[1] == 3L) {
          val oh = shp[2].toInt(); val ow = shp[3].toInt()
          scale = ow / cw
          nchwToBitmap(arr, ow, oh)
        } else if (shp.size == 4 && shp[3] == 3L) {
          val oh = shp[1].toInt(); val ow = shp[2].toInt()
          scale = ow / cw
          var mx = 0f; var i = 0
          while (i < arr.size) { if (arr[i] > mx) mx = arr[i]; i += 97 }
          val s = if (mx > 1.5f) 1f else 255f
          val px = IntArray(ow * oh) { j ->
            (255 shl 24) or (clampI(arr[j * 3] * s) shl 16) or (clampI(arr[j * 3 + 1] * s) shl 8) or clampI(arr[j * 3 + 2] * s)
          }
          intsToBitmap(px, ow, oh)
        } else error("Unexpected upscaler output shape ${shp.toList()}")
      }
      tile.recycle()
      if (done == 0) {
        outW = inW * scale; outH = inH * scale
        if (outPx.size != outW * outH) {
          return finishUpscale(input, base, model, maxOut, outW, outH, onProgress)
        }
      }
      val tpx = tileOut.pixels()
      val tow = tileOut.width
      val ex = min(tx + ts, inW); val ey = min(ty + ts, inH)
      for (y in ty until ey) for (x in tx until ex) {
        val sx = (x - cx0) * scale; val sy = (y - cy0) * scale
        for (dy in 0 until scale) for (dx in 0 until scale) {
          outPx[(y * scale + dy) * outW + x * scale + dx] = tpx[(sy + dy) * tow + sx + dx]
        }
      }
      tileOut.recycle()
      done++
      onProgress(done.toFloat() / total)
    }
    val result = intsToBitmap(outPx, outW, outH)
    if (input !== base) input.recycle()
    return result
  }

  private fun finishUpscale(input: Bitmap, base: Bitmap, model: File, maxOut: Int, outW: Int, outH: Int, onProgress: (Float) -> Unit): Bitmap {
    val outPx = IntArray(outW * outH)
    val fixed = Ort.fixedInputHW(model)
    val ts = fixed?.let { min(it.first, it.second) } ?: 128
    val overlap = max(8, ts / 8)
    val inW = input.width; val inH = input.height
    val name = Ort.session(model).inputNames.first()
    val tilesX = (inW + ts - 1) / ts; val tilesY = (inH + ts - 1) / ts
    var done = 0; val total = tilesX * tilesY
    var scale = outW / inW
    for (ty in 0 until inH step ts) for (tx in 0 until inW step ts) {
      val cx0 = max(0, tx - overlap); val cy0 = max(0, ty - overlap)
      val cx1 = min(inW, tx + ts + overlap); val cy1 = min(inH, ty + ts + overlap)
      val tile = Bitmap.createBitmap(input, cx0, cy0, cx1 - cx0, cy1 - cy0)
      val data = rgbFloatNCHW(tile, tile.width, tile.height, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
      val tileOut = withRun(model, mapOf(name to Ort.fTensor(longArrayOf(1, 3, tile.height.toLong(), tile.width.toLong()), data))) { res ->
        val t = firstTensor(res); val shp = t.info.shape
        val ow = shp[shp.size - 1].toInt(); val oh = shp[shp.size - 2].toInt()
        scale = ow / tile.width
        nchwToBitmap(readFloat(t), ow, oh)
      }
      tile.recycle()
      val tpx = tileOut.pixels(); val tow = tileOut.width
      for (y in ty until min(ty + ts, inH)) for (x in tx until min(tx + ts, inW)) {
        val sx = (x - cx0) * scale; val sy = (y - cy0) * scale
        for (dy in 0 until scale) for (dx in 0 until scale) {
          val oi = (y * scale + dy) * outW + x * scale + dx
          if (oi < outPx.size) outPx[oi] = tpx[(sy + dy) * tow + sx + dx]
        }
      }
      tileOut.recycle()
      done++; onProgress(done.toFloat() / total)
    }
    if (input !== base) input.recycle()
    return intsToBitmap(outPx, outW, outH)
  }
}
