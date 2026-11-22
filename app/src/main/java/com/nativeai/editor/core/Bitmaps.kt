package com.nativeai.editor.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

fun clampI(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
fun clampI(v: Float) = clampI(v.roundToInt())
fun clampF(v: Float, lo: Float = 0f, hi: Float = 1f) = if (v < lo) lo else if (v > hi) hi else v

fun decodeBitmap(context: Context, uri: Uri, maxSide: Int = 2560): Bitmap? {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
  if (bounds.outWidth <= 0) return null
  var sample = 1
  while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
  val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
  var bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
  val exif = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
  val orient = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) ?: ExifInterface.ORIENTATION_NORMAL
  val m = Matrix()
  when (orient) {
    ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
    ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
    ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
  }
  if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
  if (max(bmp.width, bmp.height) > maxSide) bmp = bmp.scaledLongSide(maxSide)
  return bmp
}

fun Bitmap.scaledLongSide(maxSide: Int): Bitmap {
  val long = max(width, height)
  if (long <= maxSide) return this
  val s = maxSide.toFloat() / long
  return Bitmap.createScaledBitmap(this, max(1, (width * s).roundToInt()), max(1, (height * s).roundToInt()), true)
}

fun Bitmap.toMat(): Mat {
  val src = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
  val m = Mat()
  Utils.bitmapToMat(src, m)
  return m
}

fun Mat.toBitmap(): Bitmap {
  val b = Bitmap.createBitmap(cols(), rows(), Bitmap.Config.ARGB_8888)
  Utils.matToBitmap(this, b)
  return b
}

fun Bitmap.pixels(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

fun intsToBitmap(px: IntArray, w: Int, h: Int): Bitmap =
  Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }

fun newMask(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

fun maskAlpha(mask: Bitmap): ByteArray {
  val px = mask.pixels()
  return ByteArray(px.size) { (px[it] ushr 24).toByte() }
}

fun alphaToMask(alpha: ByteArray, w: Int, h: Int): Bitmap {
  val px = IntArray(alpha.size) { (0xFFFFFF) or ((alpha[it].toInt() and 0xFF) shl 24) }
  return intsToBitmap(px, w, h)
}

fun pngBytes(b: Bitmap): ByteArray = ByteArrayOutputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }

fun pngDecode(bytes: ByteArray): Bitmap {
  val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
  return if (b.isMutable) b else b.copy(Bitmap.Config.ARGB_8888, true)
}

fun featherAlpha(alpha: ByteArray, w: Int, h: Int, radius: Float): ByteArray {
  if (radius < 1f) return alpha
  val m = Mat(h, w, CvType.CV_8UC1)
  m.put(0, 0, alpha)
  val k = (radius * 2).roundToInt() * 2 + 1
  Imgproc.GaussianBlur(m, m, Size(k.toDouble(), k.toDouble()), radius.toDouble())
  val out = ByteArray(alpha.size)
  m.get(0, 0, out)
  m.release()
  return out
}

fun applyAlpha(base: Bitmap, alpha: ByteArray): Bitmap {
  val px = base.pixels()
  for (i in px.indices) px[i] = (px[i] and 0x00FFFFFF) or ((alpha[i].toInt() and 0xFF) shl 24)
  return intsToBitmap(px, base.width, base.height)
}

fun blendByAlpha(base: Bitmap, other: Bitmap, alpha: ByteArray): Bitmap {
  val a = base.pixels(); val b = other.pixels()
  val out = IntArray(a.size)
  for (i in a.indices) {
    val t = (alpha[i].toInt() and 0xFF) / 255f
    val ia = 1f - t
    out[i] = (clampI(((a[i] ushr 24) and 255) * ia + ((b[i] ushr 24) and 255) * t) shl 24) or
      (clampI(((a[i] ushr 16) and 255) * ia + ((b[i] ushr 16) and 255) * t) shl 16) or
      (clampI(((a[i] ushr 8) and 255) * ia + ((b[i] ushr 8) and 255) * t) shl 8) or
      clampI((a[i] and 255) * ia + (b[i] and 255) * t)
  }
  return intsToBitmap(out, base.width, base.height)
}

fun bboxOfAlpha(alpha: ByteArray, w: Int, h: Int, pad: Int): android.graphics.Rect? {
  var minX = w; var minY = h; var maxX = -1; var maxY = -1
  for (y in 0 until h) for (x in 0 until w) {
    if ((alpha[y * w + x].toInt() and 0xFF) > 24) {
      if (x < minX) minX = x; if (x > maxX) maxX = x
      if (y < minY) minY = y; if (y > maxY) maxY = y
    }
  }
  if (maxX < 0) return null
  minX = max(0, minX - pad); minY = max(0, minY - pad)
  maxX = min(w - 1, maxX + pad); maxY = min(h - 1, maxY + pad)
  return android.graphics.Rect(minX, minY, maxX + 1, maxY + 1)
}

fun flattenWhite(b: Bitmap): Bitmap {
  val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
  val c = android.graphics.Canvas(out)
  c.drawColor(android.graphics.Color.WHITE)
  c.drawBitmap(b, 0f, 0f, null)
  return out
}

fun rgbFloatNCHW(b: Bitmap, w: Int, h: Int, mean: FloatArray, std: FloatArray, scale: Float = 1f / 255f): FloatArray {
  val src = if (b.width == w && b.height == h) b else Bitmap.createScaledBitmap(b, w, h, true)
  val px = src.pixels()
  val out = FloatArray(3 * w * h)
  val plane = w * h
  for (i in px.indices) {
    val p = px[i]
    out[i] = (((p ushr 16) and 255) * scale - mean[0]) / std[0]
    out[plane + i] = (((p ushr 8) and 255) * scale - mean[1]) / std[1]
    out[2 * plane + i] = ((p and 255) * scale - mean[2]) / std[2]
  }
  return out
}

fun nchwToBitmap(data: FloatArray, w: Int, h: Int, auto255: Boolean = true): Bitmap {
  val plane = w * h
  var maxV = 0f
  if (auto255) { var i = 0; while (i < data.size) { val v = data[i]; if (v > maxV) maxV = v; i += 97 } }
  val s = if (auto255 && maxV > 1.5f) 1f else 255f
  val px = IntArray(plane)
  for (i in 0 until plane) {
    val r = clampI(data[i] * s); val g = clampI(data[plane + i] * s); val b = clampI(data[2 * plane + i] * s)
    px[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
  }
  return intsToBitmap(px, w, h)
}

fun Bitmap.asMutableMask(): Bitmap =
  if (isMutable && config == Bitmap.Config.ARGB_8888) this
  else copy(Bitmap.Config.ARGB_8888, true)
