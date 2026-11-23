package com.nativeai.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nativeai.editor.ai.*
import com.nativeai.editor.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Tool { Brush, Erase, Pan, Tap }
enum class Panel { None, Erase, Select, Cutout, Face, Upscale, Portrait, Style, Adjust, Export }

data class UiState(
  val bitmap: Bitmap? = null,
  val original: Bitmap? = null,
  val maskVersion: Int = 0,
  val maskInverted: Boolean = false,
  val tool: Tool = Tool.Brush,
  val brushSize: Float = 60f,
  val feather: Float = 0.25f,
  val maskOpacity: Float = 0.5f,
  val compare: Boolean = false,
  val split: Float = 0.5f,
  val panel: Panel = Panel.None,
  val busy: String? = null,
  val progress: Float? = null,
  val message: String? = null,
  val showModels: Boolean = false,
  val showFirstRun: Boolean = false,
  val canUndo: Boolean = false,
  val canRedo: Boolean = false,
  val adjust: AdjustParams = AdjustParams(),
  val cropMode: Boolean = false,
  val cropRect: RectF = RectF(0.1f, 0.1f, 0.9f, 0.9f),
  val bokehAmount: Float = 0.5f,
  val bokehFocus: Float = 0.6f,
  val bokehRange: Float = 0.2f,
  val styleHue: Float = 0f,
  val styleSat: Float = 1f,
  val styleLight: Float = 0f,
  val styleLab: Boolean = true,
  val textureStrength: Float = 0.5f,
  val sdPrompt: String = "a red silk dress, studio lighting, high detail",
  val sdStrength: Float = 0.6f,
  val exportFormat: Int = 0,
  val exportQuality: Int = 95,
  val faceEnhance: Boolean = true,
  val cutoutModel: String = "u2netp",
  val hasSourceFace: Boolean = false,
  val hasTexture: Boolean = false,
  val depthReady: Boolean = false
)

class AppViewModel(app: Application) : AndroidViewModel(app) {


  var ui by mutableStateOf(UiState())
    private set
  val downloader = ModelDownloader(app)

  var mask: Bitmap = newMask(2, 2)
    private set

  private class Snap(val result: ByteArray, val mask: ByteArray)
  private val undoStack = ArrayDeque<Snap>()
  private val redoStack = ArrayDeque<Snap>()
  private var lastResultBmp: Bitmap? = null
  private var lastResultPng: ByteArray? = null

  private var sam: MobileSam? = null
  private var adjustBase: Bitmap? = null
  private var adjustJob: Job? = null
  private var portraitBase: Bitmap? = null
  private var depth: FloatArray? = null
  private var sourceFace: Bitmap? = null
  private var texture: Bitmap? = null
  private var strokeLast: Offset? = null
  private var strokePaint: Paint? = null

  init {
    if (downloader.firstRunPending()) ui = ui.copy(showFirstRun = true)
  }

