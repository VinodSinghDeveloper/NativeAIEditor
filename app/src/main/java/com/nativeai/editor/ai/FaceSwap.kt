package com.nativeai.editor.ai

import android.graphics.Bitmap
import android.graphics.RectF
import com.nativeai.editor.core.*
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.calib3d.Calib3d
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object FaceSwap {
  data class Face(val bbox: RectF, val kps: Array<FloatArray>, val score: Float)

  private val ARC_112 = arrayOf(
    floatArrayOf(38.2946f, 51.6963f), floatArrayOf(73.5318f, 51.5014f),
    floatArrayOf(56.0252f, 71.7366f), floatArrayOf(41.5493f, 92.3655f), floatArrayOf(70.7299f, 92.2041f)
  )
  private val FFHQ_512 = arrayOf(
    floatArrayOf(192.98138f, 239.94708f), floatArrayOf(318.90277f, 240.1936f),
    floatArrayOf(256.63416f, 314.01935f), floatArrayOf(201.26117f, 371.41043f), floatArrayOf(313.08905f, 371.15118f)
  )

  fun detect(img: Bitmap, detFile: File, thr: Float = 0.45f): List<Face> {
    val det = 640
    val scale = min(det.toFloat() / img.width, det.toFloat() / img.height)
    val nw = max(1, (img.width * scale).toInt()); val nh = max(1, (img.height * scale).toInt())
    val resized = Bitmap.createScaledBitmap(img, nw, nh, true)
    val px = resized.pixels()
    val input = FloatArray(3 * det * det)
    val plane = det * det
    for (y in 0 until nh) for (x in 0 until nw) {
      val p = px[y * nw + x]; val i = y * det + x
      input[i] = (((p ushr 16) and 255) - 127.5f) / 128f
      input[plane + i] = (((p ushr 8) and 255) - 127.5f) / 128f
      input[2 * plane + i] = ((p and 255) - 127.5f) / 128f
    }
    val session = Ort.session(detFile)
    val name = session.inputNames.first()
    val raw = ArrayList<Triple<Int, Int, FloatArray>>()
    withRun(detFile, mapOf(name to Ort.fTensor(longArrayOf(1, 3, det.toLong(), det.toLong()), input))) { res ->
      for (entry in res) {
        val t = entry.value as? ai.onnxruntime.OnnxTensor ?: continue
        raw.add(Triple(t.info.shape.size, t.info.shape.lastOrNull()?.toInt() ?: 0, readFloat(t)))
      }
    }
    if (resized !== img) resized.recycle()
    val faces = ArrayList<Face>()
    for (stride in intArrayOf(8, 16, 32)) {
      val h = det / stride; val w = det / stride; val anchors = 2
      var scores: FloatArray? = null; var bbox: FloatArray? = null; var kps: FloatArray? = null
      for ((dims, _, data) in raw) {
        when (data.size) {
          anchors * h * w -> scores = data
          anchors * 4 * h * w -> bbox = data
          anchors * 10 * h * w -> kps = data
        }
      }
      val sc = scores ?: continue; val bb = bbox ?: continue; val kp = kps ?: continue
      val nchw = raw.first { it.third === sc }.first == 4
      fun sAt(a: Int, y: Int, x: Int) = if (nchw) sc[(a * h + y) * w + x] else sc[(y * w + x) * anchors + a]
      fun bAt(a: Int, y: Int, x: Int, k: Int) = if (nchw) bb[((a * 4 + k) * h + y) * w + x] else bb[((y * w + x) * anchors + a) * 4 + k]
      fun kAt(a: Int, y: Int, x: Int, k: Int) = if (nchw) kp[((a * 10 + k) * h + y) * w + x] else kp[((y * w + x) * anchors + a) * 10 + k]
      for (y in 0 until h) for (x in 0 until w) for (a in 0 until anchors) {
        val s = sAt(a, y, x)
        if (s < thr) continue
        val cx = (x * stride).toFloat(); val cy = (y * stride).toFloat()
        val x1 = (cx - bAt(a, y, x, 0) * stride) / scale
        val y1 = (cy - bAt(a, y, x, 1) * stride) / scale
        val x2 = (cx + bAt(a, y, x, 2) * stride) / scale
        val y2 = (cy + bAt(a, y, x, 3) * stride) / scale
        val pts = Array(5) { i ->
          floatArrayOf((cx + kAt(a, y, x, i * 2) * stride) / scale, (cy + kAt(a, y, x, i * 2 + 1) * stride) / scale)
        }
        faces.add(Face(RectF(x1, y1, x2, y2), pts, s))
      }
    }
    return nms(faces, 0.4f)
  }

  private fun nms(faces: List<Face>, iouThr: Float): List<Face> {
    val sorted = faces.sortedByDescending { it.score }.toMutableList()
    val keep = ArrayList<Face>()
    while (sorted.isNotEmpty()) {
      val best = sorted.removeAt(0); keep.add(best)
      sorted.removeAll { iou(best.bbox, it.bbox) > iouThr }
    }
    return keep
  }

  private fun iou(a: RectF, b: RectF): Float {
    val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
    val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
    val inter = ix * iy
    return inter / (a.width() * a.height() + b.width() * b.height() - inter + 1e-6f)
  }

  private fun alignMatrix(kps: Array<FloatArray>, dst: Array<FloatArray>, size: Int, ratio: Float, dx: Float): Mat {
    val src = MatOfPoint2f(*Array(5) { Point(kps[it][0].toDouble(), kps[it][1].toDouble()) })
    val dstM = MatOfPoint2f(*Array(5) { Point((dst[it][0] * ratio + dx).toDouble(), (dst[it][1] * ratio).toDouble()) })
    val m = Calib3d.estimateAffinePartial2D(src, dstM)
    src.release(); dstM.release()
    check(!m.empty()) { "Face alignment failed" }
    return m
  }

  private fun warp(src: Bitmap, m: Mat, size: Int): Bitmap {
    val inMat = src.toMat()
    val out = Mat()
    Imgproc.warpAffine(inMat, out, m, Size(size.toDouble(), size.toDouble()), Imgproc.INTER_LINEAR, org.opencv.core.Core.BORDER_REFLECT_101, Scalar(0.0))
    inMat.release()
    val b = out.toBitmap(); out.release()
    return b
  }

  private fun embedding(img: Bitmap, face: Face, arcFile: File): FloatArray {
    val m = alignMatrix(face.kps, ARC_112, 112, 1f, 0f)
    val aligned = warp(img, m, 112); m.release()
    val input = rgbFloatNCHW(aligned, 112, 112, floatArrayOf(127.5f, 127.5f, 127.5f), floatArrayOf(127.5f, 127.5f, 127.5f))
    aligned.recycle()
    val name = Ort.session(arcFile).inputNames.first()
    val emb = withRun(arcFile, mapOf(name to Ort.fTensor(longArrayOf(1, 3, 112, 112), input))) { readFloat(firstTensor(it)) }
    var norm = 0f
    for (v in emb) norm += v * v
    norm = sqrt(norm) + 1e-9f
    return FloatArray(emb.size) { emb[it] / norm }
  }

  private var emapCache: Pair<String, FloatArray>? = null
  private fun emap(file: File): FloatArray {
    val c = emapCache
    if (c != null && c.first == file.absolutePath) return c.second
    val e = OnnxProto.emap(file)
    emapCache = file.absolutePath to e
    return e
  }

  private fun colorMatch(fake: Bitmap, target: Bitmap): Bitmap {
    val f = fake.pixels(); val t = target.pixels()
    val mf = FloatArray(3); val mt = FloatArray(3); val sf = FloatArray(3); val st = FloatArray(3)
    val n = f.size
    for (p in f) { mf[0] += (p ushr 16) and 255; mf[1] += (p ushr 8) and 255; mf[2] += p and 255 }
    for (p in t) { mt[0] += (p ushr 16) and 255; mt[1] += (p ushr 8) and 255; mt[2] += p and 255 }
    for (c in 0..2) { mf[c] /= n; mt[c] /= n }
    for (p in f) { val d = floatArrayOf(((p ushr 16) and 255) - mf[0], ((p ushr 8) and 255) - mf[1], (p and 255) - mf[2]); for (c in 0..2) sf[c] += d[c] * d[c] }
    for (p in t) { val d = floatArrayOf(((p ushr 16) and 255) - mt[0], ((p ushr 8) and 255) - mt[1], (p and 255) - mt[2]); for (c in 0..2) st[c] += d[c] * d[c] }
    for (c in 0..2) { sf[c] = sqrt(sf[c] / n) + 1e-3f; st[c] = sqrt(st[c] / n) + 1e-3f }
    val out = IntArray(n)
    for (i in 0 until n) {
      val p = f[i]
      val r = clampI((((p ushr 16) and 255) - mf[0]) * (st[0] / sf[0]) + mt[0])
      val g = clampI((((p ushr 8) and 255) - mf[1]) * (st[1] / sf[1]) + mt[1])
      val b = clampI(((p and 255) - mf[2]) * (st[2] / sf[2]) + mt[2])
      out[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
    }
    return intsToBitmap(out, fake.width, fake.height)
  }

  private fun paste(base: Bitmap, faceBmp: Bitmap, m: Mat, feather: Float): Bitmap {
    val inv = Mat(); org.opencv.imgproc.Imgproc.invertAffineTransform(m, inv)
    val faceMat = faceBmp.toMat()
    val warped = Mat()
    Imgproc.warpAffine(faceMat, warped, inv, Size(base.width.toDouble(), base.height.toDouble()), Imgproc.INTER_LINEAR, org.opencv.core.Core.BORDER_CONSTANT, Scalar(0.0))
    val maskSrc = Mat(faceBmp.height, faceBmp.width, CvType.CV_8UC1, Scalar(255.0))
    val maskW = Mat()
    Imgproc.warpAffine(maskSrc, maskW, inv, Size(base.width.toDouble(), base.height.toDouble()), Imgproc.INTER_LINEAR, org.opencv.core.Core.BORDER_CONSTANT, Scalar(0.0))
    val k = (feather * 2).toInt() * 2 + 1
    Imgproc.GaussianBlur(maskW, maskW, Size(k.toDouble(), k.toDouble()), feather.toDouble())
    val alpha = ByteArray(base.width * base.height)
    maskW.get(0, 0, alpha)
    val warpedBmp = warped.toBitmap()
    val result = blendByAlpha(base, warpedBmp, alpha)
    faceMat.release(); warped.release(); maskSrc.release(); maskW.release(); inv.release()
    warpedBmp.recycle()
    return result
  }

  fun swap(source: Bitmap, target: Bitmap, det: File, arc: File, inswapper: File, gfpgan: File?, enhance: Boolean, onProgress: (Float) -> Unit): Bitmap {
    val srcFaces = detect(source, det)
    check(srcFaces.isNotEmpty()) { "No face found in source image" }
    val srcFace = srcFaces.maxByOrNull { it.bbox.width() * it.bbox.height() }!!
    val tgtFaces = detect(target, det)
    check(tgtFaces.isNotEmpty()) { "No face found in target photo" }
    onProgress(0.15f)
    val srcEmb = embedding(source, srcFace, arc)
    val em = emap(inswapper)
    val latent = FloatArray(512)
    for (i in 0 until 512) { var s = 0f; for (j in 0 until 512) s += srcEmb[j] * em[j * 512 + i]; latent[i] = s }
    var ln = 0f; for (v in latent) ln += v * v; ln = sqrt(ln) + 1e-9f
    for (i in 0 until 512) latent[i] /= ln
    onProgress(0.3f)
    var current = target
    val swapSession = Ort.session(inswapper)
    val targetName = swapSession.inputNames.first { it.contains("target", true) }
    val sourceName = swapSession.inputNames.first { it.contains("source", true) }
    tgtFaces.forEachIndexed { idx, face ->
      val ratio = 128f / 112f
      val m = alignMatrix(face.kps, ARC_112, 128, ratio, 8f * ratio)
      val aligned = warp(current, m, 128)
      val input = rgbFloatNCHW(aligned, 128, 128, floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f))
      val fake = withRun(inswapper, mapOf(
        targetName to Ort.fTensor(longArrayOf(1, 3, 128, 128), input),
        sourceName to Ort.fTensor(longArrayOf(1, 512), latent)
      )) { res -> nchwToBitmap(readFloat(firstTensor(res)), 128, 128) }
      val matched = colorMatch(fake, aligned)
      fake.recycle()
      val pasted = paste(current, matched, m, 5f)
      matched.recycle(); aligned.recycle(); m.release()
      if (current !== target) current.recycle()
      current = pasted
      if (enhance && gfpgan != null && gfpgan.exists()) {
        val gm = alignMatrix(face.kps, FFHQ_512, 512, 1f, 0f)
        val gAligned = warp(current, gm, 512)
        val gInput = rgbFloatNCHW(gAligned, 512, 512, floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.5f, 0.5f, 0.5f))
        val gName = Ort.session(gfpgan).inputNames.first()
        val enhanced = withRun(gfpgan, mapOf(gName to Ort.fTensor(longArrayOf(1, 3, 512, 512), gInput))) { res ->
          val data = readFloat(firstTensor(res))
          val px = IntArray(512 * 512)
          val plane = 512 * 512
          for (i in px.indices) {
            val r = clampI((data[i] * 0.5f + 0.5f) * 255f)
            val g = clampI((data[plane + i] * 0.5f + 0.5f) * 255f)
            val b = clampI((data[2 * plane + i] * 0.5f + 0.5f) * 255f)
            px[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
          }
          intsToBitmap(px, 512, 512)
        }
        val gPasted = paste(current, enhanced, gm, 9f)
        enhanced.recycle(); gAligned.recycle(); gm.release()
        current.recycle()
        current = gPasted
      }
      onProgress(0.3f + 0.7f * (idx + 1) / tgtFaces.size)
    }
    return current
  }
}
