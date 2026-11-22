package com.nativeai.editor.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nativeai.editor.*
import com.nativeai.editor.ai.ModelCatalog
import com.nativeai.editor.ai.ModelDownloader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: AppViewModel) {
  val ui = vm.ui
  val context = LocalContext.current
  val snackbar = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val states by vm.downloader.states.collectAsState()

  val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    if (uri != null) vm.loadPhoto(uri)
  }
  val faceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    if (uri != null) vm.setSourceFace(uri)
  }
  val textureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    if (uri != null) vm.setTexture(uri)
  }
  val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (granted) vm.export()
    else scope.launch { snackbar.showSnackbar("Storage permission required on Android 9 and below") }
  }

  LaunchedEffect(ui.message) {
    ui.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
  }

  if (ui.showModels) { ModelManagerScreen(vm); return }

  if (ui.showFirstRun) {
    val coreMB = ModelCatalog.core.sumOf { it.approxMB }
    AlertDialog(
      onDismissRequest = { vm.skipFirstRun() },
      title = { Text("Download AI models?") },
      text = {
        Text(
          "First launch downloads the core on-device models (~$coreMB MB: LaMa eraser, MobileSAM tap-select, " +
            "U2NetP cutout, MiDaS depth). After that everything runs 100% offline. Use Wi-Fi if possible."
        )
      },
      confirmButton = { TextButton(onClick = { vm.downloadCore() }) { Text("Download now") } },
      dismissButton = { TextButton(onClick = { vm.skipFirstRun() }) { Text("Later") } }
    )
  }

  val coreDownloading = ModelCatalog.core.any { states[it.id]?.status == ModelDownloader.Status.Downloading }
  val coreProgress = ModelCatalog.core.map { states[it.id]?.progress ?: 0f }.average().toFloat()

  Scaffold(
    snackbarHost = { SnackbarHost(snackbar) },
    topBar = {
      TopAppBar(
        title = { Text("Native AI Editor", fontSize = 17.sp) },
        actions = {
          TextButton(onClick = { vm.undo() }, enabled = ui.canUndo) { Text("Undo") }
          TextButton(onClick = { vm.redo() }, enabled = ui.canRedo) { Text("Redo") }
          TextButton(onClick = { vm.showModels(true) }) { Text("Models") }
          TextButton(onClick = { openLauncher.launch("image/*") }) { Text("Open") }
        }
      )
    }
  ) { pad ->
    Column(Modifier.padding(pad).fillMaxSize()) {
      if (coreDownloading) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
          Text("Downloading core models… ${(coreProgress * 100).toInt()}%", fontSize = 12.sp)
          LinearProgressIndicator(progress = { coreProgress }, modifier = Modifier.fillMaxWidth())
        }
      }
      Box(Modifier.weight(1f).fillMaxWidth()) {
        val bmp = ui.bitmap
        if (bmp == null) {
          Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Open a photo to start", fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { openLauncher.launch("image/*") }) { Text("Open Photo") }
          }
        } else {
          EditorCanvas(
            bitmap = bmp, original = ui.original, mask = vm.mask, maskVersion = ui.maskVersion,
            maskOpacity = ui.maskOpacity, maskInverted = ui.maskInverted, tool = ui.tool,
            compare = ui.compare, split = ui.split, onSplit = vm::setSplit,
            cropMode = ui.cropMode, cropRect = ui.cropRect, onCrop = vm::setCropRect,
            onStrokeStart = vm::strokeStart, onStrokeMove = vm::strokeMove, onStrokeEnd = vm::strokeEnd,
            onTap = { p -> vm.samTap(p.x, p.y) }
          )
          val busyText = ui.busy
          if (busyText != null) {
            Surface(color = Color(0xCC101014), modifier = Modifier.fillMaxSize()) {}
            Column(
              Modifier.align(Alignment.Center),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              CircularProgressIndicator()
              Spacer(Modifier.height(10.dp))
              Text(busyText, fontSize = 14.sp)
              ui.progress?.let {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { it }, modifier = Modifier.width(220.dp))
              }
            }
          }
        }
      }

      if (ui.bitmap != null) {
        Row(
          Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Chip(ui.tool == Tool.Brush, "Brush") { vm.setTool(Tool.Brush) }
          Chip(ui.tool == Tool.Erase, "Eraser") { vm.setTool(Tool.Erase) }
          Chip(ui.tool == Tool.Pan, "Pan") { vm.setTool(Tool.Pan) }
          Chip(ui.tool == Tool.Tap, "Tap-Select") { vm.setTool(Tool.Tap) }
          Chip(ui.compare, "Before/After") { vm.toggleCompare() }
          Chip(ui.maskInverted, if (ui.maskInverted) "Mask: BG" else "Mask: FG") { vm.toggleInvert() }
          Chip(false, "Clear Mask") { vm.clearMask() }
        }
        if (ui.tool == Tool.Brush || ui.tool == Tool.Erase) {
          SliderRow("Brush size", ui.brushSize, 4f..300f, { vm.setBrush(it, ui.feather, ui.maskOpacity) }, { "%.0f".format(it) })
          SliderRow("Feather", ui.feather, 0f..1f, { vm.setBrush(ui.brushSize, it, ui.maskOpacity) })
          SliderRow("Mask alpha", ui.maskOpacity, 0f..1f, { vm.setBrush(ui.brushSize, ui.feather, it) })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
          Panel.entries.filter { it != Panel.None }.forEach { p ->
            Chip(ui.panel == p, p.name) { vm.set(p) }
          }
        }
        if (ui.panel != Panel.None) {
          Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()).fillMaxWidth()) {
            when (ui.panel) {
              Panel.Erase -> {
                PanelTitle("Magic Object Eraser — paint over the object, then run LaMa")
                Row(Modifier.padding(horizontal = 12.dp)) {
                  Button(onClick = { vm.runLama() }) { Text("Erase & Inpaint (LaMa)") }
                }
              }
              Panel.Select -> {
                PanelTitle("Smart Tap Auto-Select — tap any object with the Tap-Select tool")
                Row(Modifier.padding(horizontal = 12.dp)) {
                  Chip(false, "Enable Tap tool") { vm.setTool(Tool.Tap) }
                  Chip(false, "Clear selection") { vm.clearMask() }
                }
              }
              Panel.Cutout -> {
                PanelTitle("Background / Foreground Eraser")
                Row(Modifier.padding(horizontal = 12.dp)) {
                  Chip(ui.cutoutModel == "u2netp", "U2NetP (fast)") { vm.setCutoutModel("u2netp") }
                  Chip(ui.cutoutModel == "rmbg", "RMBG-1.4 (quality)") { vm.setCutoutModel("rmbg") }
                }
                Row(Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState())) {
                  Chip(false, "Keep subject (cutout)") { vm.runCutout(0) }
                  Chip(false, "Erase background + fill") { vm.runCutout(1) }
                  Chip(false, "Erase subject + fill") { vm.runCutout(2) }
                }
              }
              Panel.Face -> {
                PanelTitle("AI Face Swap — source face + current photo as target")
                Row(
                  Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Chip(false, if (ui.hasSourceFace) "Source loaded (change)" else "Pick Source Face") { faceLauncher.launch("image/*") }
                  Chip(false, "Pick Target Photo") { openLauncher.launch("image/*") }
                }
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                  Text("GFPGAN enhance", fontSize = 12.sp)
                  Switch(
                    checked = ui.faceEnhance,
                    onCheckedChange = { vm.setFaceEnhance(it) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                  )
                  Button(onClick = { vm.runFaceSwap() }, enabled = ui.hasSourceFace) { Text("Swap Faces") }
                }
              }
              Panel.Upscale -> {
                PanelTitle("4K Ultra HD Upscaler — memory-safe tiled Real-ESRGAN x4")
                Row(Modifier.padding(horizontal = 12.dp)) {
                  Button(onClick = { vm.runUpscale() }) { Text("Upscale x4 (max 4096px)") }
                }
              }
              Panel.Portrait -> {
                PanelTitle("AI DSLR Portrait Blur — MiDaS depth + adjustable bokeh")
                Row(Modifier.padding(horizontal = 12.dp)) {
                  Button(onClick = { vm.computeDepth() }) { Text(if (ui.depthReady) "Re-analyze Depth" else "Analyze Depth") }
                }
                if (ui.depthReady) {
                  SliderRow("Bokeh", ui.bokehAmount, 0f..1f, { vm.setBokeh(it, ui.bokehFocus, ui.bokehRange) })
                  SliderRow("Focus depth", ui.bokehFocus, 0f..1f, { vm.setBokeh(ui.bokehAmount, it, ui.bokehRange) })
                  SliderRow("Depth range", ui.bokehRange, 0.05f..0.5f, { vm.setBokeh(ui.bokehAmount, ui.bokehFocus, it) })
                }
              }
              Panel.Style -> {
                PanelTitle("Outfit & Style — select region, then native LAB/HSV transfer")
                Row(Modifier.padding(horizontal = 12.dp)) {
                  Chip(ui.styleLab, "LAB") { vm.setStyle(true, ui.styleHue, ui.styleSat, ui.styleLight, ui.textureStrength) }
                  Chip(!ui.styleLab, "HSV") { vm.setStyle(false, ui.styleHue, ui.styleSat, ui.styleLight, ui.textureStrength) }
                  Chip(false, if (ui.hasTexture) "Texture loaded" else "Pick texture") { textureLauncher.launch("image/*") }
                }
                SliderRow("Hue shift", ui.styleHue, -180f..180f, { vm.setStyle(ui.styleLab, it, ui.styleSat, ui.styleLight, ui.textureStrength) }, { "%.0f".format(it) })
                SliderRow("Saturation", ui.styleSat, 0f..2f, { vm.setStyle(ui.styleLab, ui.styleHue, it, ui.styleLight, ui.textureStrength) })
                SliderRow("Lightness", ui.styleLight, -60f..60f, { vm.setStyle(ui.styleLab, ui.styleHue, ui.styleSat, it, ui.textureStrength) }, { "%.0f".format(it) })
                SliderRow("Texture mix", ui.textureStrength, 0f..1f, { vm.setStyle(ui.styleLab, ui.styleHue, ui.styleSat, ui.styleLight, it) })
                Row(Modifier.padding(horizontal = 12.dp)) {
                  Button(onClick = { vm.applyStyle() }) { Text("Apply Restyle") }
                }
                PanelTitle("SD-Turbo ONNX (import models first) — local generative restyle")
                OutlinedTextField(
                  value = ui.sdPrompt,
                  onValueChange = { vm.setSd(it, ui.sdStrength) },
                  label = { Text("Prompt") },
                  singleLine = true,
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                )
                SliderRow("Strength", ui.sdStrength, 0.1f..1f, { vm.setSd(ui.sdPrompt, it) })
                Row(Modifier.padding(horizontal = 12.dp)) {
                  Button(onClick = { vm.runSdTurbo() }) { Text("Run SD-Turbo (1 step)") }
                }
              }
              Panel.Adjust -> {
                PanelTitle("Pro Manual Adjustments")
                val a = ui.adjust
                SliderRow("Exposure", a.exposure, -2f..2f, { vm.setAdjust(a.copy(exposure = it)) })
                SliderRow("Contrast", a.contrast, -1f..1f, { vm.setAdjust(a.copy(contrast = it)) })
                SliderRow("Highlights", a.highlights, -1f..1f, { vm.setAdjust(a.copy(highlights = it)) })
                SliderRow("Shadows", a.shadows, -1f..1f, { vm.setAdjust(a.copy(shadows = it)) })
                SliderRow("Saturation", a.saturation, -1f..1f, { vm.setAdjust(a.copy(saturation = it)) })
                SliderRow("Vibrance", a.vibrance, -1f..1f, { vm.setAdjust(a.copy(vibrance = it)) })
                SliderRow("Temperature", a.temperature, -1f..1f, { vm.setAdjust(a.copy(temperature = it)) })
                SliderRow("Sharpness", a.sharpness, 0f..1f, { vm.setAdjust(a.copy(sharpness = it)) })
                SliderRow("Vignette", a.vignette, 0f..1f, { vm.setAdjust(a.copy(vignette = it)) })
                Row(Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState())) {
                  Chip(ui.cropMode, if (ui.cropMode) "Apply Crop" else "Crop") {
                    if (ui.cropMode) vm.applyCrop() else vm.setCropMode(true)
                  }
                  Chip(false, "Rotate 90") { vm.rotate() }
                  Chip(false, "Flip H") { vm.flip(true) }
                  Chip(false, "Flip V") { vm.flip(false) }
                  Chip(false, "Done") { vm.commitAdjust() }
                }
              }
              Panel.Export -> {
                PanelTitle("4K Export to Gallery")
                Row(Modifier.padding(horizontal = 12.dp)) {
                  Chip(ui.exportFormat == 0, "PNG") { vm.setExport(0, ui.exportQuality) }
                  Chip(ui.exportFormat == 1, "JPG") { vm.setExport(1, ui.exportQuality) }
                  Chip(ui.exportFormat == 2, "WEBP") { vm.setExport(2, ui.exportQuality) }
                }
                if (ui.exportFormat != 0) {
                  SliderRow("Quality", ui.exportQuality.toFloat(), 50f..100f, { vm.setExport(ui.exportFormat, it.toInt()) }, { "%.0f".format(it) })
                }
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                  Button(onClick = {
                    if (Build.VERSION.SDK_INT < 29 &&
                      ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                    ) permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    else vm.export()
                  }) { Text("Save to Gallery") }
                  Spacer(Modifier.width(12.dp))
                  Text("${ui.bitmap!!.width}x${ui.bitmap!!.height}px", fontSize = 12.sp)
                }
              }
              Panel.None -> {}
            }
            Spacer(Modifier.height(8.dp))
          }
        }
      }
    }
  }
}