  private fun msg(text: String) { ui = ui.copy(message = text) }
  fun clearMessage() { ui = ui.copy(message = null) }
  fun set(p: Panel) {
    if (p == Panel.Adjust && ui.panel != Panel.Adjust) {
      ui.bitmap?.let { pushUndo(); adjustBase = it }
    }
    if (p == Panel.Portrait && ui.panel != Panel.Portrait) {
      portraitBase = ui.bitmap; depth = null
      ui = ui.copy(depthReady = false)
    }
    ui = ui.copy(panel = if (ui.panel == p) Panel.None else p, cropMode = false)
  }
  fun setTool(t: Tool) { ui = ui.copy(tool = t) }
  fun setBrush(size: Float, feather: Float, opacity: Float) { ui = ui.copy(brushSize = size, feather = feather, maskOpacity = opacity) }
  fun toggleInvert() { ui = ui.copy(maskInverted = !ui.maskInverted) }
  fun toggleCompare() { ui = ui.copy(compare = !ui.compare) }
  fun setSplit(f: Float) { ui = ui.copy(split = f) }
  fun showModels(v: Boolean) { ui = ui.copy(showModels = v) }
  fun setCutoutModel(id: String) { ui = ui.copy(cutoutModel = id) }
  fun setFaceEnhance(v: Boolean) { ui = ui.copy(faceEnhance = v) }
  fun setExport(format: Int, quality: Int) { ui = ui.copy(exportFormat = format, exportQuality = quality) }
  fun setStyle(lab: Boolean, hue: Float, sat: Float, light: Float, texStrength: Float) {
    ui = ui.copy(styleLab = lab, styleHue = hue, styleSat = sat, styleLight = light, textureStrength = texStrength)
  }
  fun setSd(prompt: String, strength: Float) { ui = ui.copy(sdPrompt = prompt, sdStrength = strength) }
  fun setBokeh(amount: Float, focus: Float, range: Float) {
    ui = ui.copy(bokehAmount = amount, bokehFocus = focus, bokehRange = range)
    applyBokehDebounced()
  }
  fun setCropRect(r: RectF) { ui = ui.copy(cropRect = r) }
  fun setCropMode(v: Boolean) { ui = ui.copy(cropMode = v) }

  private fun invalidateCaches() {
    sam?.close(); sam = null
    depth = null; portraitBase = null
    ui = ui.copy(depthReady = false)
  }

  private fun snapCurrent(): Snap {
    val bmp = ui.bitmap ?: error("no image")
    val r = if (bmp !== lastResultBmp || lastResultPng == null) {
      pngBytes(bmp).also { lastResultPng = it; lastResultBmp = bmp }
    } else lastResultPng!!
    return Snap(r, pngBytes(mask))
  }

  private fun trimStack() {
    var bytes = undoStack.sumOf { it.result.size.toLong() + it.mask.size }
    while (undoStack.size > 12 || (bytes > 64L * 1024 * 1024 && undoStack.size > 1)) {
      val old = undoStack.removeFirst()
      bytes -= old.result.size + old.mask.size
    }
  }

  private fun pushUndo() {
    if (ui.bitmap == null) return
    undoStack.addLast(snapCurrent())
    redoStack.clear()
    trimStack()
    ui = ui.copy(canUndo = true, canRedo = false)
  }

  private fun restore(s: Snap) {
    val bmp = pngDecode(s.result)
    val m = pngDecode(s.mask)
    mask = m
    lastResultBmp = bmp; lastResultPng = s.result
    invalidateCaches()
    ui = ui.copy(bitmap = bmp, maskVersion = ui.maskVersion + 1, adjust = AdjustParams())
    adjustBase = bmp
  }

  fun undo() {
    if (undoStack.isEmpty() || ui.bitmap == null) return
    redoStack.addLast(snapCurrent())
    restore(undoStack.removeLast())
    ui = ui.copy(canUndo = undoStack.isNotEmpty(), canRedo = true)
  }

  fun redo() {
    if (redoStack.isEmpty() || ui.bitmap == null) return
    undoStack.addLast(snapCurrent())
    restore(redoStack.removeLast())
    ui = ui.copy(canUndo = true, canRedo = redoStack.isNotEmpty())
  }

  fun loadPhoto(uri: Uri) = viewModelScope.launch(Dispatchers.Default) {
    ui = ui.copy(busy = "Loading photo…")
    try {
      val bmp = decodeBitmap(getApplication(), uri, 2560) ?: error("Could not decode image")
      val editable = if (bmp.isMutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true)
      mask = newMask(editable.width, editable.height)
      undoStack.clear(); redoStack.clear()
      lastResultBmp = null; lastResultPng = null
      invalidateCaches()
      adjustBase = editable
      ui = ui.copy(
        bitmap = editable, original = editable, maskVersion = ui.maskVersion + 1,
        busy = null, canUndo = false, canRedo = false, panel = Panel.None,
        adjust = AdjustParams(), cropMode = false, compare = false
      )
    } catch (e: Exception) {
      ui = ui.copy(busy = null); msg("Load failed: ${e.message}")
    }
  }

