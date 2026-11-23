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

  if (ui.showFirstRun) {
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
