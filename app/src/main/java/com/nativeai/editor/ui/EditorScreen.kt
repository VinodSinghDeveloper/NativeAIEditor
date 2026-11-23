package com.nativeai.editor.ui
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.nativeai.editor.*
import com.nativeai.editor.ai.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: AppViewModel) {
  val ui = vm.ui
  val snackbar = remember { SnackbarHostState() }
  val states by vm.downloader.states.collectAsState()
  var activeTab by remember { mutableIntStateOf(0) }
  var showOriginalHold by remember { mutableStateOf(false) }

  val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> uri?.let { vm.loadPhoto(it) } }
  val faceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? -> uri?.let { vm.setSourceFace(it) } }

  LaunchedEffect(ui.message) { ui.message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
  if (ui.showModels) { ModelManagerScreen(vm); return }

  if (false && ui.showFirstRun) {
    val coreMB = ModelCatalog.core.sumOf { it.approxMB }
    AlertDialog(
      onDismissRequest = { vm.skipFirstRun() },
      title = { Text("Download Offline AI Models?") },
      text = { Text("First launch downloads core AI engines (~$coreMB MB). After that everything runs 100% offline.") },
      confirmButton = { TextButton(onClick = { vm.downloadCore() }) { Text("Download now") } },
      dismissButton = { TextButton(onClick = { vm.skipFirstRun() }) { Text("Later") } }
    )
  }

  val coreDownloading = ModelCatalog.core.any { states.get(it.id)?.status == ModelDownloader.Status.Downloading }
  val coreProgress = ModelCatalog.core.map { states.get(it.id)?.progress ?: 0f }.average().toFloat()
  val studioBg = Color(0xFF0A0C10); val cardBg = Color(0xFF141821)
  val accentGrad = Brush.horizontalGradient(listOf(Color(0xFF6366F1), Color(0xFFA855F7), Color(0xFFEC4899)))

  Scaffold(
    containerColor = studioBg,
    snackbarHost = { SnackbarHost(snackbar) },
    topBar = {
      Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("RealMind ChalChitra AI", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
          Text("Offline AI Photo Studio", color = Color(0xFF94A3B8), fontSize = 11.sp)
        }
        IconButton(onClick = { vm.undo() }, enabled = ui.canUndo) { Icon(Icons.Rounded.Refresh, "Undo", tint = if (ui.canUndo) Color.White else Color(0xFF475569)) }
        FilledTonalButton(onClick = { vm.showModels(true) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
          colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF1E293B), contentColor = Color(0xFF38BDF8))) {
          Icon(Icons.Rounded.Build, null, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text("Models", fontSize = 12.sp)
        }
        Spacer(Modifier.width(6.dp))
        Button(onClick = { vm.export() }, enabled = ui.bitmap != null && ui.busy == null, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))) { Text("Save", fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
      }
    }
  ) { pad ->
    Column(Modifier.fillMaxSize().padding(pad)) {
      if (coreDownloading) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
          Text("Downloading AI engines... ${(coreProgress * 100).toInt()}%", color = Color(0xFF38BDF8), fontSize = 11.sp)
          LinearProgressIndicator(progress = { coreProgress }, modifier = Modifier.fillMaxWidth())
        }
      }

      Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF0F131A))) {
        val bmp = if (showOriginalHold && ui.original != null) ui.original else ui.bitmap
        if (bmp == null) {
          Column(Modifier.fillMaxSize().clickable { openLauncher.launch("image/*") }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(accentGrad), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
            Spacer(Modifier.height(14.dp)); Text("Tap to Open Photo in AI Studio", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("100% Offline • 4K Export • Zero Watermark", color = Color(0xFF94A3B8), fontSize = 12.sp)
          }
        } else {
          EditorCanvas(bitmap = bmp, original = ui.original, mask = vm.mask, maskVersion = ui.maskVersion, maskOpacity = if (showOriginalHold) 0f else ui.maskOpacity,
            maskInverted = ui.maskInverted, tool = if (activeTab == 2) Tool.Brush else ui.tool, compare = ui.compare, split = ui.split, onSplit = vm::setSplit,
            cropMode = ui.cropMode, cropRect = ui.cropRect, onCrop = vm::setCropRect, onStrokeStart = vm::strokeStart, onStrokeMove = vm::strokeMove,
            onStrokeEnd = vm::strokeEnd, onTap = { p -> vm.samTap(p.x, p.y) })
          Surface(shape = RoundedCornerShape(20.dp), color = Color(0xCC0F172A), modifier = Modifier.align(Alignment.TopStart).padding(10.dp).clickable { openLauncher.launch("image/*") }) {
            Text("Change Photo", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
          }
          if (ui.original != null) {
            Surface(shape = RoundedCornerShape(20.dp), color = if (showOriginalHold) Color(0xFF6366F1) else Color(0xCC0F172A),
              modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).pointerInput(Unit) { detectTapGestures(onPress = { showOriginalHold = true; tryAwaitRelease(); showOriginalHold = false }) }) {
              Text(if (showOriginalHold) "Original" else "Hold: Before", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            }
          }
        }
        ui.busy?.let { busyText ->
          Box(Modifier.fillMaxSize().background(Color(0xCC05070A)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
              CircularProgressIndicator(color = Color(0xFFA855F7)); Spacer(Modifier.height(12.dp))
              Text(busyText, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
              ui.progress?.let { p -> Spacer(Modifier.height(6.dp)); LinearProgressIndicator(progress = { p }, color = Color(0xFF38BDF8), modifier = Modifier.width(180.dp)) }
            }
          }
        }
      }

      Surface(color = cardBg, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().padding(top = 10.dp, bottom = 8.dp)) {
          val hasImg = ui.bitmap != null && ui.busy == null
          when (activeTab) {
            0 -> Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              StudioActionCard("Remove Crowd", Icons.Rounded.Person, Color(0xFFEC4899), hasImg) { vm.autoRemoveCrowd(true) }
              StudioActionCard("Old Restore", Icons.Rounded.Star, Color(0xFFF59E0B), hasImg) { vm.restoreOldPhoto() }
              StudioActionCard("B&W Colorize", Icons.Rounded.Create, Color(0xFF10B981), hasImg) { vm.colorizeBwPhoto() }
              StudioActionCard("4K Enhance", Icons.Rounded.ThumbUp, Color(0xFF6366F1), hasImg) { vm.setFaceEnhance(true); vm.runUpscale() }
              StudioActionCard("Auto Enhance", Icons.Rounded.CheckCircle, Color(0xFF38BDF8), hasImg) { vm.setAdjust(ui.adjust.copy(contrast = 0.18f, vibrance = 0.25f)); vm.commitAdjust() }
            }
            1 -> Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              StudioActionCard("Anime Hayao", Icons.Rounded.PlayArrow, Color(0xFFEC4899), hasImg) { vm.applyAnime(false) }
              StudioActionCard("3D Shinkai", Icons.Rounded.Favorite, Color(0xFF8B5CF6), hasImg) { vm.applyAnime(true) }
              StudioActionCard("Comic Art", Icons.Rounded.Edit, Color(0xFFF59E0B), hasImg) { vm.applyCartoonMode(0) }
              StudioActionCard("Pencil Sketch", Icons.Rounded.Create, Color(0xFF94A3B8), hasImg) { vm.applyCartoonMode(1) }
              StudioActionCard("Leica B&W", Icons.Rounded.Check, Color(0xFFE2E8F0), hasImg) { vm.applyCartoonMode(2) }
            }
            2 -> Column(Modifier.padding(horizontal = 12.dp)) {
              Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioActionCard("Erase Marked", Icons.Rounded.Delete, Color(0xFFEF4444), hasImg) { vm.runLama() }
                StudioActionCard("Clear Mask", Icons.Rounded.Clear, Color(0xFF64748B), hasImg) { vm.clearMask() }
                StudioActionCard("Invert Mask", Icons.Rounded.Refresh, Color(0xFF38BDF8), hasImg) { vm.toggleInvert() }
              }
              Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text("Brush", color = Color(0xFF94A3B8), fontSize = 11.sp)
                Slider(value = ui.brushSize, onValueChange = { vm.setBrush(it, ui.feather, ui.maskOpacity) }, valueRange = 8f..90f, modifier = Modifier.weight(1f).height(28.dp))
                Text("${ui.brushSize.toInt()}", color = Color.White, fontSize = 11.sp)
              }
            }
            3 -> Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              StudioActionCard("Pick Face", Icons.Rounded.Face, Color(0xFF38BDF8), hasImg) { faceLauncher.launch("image/*") }
              StudioActionCard("Swap Face", Icons.Rounded.Refresh, Color(0xFFA855F7), hasImg && ui.hasSourceFace) { vm.runFaceSwap() }
              StudioActionCard("HD Face Fix", Icons.Rounded.Star, Color(0xFF10B981), hasImg) { vm.setFaceEnhance(true); vm.runFaceSwap() }
              StudioActionCard("4x Upscale", Icons.Rounded.Add, Color(0xFFF59E0B), hasImg) { vm.runUpscale() }
            }
            4 -> Column(Modifier.padding(horizontal = 12.dp)) {
              Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioActionCard("Remove BG", Icons.Rounded.Clear, Color(0xFFEC4899), hasImg) { vm.runCutout(0) }
                StudioActionCard("DSLR Bokeh", Icons.Rounded.Search, Color(0xFF6366F1), hasImg) { vm.setBokeh(0.6f, ui.bokehFocus, ui.bokehRange) }
                StudioActionCard("Auto Subject", Icons.Rounded.Person, Color(0xFF10B981), hasImg) { vm.runCutout(2) }
                StudioActionCard("Rotate 90", Icons.Rounded.Refresh, Color(0xFF38BDF8), hasImg) { vm.rotate() }
              }
              Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text("DSLR Blur", color = Color(0xFF94A3B8), fontSize = 11.sp)
                Slider(value = ui.bokehAmount, onValueChange = { vm.setBokeh(it, ui.bokehFocus, ui.bokehRange) }, valueRange = 0f..1f, modifier = Modifier.weight(1f).height(28.dp))
                Text("${(ui.bokehAmount * 100).toInt()}%", color = Color.White, fontSize = 11.sp)
              }
            }
            5 -> Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              listOf("Teal & Orange", "Kodak Gold 200", "Moody Dark", "Cyberpunk Neon", "Golden Hour", "Vintage 90s", "HDR Vivid").forEach { preset ->
                StudioActionCard(preset, Icons.Rounded.Star, Color(0xFF8B5CF6), hasImg) { vm.applyCinemaPreset(preset) }
              }
            }
          }
          Spacer(Modifier.height(8.dp))
          Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceAround) {
            listOf("AI Magic" to Icons.Rounded.Star, "Anime/Art" to Icons.Rounded.PlayArrow, "Erase" to Icons.Rounded.Delete,
              "Face" to Icons.Rounded.Face, "Cutout" to Icons.Rounded.Person, "Presets" to Icons.Rounded.Settings).forEachIndexed { index, pair ->
              val sel = activeTab == index
              Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { activeTab = index }.padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(pair.second, pair.first, tint = if (sel) Color(0xFFA855F7) else Color(0xFF64748B), modifier = Modifier.size(21.dp))
                Text(pair.first, color = if (sel) Color.White else Color(0xFF64748B), fontSize = 10.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun StudioActionCard(title: String, icon: ImageVector, accent: Color, enabled: Boolean, onClick: () -> Unit) {
  Column(
    Modifier.width(84.dp).clip(RoundedCornerShape(14.dp)).background(if (enabled) Color(0xFF1E2433) else Color(0xFF11151E))
      .border(1.dp, if (enabled) accent.copy(alpha = 0.45f) else Color(0xFF1E293B), RoundedCornerShape(14.dp)).clickable(enabled = enabled) { onClick() }.padding(vertical = 10.dp, horizontal = 6.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(accent.copy(alpha = if (enabled) 0.2f else 0.08f)), contentAlignment = Alignment.Center) {
      Icon(icon, title, tint = if (enabled) accent else Color(0xFF475569), modifier = Modifier.size(19.dp))
    }
    Spacer(Modifier.height(6.dp)); Text(title, color = if (enabled) Color.White else Color(0xFF475569), fontSize = 10.sp, maxLines = 1, fontWeight = FontWeight.Medium)
  }
}