  fun setSourceFace(uri: Uri) = viewModelScope.launch(Dispatchers.Default) {
    val bmp = decodeBitmap(getApplication(), uri, 1600)
    if (bmp == null) msg("Could not decode source face") else {
      sourceFace = bmp
      ui = ui.copy(hasSourceFace = true)
      msg("Source face loaded")
    }
  }

  fun setTexture(uri: Uri) = viewModelScope.launch(Dispatchers.Default) {
    val bmp = decodeBitmap(getApplication(), uri, 1024)
    if (bmp == null) msg("Could not decode texture") else {
      texture = bmp
      ui = ui.copy(hasTexture = true)
      msg("Texture loaded")
    }
  }

  private fun makePaint(erase: Boolean): Paint {
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE
    p.style = Paint.Style.STROKE
    p.strokeWidth = ui.brushSize
    p.strokeCap = Paint.Cap.ROUND
    p.strokeJoin = Paint.Join.ROUND
    val r = ui.brushSize * ui.feather * 0.5f
    if (r >= 0.5f) p.maskFilter = BlurMaskFilter(r, BlurMaskFilter.Blur.NORMAL)
    if (erase) p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    return p
  }

  fun strokeStart(p: Offset) {
    if (ui.bitmap == null) return
    pushUndo()
    strokePaint = makePaint(ui.tool == Tool.Erase)
    strokeLast = p
    val c = Canvas(mask)
    c.drawCircle(p.x, p.y, ui.brushSize / 2f, strokePaint!!.apply { style = Paint.Style.FILL })
    strokePaint!!.style = Paint.Style.STROKE
    ui = ui.copy(maskVersion = ui.maskVersion + 1)
  }

  fun strokeMove(p: Offset) {
    val last = strokeLast ?: return
    val paint = strokePaint ?: return
    Canvas(mask).drawLine(last.x, last.y, p.x, p.y, paint)
    strokeLast = p
    ui = ui.copy(maskVersion = ui.maskVersion + 1)
  }

  fun strokeEnd() { strokeLast = null; strokePaint = null }

  fun clearMask() {
    pushUndo()
    mask.eraseColor(android.graphics.Color.TRANSPARENT)
    ui = ui.copy(maskVersion = ui.maskVersion + 1)
  }

  private fun requireModel(id: String): File? {
    if (!downloader.isReady(id)) {
      msg("Model not downloaded. Open Models to download or import it.")
      ui = ui.copy(showModels = true)
      return null
    }
    return downloader.file(id)
  }

  private fun runOp(name: String, block: suspend (progress: (Float) -> Unit) -> Unit) {
    if (ui.busy != null) return
    viewModelScope.launch(Dispatchers.Default) {
      ui = ui.copy(busy = name, progress = 0f)
      try {
        block { p -> ui = ui.copy(progress = p) }
      } catch (e: Exception) {
        msg("$name failed: ${e.message}")
      } finally {
        ui = ui.copy(busy = null, progress = null)
      }
    }
  }

  fun runLama() {
    val bmp = ui.bitmap ?: return
    val alpha = maskAlpha(mask)
    val eff = if (ui.maskInverted) ByteArray(alpha.size) { (255 - (alpha[it].toInt() and 0xFF)).toByte() } else alpha
    if (eff.none { (it.toInt() and 0xFF) > 24 }) { msg("Paint a mask first (or invert it)"); return }
    val model = requireModel("lama") ?: return
    runOp("Magic Eraser") { progress ->
      pushUndo()
      val out = Lama.inpaint(bmp, alpha, ui.maskInverted, model, progress)
      invalidateCaches()
      withContext(Dispatchers.Main) {
        mask.eraseColor(android.graphics.Color.TRANSPARENT)
        ui = ui.copy(bitmap = out, maskVersion = ui.maskVersion + 1)
        adjustBase = out
      }
    }
  }

