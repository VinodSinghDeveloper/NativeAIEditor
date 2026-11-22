package com.nativeai.editor.ai

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class ModelDownloader(private val context: Context) {
  enum class Status { Missing, Downloading, Ready, Failed }
  data class ModelState(val id: String, val status: Status, val progress: Float, val message: String?)

  val dir = File(context.filesDir, "models").apply { mkdirs() }
  private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)
  private val _states = MutableStateFlow(ModelCatalog.all.associate { it.id to initialState(it) })
  val states: StateFlow<Map<String, ModelState>> = _states

  fun file(id: String): File = File(dir, ModelCatalog.byId.getValue(id).fileName)
  fun produced(id: String, name: String): File = File(dir, name)
  fun isReady(id: String): Boolean = ModelCatalog.byId.getValue(id).produces.all { File(dir, it).length() > 0 }
  fun sdReady(): Boolean = ModelCatalog.sdIds.all { isReady(it) }

  private fun initialState(def: ModelDef): ModelState =
    ModelState(def.id, if (isReady(def.id)) Status.Ready else Status.Missing, if (isReady(def.id)) 1f else 0f, null)

  private fun set(id: String, status: Status, progress: Float, msg: String? = null) {
    _states.value = _states.value + (id to ModelState(id, status, progress, msg))
  }

  fun setCustomUrl(id: String, url: String) { prefs.edit().putString("url_$id", url.trim()).apply() }

  suspend fun download(id: String): Boolean = withContext(Dispatchers.IO) {
    val def = ModelCatalog.byId.getValue(id)
    if (isReady(id)) { set(id, Status.Ready, 1f); return@withContext true }
    val urls = (listOfNotNull(prefs.getString("url_$id", null)) + def.mirrors).distinct()
    if (urls.isEmpty()) { set(id, Status.Failed, 0f, "No URL. Import the file or add a custom URL."); return@withContext false }
    set(id, Status.Downloading, 0f)
    var lastErr = "unknown"
    for (url in urls) {
      repeat(2) { attempt ->
        try {
          val tmp = File(dir, def.fileName + ".part")
          fetch(url, tmp) { p -> set(id, Status.Downloading, p * 0.95f) }
          if (def.zipMap != null) extractZip(tmp, def.zipMap) else { tmp.renameTo(File(dir, def.fileName)) }
          tmp.delete()
          def.produces.filter { it.endsWith(".onnx") }.forEach { name ->
            try { Ort.session(File(dir, name)) } catch (e: Exception) {
              File(dir, name).delete(); throw IllegalStateException("Downloaded file is not a valid ONNX model: ${e.message}")
            }
          }
          set(id, Status.Ready, 1f)
          return@withContext true
        } catch (e: Exception) {
          lastErr = e.message ?: e.javaClass.simpleName
          if (attempt == 0) kotlinx.coroutines.delay(800)
        }
      }
    }
    set(id, Status.Failed, 0f, "All mirrors failed ($lastErr). Import locally or set a custom URL.")
    false
  }

  private fun fetch(url: String, tmp: File, onProgress: (Float) -> Unit) {
    var existing = if (tmp.exists()) tmp.length() else 0L
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 30000; conn.readTimeout = 60000
    conn.setRequestProperty("User-Agent", "NativeAIEditor/1.0")
    if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")
    try {
      conn.connect()
      val code = conn.responseCode
      if (code == 200) existing = 0L
      else if (code != 206 && code != 416) throw IllegalStateException("HTTP $code")
      if (code == 416) { tmp.delete(); existing = 0L; throw IllegalStateException("restart") }
      val total = if (code == 206) existing + conn.contentLengthLong else conn.contentLengthLong
      RandomAccessFile(tmp, "rw").use { out ->
        out.seek(existing)
        conn.inputStream.use { input ->
          val buf = ByteArray(65536)
          var done = existing; var lastTick = 0L
          while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n); done += n
            val now = System.currentTimeMillis()
            if (total > 0 && now - lastTick > 120) { onProgress(done.toFloat() / total); lastTick = now }
          }
        }
      }
      if (total > 0 && tmp.length() < total) throw IllegalStateException("Incomplete download")
      onProgress(1f)
    } finally { conn.disconnect() }
  }

  private fun extractZip(zip: File, map: Map<String, String>) {
    val found = HashSet<String>()
    ZipInputStream(zip.inputStream().buffered()).use { z ->
      while (true) {
        val e = z.nextEntry ?: break
        val base = e.name.substringAfterLast('/')
        val target = map[base]
        if (target != null && !e.isDirectory) {
          FileOutputStream(File(dir, target)).use { out -> z.copyTo(out, 65536) }
          found.add(base)
        }
        z.closeEntry()
      }
    }
    val missing = map.keys - found
    if (missing.isNotEmpty()) throw IllegalStateException("Zip missing entries: $missing")
  }

  suspend fun import(id: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
    val def = ModelCatalog.byId.getValue(id)
    try {
      set(id, Status.Downloading, 0.3f)
      val tmp = File(dir, def.fileName + ".import")
      context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(tmp).use { input.copyTo(it, 65536) } }
        ?: throw IllegalStateException("Cannot open file")
      if (def.zipMap != null) extractZip(tmp, def.zipMap) else tmp.renameTo(File(dir, def.fileName))
      tmp.delete()
      def.produces.filter { it.endsWith(".onnx") }.forEach { name ->
        try { Ort.session(File(dir, name)) } catch (e: Exception) {
          File(dir, name).delete(); throw IllegalStateException("Not a valid ONNX model: ${e.message}")
        }
      }
      set(id, Status.Ready, 1f); true
    } catch (e: Exception) {
      set(id, Status.Failed, 0f, "Import failed: ${e.message}"); false
    }
  }

  suspend fun importSdFolder(treeUri: Uri): Int = withContext(Dispatchers.IO) {
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext 0
    val wanted = mapOf(
      "text_encoder" to "sd_text_encoder", "unet" to "sd_unet",
      "vae_encoder" to "sd_vae_encoder", "vae_decoder" to "sd_vae_decoder"
    )
    var count = 0
    fun walk(d: DocumentFile, parent: String) {
      for (f in d.listFiles()) {
        val n = f.name ?: continue
        if (f.isDirectory) walk(f, n.lowercase())
        else {
          val target = when {
            n == "model.onnx" && wanted.containsKey(parent) -> wanted[parent]
            n == "vocab.json" -> "sd_vocab"
            n == "merges.txt" -> "sd_merges"
            else -> null
          }
          if (target != null) {
            val def = ModelCatalog.byId.getValue(target)
            context.contentResolver.openInputStream(f.uri)?.use { input ->
              FileOutputStream(File(dir, def.fileName)).use { input.copyTo(it, 65536) }
            }
            set(target, Status.Ready, 1f); count++
          }
        }
      }
    }
    walk(root, "")
    count
  }

  fun delete(id: String) {
    val def = ModelCatalog.byId.getValue(id)
    def.produces.forEach { Ort.evict(File(dir, it)); File(dir, it).delete() }
    File(dir, def.fileName).delete()
    set(id, Status.Missing, 0f)
  }

  suspend fun downloadCore(): Boolean {
    var ok = true
    for (def in ModelCatalog.core) if (!isReady(def.id)) ok = download(def.id) && ok
    prefs.edit().putBoolean("first_run_done", true).apply()
    return ok
  }

  fun firstRunPending(): Boolean =
    !prefs.getBoolean("first_run_done", false) && ModelCatalog.core.any { !isReady(it.id) }

  fun markFirstRunDone() { prefs.edit().putBoolean("first_run_done", true).apply() }
}
