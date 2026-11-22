package com.nativeai.editor.ui

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.nativeai.editor.Tool
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
fun EditorCanvas(
  bitmap: Bitmap, original: Bitmap?, mask: Bitmap, maskVersion: Int,
  maskOpacity: Float, maskInverted: Boolean, tool: Tool,
  compare: Boolean, split: Float, onSplit: (Float) -> Unit,
  cropMode: Boolean, cropRect: RectF, onCrop: (RectF) -> Unit,
  onStrokeStart: (Offset) -> Unit, onStrokeMove: (Offset) -> Unit, onStrokeEnd: () -> Unit,
  onTap: (Offset) -> Unit,
  modifier: Modifier = Modifier
) {
  var view by remember { mutableStateOf(Size.Zero) }
  var scale by remember { mutableFloatStateOf(0f) }
  var offset by remember { mutableStateOf(Offset.Zero) }

  fun fit() {
    if (view.width > 0 && view.height > 0) {
      scale = min(view.width / bitmap.width, view.height / bitmap.height)
      offset = Offset((view.width - bitmap.width * scale) / 2f, (view.height - bitmap.height * scale) / 2f)
    }
  }
  LaunchedEffect(bitmap, view) { fit() }

  val img = remember(bitmap) { bitmap.asImageBitmap() }
  val origImg = remember(original) { original?.asImageBitmap() }
  val maskImg = remember(maskVersion, mask) { mask.asImageBitmap() }

  fun toImage(p: Offset) =Offset((p.x - offset.x) / scale, (p.y - offset.y) / scale)

  Canvas(
    modifier = modifier
      .fillMaxSize()
      .clipToBounds()
      .onSizeChanged { view = Size(it.width.toFloat(), it.height.toFloat()) }
      .pointerInput(tool, bitmap, compare, cropMode) {
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          down.consume()
          val start = down.position
          var moved = false
          var multi = false
          var drawing = false
          var cropDrag = -1
          var prevCentroid = start
          var prevDist = 0f

          if (cropMode) {
            val corners = listOf(
              Offset(cropRect.left, cropRect.top), Offset(cropRect.right, cropRect.top),
              Offset(cropRect.right, cropRect.bottom), Offset(cropRect.left, cropRect.bottom)
            )
            corners.forEachIndexed { i, c ->
              val sc = Offset(
                offset.x + c.x * bitmap.width * scale,
                offset.y + c.y * bitmap.height * scale
              )
              if ((sc - start).getDistance() < 48f) cropDrag = i
            }
            val ip = toImage(start)
            val inside = ip.x / bitmap.width in cropRect.left..cropRect.right &&
              ip.y / bitmap.height in cropRect.top..cropRect.bottom
            if (cropDrag < 0 && inside) cropDrag = 4
          }

          if (!compare && !cropMode && (tool == Tool.Brush || tool == Tool.Erase)) {
            drawing = true
            onStrokeStart(toImage(start))
          }

          while (true) {
            val event = awaitPointerEvent()
            val changes = event.changes
            if (changes.all { !it.pressed }) break
            if (changes.size > 1) {
              multi = true
              if (drawing) { onStrokeEnd(); drawing = false }
            }
            val centroid = event.calculateCentroid() ?: changes[0].position
            val dist = event.calculateCentroidSize(false)
            if ((centroid - start).getDistance() > 12f) moved = true

            when {
              multi || (tool == Tool.Pan && !compare && !cropMode) -> {
                if (prevDist > 0f && dist > 0f) {
                  val zoom = (dist / prevDist).coerceIn(0.5f, 2f)
                  val newScale = (scale * zoom).coerceIn(0.05f, 20f)
                  val factor = newScale / scale
                  offset = centroid - (centroid - offset) * factor
                  scale = newScale
                }
                offset += centroid - prevCentroid
                changes.forEach { it.consume() }
              }
              compare -> {
                val f = ((changes[0].position.x - offset.x) / scale / bitmap.width).coerceIn(0f, 1f)
                onSplit(f)
                changes.forEach { it.consume() }
              }
              cropMode && cropDrag >= 0 -> {
                val p = toImage(changes[0].position)
                val nx = (p.x / bitmap.width).coerceIn(0f, 1f)
                val ny = (p.y / bitmap.height).coerceIn(0f, 1f)
                val r = RectF(cropRect)
                when (cropDrag) {
                  0 -> { r.left = min(nx, r.right - 0.05f); r.top = min(ny, r.bottom - 0.05f) }
                  1 -> { r.right = max(nx, r.left + 0.05f); r.top = min(ny, r.bottom - 0.05f) }
                  2 -> { r.right = max(nx, r.left + 0.05f); r.bottom = max(ny, r.top + 0.05f) }
                  3 -> { r.left = min(nx, r.right - 0.05f); r.bottom = max(ny, r.top + 0.05f) }
                  4 -> {
                    val d = centroid - prevCentroid
                    val dx = d.x / scale / bitmap.width
                    val dy = d.y / scale / bitmap.height
                    val w = r.width(); val h = r.height()
                    r.left = (r.left + dx).coerceIn(0f, 1f - w)
                    r.top = (r.top + dy).coerceIn(0f, 1f - h)
                    r.right = r.left + w; r.bottom = r.top + h
                  }
                }
                onCrop(r)
                changes.forEach { it.consume() }
              }
              drawing -> {
                onStrokeMove(toImage(changes[0].position))
                changes.forEach { it.consume() }
              }
            }
            prevCentroid = centroid
            prevDist = dist
          }
          if (drawing) onStrokeEnd()
          if (tool == Tool.Tap && !moved && !multi && !compare && !cropMode) onTap(toImage(start))
        }
      }
  ) {
    drawRect(Color(0xFF101014))
    if (scale <= 0f) return@Canvas
    withTransform({
      translate(offset.x, offset.y)
      scale(scale, scale, Offset.Zero)
    }) {
      drawImage(img)
      if (compare && origImg != null) {
        clipRect(left = 0f, top = 0f, right = bitmap.width * split, bottom = bitmap.height.toFloat()) {
          drawImage(
            origImg,
            srcSize = IntSize(origImg.width, origImg.height),
            dstSize = IntSize(bitmap.width, bitmap.height)
          )
        }
      }
      if (!compare && maskOpacity > 0.01f) {
        drawImage(
          maskImg,
          alpha = maskOpacity,
          colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
            if (maskInverted) Color(0xFF26C6DA) else Color(0xFFFF3B30)
          )
        )
      }
      if (cropMode) {
        val l = cropRect.left * bitmap.width; val t = cropRect.top * bitmap.height
        val r = cropRect.right * bitmap.width; val b = cropRect.bottom * bitmap.height
        val dark = Color(0xB3000000)
        drawRect(dark, topLeft = Offset.Zero, size = Size(bitmap.width.toFloat(), t))
        drawRect(dark, topLeft = Offset(0f, b), size = Size(bitmap.width.toFloat(), bitmap.height - b))
        drawRect(dark, topLeft = Offset(0f, t), size = Size(l, b - t))
        drawRect(dark, topLeft = Offset(r, t), size = Size(bitmap.width - r, b - t))
        drawRect(Color.White, topLeft = Offset(l, t), size = Size(r - l, b - t), style = Stroke(width = 2f / scale))
        val hr = 10f / scale
        listOf(Offset(l, t), Offset(r, t), Offset(r, b), Offset(l, b)).forEach { drawCircle(Color.White, hr, it) }
      }
    }
    if (compare) {
      val x = offset.x + split * bitmap.width * scale
      drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
      drawCircle(Color.White, 14f, Offset(x, size.height / 2f))
      drawCircle(Color(0xFF9C7CFF), 9f, Offset(x, size.height / 2f))
    }
  }
}