  fun samTap(x: Float, y: Float) {
    val bmp = ui.bitmap ?: return
    if (!downloader.isReady("sam_encoder") || !downloader.isReady("sam_decoder")) {
      msg("MobileSAM models missing. Open Models."); ui = ui.copy(showModels = true); return
    }
    runOp("Smart Select") { progress ->
      progress(0.2f)
      val s = sam ?: MobileSam(downloader.file("sam_encoder"), downloader.file("sam_decoder")).also { sam = it }
      val alpha = s.tap(bmp, x, y)
      pushUndo()
      val newMask = alphaToMask(alpha, bmp.width, bmp.height)
      withContext(Dispatchers.Main) {
        mask = newMask
        ui = ui.copy(maskVersion = ui.maskVersion + 1, tool = Tool.Brush)
      }
      progress(1f)
    }
  }

  fun runCutout(action: Int) {
    val bmp = ui.bitmap ?: return
    val model = requireModel(ui.cutoutModel) ?: return
    runOp("AI Cutout") { progress ->
      progress(0.2f)
      val fg = Segmenter.foregroundAlpha(bmp, ui.cutoutModel, model)
      progress(0.6f)
      when (action) {
        0 -> {
          pushUndo()
          val out = applyAlpha(bmp, fg)
          withContext(Dispatchers.Main) { ui = ui.copy(bitmap = out) }
        }
        else -> {
          val eraseAlpha = if (action == 1) ByteArray(fg.size) { (255 - (fg[it].toInt() and 0xFF)).toByte() } else fg
          val lamaModel = requireModel("lama") ?: return@runOp
          pushUndo()
          val out = Lama.inpaint(bmp, eraseAlpha, false, lamaModel, progress)
          invalidateCaches()
          withContext(Dispatchers.Main) {
            mask = alphaToMask(eraseAlpha, bmp.width, bmp.height)
            ui = ui.copy(bitmap = out, maskVersion = ui.maskVersion + 1)
            adjustBase = out
          }
        }
      }
      progress(1f)
    }
  }

  fun runFaceSwap() {
    val target = ui.bitmap ?: return
    val src = sourceFace ?: run { msg("Pick a source face first"); return }
    if (!downloader.isReady("face_pack")) { msg("Download the SCRFD+ArcFace pack first"); ui = ui.copy(showModels = true); return }
    val inswapper = requireModel("inswapper") ?: return
    val gfpgan = if (ui.faceEnhance && downloader.isReady("gfpgan")) downloader.file("gfpgan") else null
    runOp("Face Swap") { progress ->
      val out = FaceSwap.swap(
        src, target,
        downloader.produced("face_pack", "scrfd_500m.onnx"),
        downloader.produced("face_pack", "arcface.onnx"),
        inswapper, gfpgan, ui.faceEnhance, progress
      )
      pushUndo()
      invalidateCaches()
      withContext(Dispatchers.Main) { ui = ui.copy(bitmap = out); adjustBase = out }
    }
  }

  fun runUpscale() {
    val bmp = ui.bitmap ?: return
    val model = requireModel("realesrgan") ?: return
    runOp("4K Upscale") { progress ->
      val out = Upscaler.upscale(bmp, model, 4096, progress)
      pushUndo()
      invalidateCaches()
      withContext(Dispatchers.Main) {
        mask = newMask(out.width, out.height)
        ui = ui.copy(bitmap = out, maskVersion = ui.maskVersion + 1)
        adjustBase = out
      }
    }
  }

  fun computeDepth() {
    val bmp = ui.bitmap ?: return
    val model = requireModel("midas") ?: return
    runOp("Depth Analysis") { progress ->
      progress(0.2f)
      val d = DepthBokeh.depth(bmp, model)
      depth = d; portraitBase = bmp
      pushUndo()
      progress(0.9f)
      val out = DepthBokeh.bokeh(bmp, d, ui.bokehAmount, ui.bokehFocus, ui.bokehRange)
      withContext(Dispatchers.Main) { ui = ui.copy(bitmap = out, depthReady = true) }
      progress(1f)
    }
  }

