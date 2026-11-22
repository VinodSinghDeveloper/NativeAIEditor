package com.nativeai.editor.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nativeai.editor.AppViewModel
import com.nativeai.editor.ai.ModelCatalog
import com.nativeai.editor.ai.ModelDownloader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(vm: AppViewModel) {
  val states by vm.downloader.states.collectAsState()
  val scope = rememberCoroutineScope()
  var importId by remember { mutableStateOf<String?>(null) }
  var urlId by remember { mutableStateOf<String?>(null) }
  var urlText by remember { mutableStateOf("") }
  var folderMsg by remember { mutableStateOf<String?>(null) }

  val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
    val id = importId
    if (uri != null && id != null) scope.launch { vm.downloader.import(id, uri) }
  }
  val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
    if (uri != null) scope.launch {
      val n = vm.downloader.importSdFolder(uri)
      folderMsg = if (n > 0) "Imported $n SD-Turbo files." else "No matching files found in that folder."
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("On-Device Models") },
        navigationIcon = { TextButton(onClick = { vm.showModels(false) }) { Text("Back") } }
      )
    }
  ) { pad ->
    LazyColumn(Modifier.padding(pad).fillMaxSize()) {
      item {
        Column(Modifier.padding(12.dp)) {
          Text("All inference runs 100% offline after download. Files live in app-private storage.", fontSize = 12.sp)
          Spacer(Modifier.height(8.dp))
          Button(onClick = { folderLauncher.launch(null) }) { Text("Import SD-Turbo ONNX export folder") }
          Text(
            "Folder must contain text_encoder/, unet/, vae_encoder/, vae_decoder/ (model.onnx each) plus vocab.json and merges.txt.",
            fontSize = 11.sp
          )
          folderMsg?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary) }
          Spacer(Modifier.height(4.dp))
        }
      }
      items(ModelCatalog.all) { def ->
        val st = states[def.id]
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
          Column(Modifier.padding(12.dp)) {
            Text(def.title, fontSize = 14.sp)
            Text(
              "~${def.approxMB} MB • ${st?.status ?: ModelDownloader.Status.Missing}",
              fontSize = 11.sp,
              color = MaterialTheme.colorScheme.secondary
            )
            def.note?.let { Text(it, fontSize = 11.sp) }
            if (st?.status == ModelDownloader.Status.Downloading) {
              LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            st?.message?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
              if (st?.status != ModelDownloader.Status.Ready) {
                Chip(false, "Download") { scope.launch { vm.downloader.download(def.id) } }
              }
              Chip(false, "Import file") { importId = def.id; importLauncher.launch("*/*") }
              Chip(false, "URL") { urlId = def.id; urlText = "" }
              if (st?.status == ModelDownloader.Status.Ready) {
                Chip(false, "Delete") { vm.downloader.delete(def.id) }
              }
            }
          }
        }
      }
      item { Spacer(Modifier.height(24.dp)) }
    }
  }

  if (urlId != null) {
    AlertDialog(
      onDismissRequest = { urlId = null },
      title = { Text("Custom model URL") },
      text = {
        OutlinedTextField(
          value = urlText,
          onValueChange = { urlText = it },
          label = { Text("https://…") },
          singleLine = true
        )
      },
      confirmButton = {
        TextButton(onClick = {
          val id = urlId!!
          vm.downloader.setCustomUrl(id, urlText)
          urlId = null
          scope.launch { vm.downloader.download(id) }
        }) { Text("Save & Download") }
      },
      dismissButton = { TextButton(onClick = { urlId = null }) { Text("Cancel") } }
    )
  }
}