  private var bokehJob: Job? = null
  private fun applyBokehDebounced() {
    val d = depth ?: return
    val base = portraitBase ?: return
    bokehJob?.cancel()
    bokehJob = viewModelScope.launch(Dispatchers.Default) {
      delay(120)
      val out = DepthBokeh.bokeh(base, d, ui.bokehAmount, ui.bokehFocus, ui.bokehRange)
      withContext(Dispatchers.Main) { ui = ui.copy(bitmap = out) }
    }
  }

  fun applyStyle() {
    val bmp = ui.bitmap ?: return
    val alpha = maskAlpha(mask)
    if (alpha.none { (it.toInt() and 0xFF) > 24 }) { msg("Select the outfit region first (brush or tap)"); return }
    runOp("Style Transfer") { progress ->
      progress(0.2f)
      var out = Style.recolor(bmp, alpha, ui.styleLab, ui.styleHue, ui.styleSat, ui.styleLight)
      progress(0.7f)
      val tex = texture
      if (tex != null && ui.textureStrength > 0.01f) {
        val blended = Style.textureBlend(out, alpha, tex, ui.textureStrength)
        out.recycle(); out = blended
      }
      pushUndo()
      withContext(Dispatchers.Main) { ui = ui.copy(bitmap = out); adjustBase = out }
      progress(1f)
    }
  }

  fun runSdTurbo() {
    val bmp = ui.bitmap ?: return
    if (!downloader.sdReady()) {
      msg("Import the SD-Turbo ONNX export folder in Models first")
      ui = ui.copy(showModels = true); return
    }
    val alpha = maskAlpha(mask)
    if (alpha.none { (it.toInt() and 0xFF) > 24 }) { msg("Select the outfit region first"); return }
    val files = ModelCatalog.sdIds.associateWith { downloader.file(it) }
    runOp("SD-Turbo Restyle") { progress ->
      val out = SdTurbo.inpaint(bmp, alpha, ui.sdPrompt, ui.sdStrength, files, progress)
      pushUndo()
      withContext(Dispatchers.Main) { ui = ui.copy(bitmap = out); adjustBase = out }
    }
  }

  fun setAdjust(p: AdjustParams) {
    ui = ui.copy(adjust = p)
    val base = adjustBase ?: return
    adjustJob?.cancel()
    adjustJob = viewModelScope.launch(Dispatchers.Default) {
      delay(100)
      val out = Adjust.apply(base, p)
      withContext(Dispatchers.Main) { ui = ui.copy(bitmap = out) }
    }
  }

  fun commitAdjust() {
    adjustBase = ui.bitmap
    ui = ui.copy(panel = Panel.None, adjust = AdjustParams())
  }

  fun applyCrop() {
    val bmp = ui.bitmap ?: return
    val r = ui.cropRect
    pushUndo()
    val out = Adjust.crop(bmp, r.left, r.top, r.right, r.bottom)
    val m = Adjust.crop(mask, r.left, r.top, r.right, r.bottom).asMutableMask()
    mask = m
    invalidateCaches()
    adjustBase = out
    ui = ui.copy(bitmap = out, maskVersion = ui.maskVersion + 1, cropMode = false)
  }

  fun rotate() {
    val bmp = ui.bitmap ?: return
    pushUndo()
    val out = Adjust.rotate90(bmp)
    mask = Adjust.rotate90(mask).asMutableMask()
    invalidateCaches()
    adjustBase = out
    ui = ui.copy(bitmap = out, maskVersion = ui.maskVersion + 1)
  }

  fun flip(horizontal: Boolean) {
    val bmp = ui.bitmap ?: return
    pushUndo()
    val out = Adjust.flip(bmp, horizontal)
    mask = Adjust.flip(mask, horizontal).asMutableMask()
    invalidateCaches()
    adjustBase = out
    ui = ui.copy(bitmap = out, maskVersion = ui.maskVersion + 1)
  }

  fun export() {
    val bmp = ui.bitmap ?: return
    runOp("Exporting") { progress ->
      progress(0.3f)
      val uri = Exporter.save(getApplication(), bmp, ui.exportFormat, ui.exportQuality)
      progress(1f)
      withContext(Dispatchers.Main) {
        msg(if (uri != null) "Saved to Gallery / Pictures / NativeAIEditor" else "Export failed")
      }
    }
  }

  fun downloadCore() {
    ui = ui.copy(showFirstRun = false)
    viewModelScope.launch { downloader.downloadCore() }
  }

  fun skipFirstRun() {
    downloader.markFirstRunDone()
    ui = ui.copy(showFirstRun = false)
  }


  fun autoRemoveCrowd(keepMain: Boolean = true) {
    val bmp = ui.bitmap ?: return
    val lamaF = downloader.file("lama").takeIf { it.exists() } ?: run { msg("Download LaMa Magic Eraser first"); return }
    val segF = downloader.file("rmbg").takeIf { it.exists() } ?: downloader.file("u2netp").takeIf { it.exists() } ?: run { msg("Download U2NetP or RMBG first"); return }
    runOp("AI Removing Crowd...") { p ->
      p(0.3f)
      val out = CreativeStudioAi.autoRemoveCrowd(bmp, segF, lamaF, keepMain)
      p(1f)
      withContext(Dispatchers.Main) { pushUndo(); ui = ui.copy(bitmap = out); invalidateCaches(); msg("Crowd removed!") }
    }
  }

  fun applyAnime(shinkai: Boolean = false) {
    val bmp = ui.bitmap ?: return
    val id = if (shinkai) "anime_shinkai" else "anime_hayao"
    val f = downloader.file(id).takeIf { it.exists() }
    runOp(if (shinkai) "Rendering 3D Shinkai Anime..." else "Rendering Hayao Anime...") { p ->
      p(0.35f)
      val out = if (f != null) CreativeStudioAi.runStyleOnnx(bmp, f) else CreativeStudioAi.nativeCartoon(bmp, 0)
      p(1f)
      withContext(Dispatchers.Main) { pushUndo(); ui = ui.copy(bitmap = out); invalidateCaches() }
    }
  }

  fun applyCartoonMode(mode: Int) {
    val bmp = ui.bitmap ?: return
    runOp("Applying Art Style...") { p ->
      p(0.4f)
      val out = CreativeStudioAi.nativeCartoon(bmp, mode)
      p(1f)
      withContext(Dispatchers.Main) { pushUndo(); ui = ui.copy(bitmap = out); invalidateCaches() }
    }
  }

  fun colorizeBwPhoto() {
    val bmp = ui.bitmap ?: return
    val f = downloader.file("colorizer").takeIf { it.exists() }
    runOp("Colorizing B&W Photo...") { p ->
      p(0.35f)
      val out = CreativeStudioAi.colorizeBw(bmp, f)
      p(1f)
      withContext(Dispatchers.Main) { pushUndo(); ui = ui.copy(bitmap = out); invalidateCaches() }
    }
  }

  fun restoreOldPhoto() {
    val bmp = ui.bitmap ?: return
    val gfp = downloader.file("gfpgan").takeIf { it.exists() }
    val det = downloader.file("face_det").takeIf { it.exists() }
    runOp("Restoring Old Photo & Face...") { p ->
      p(0.3f)
      val out = CreativeStudioAi.restoreOldPhoto(bmp, gfp, det)
      p(1f)
      withContext(Dispatchers.Main) { pushUndo(); ui = ui.copy(bitmap = out); invalidateCaches(); msg("Old photo restored!") }
    }
  }

  fun applyCinemaPreset(preset: String) {
    val bmp = ui.bitmap ?: return
    runOp("Applying $preset...") { p ->
      p(0.5f)
      val out = CreativeStudioAi.applyPreset(bmp, preset)
      p(1f)
      withContext(Dispatchers.Main) { pushUndo(); ui = ui.copy(bitmap = out); invalidateCaches() }
    }
  }

  override fun onCleared() {
    sam?.close()
    super.onCleared()
  }
}
