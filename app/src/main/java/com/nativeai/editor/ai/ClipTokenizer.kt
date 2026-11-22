package com.nativeai.editor.ai

import org.json.JSONObject
import java.util.Locale

class ClipTokenizer(vocabJson: String, mergesTxt: String) {
  private val vocab = HashMap<String, Int>()
  private val ranks = HashMap<Pair<String, String>, Int>()
  private val byteEnc = IntArray(256)

  init {
    val obj = JSONObject(vocabJson)
    for (k in obj.keys()) vocab[k] = obj.getInt(k)
    mergesTxt.lines().filter { it.isNotBlank() && !it.startsWith("#version") }.forEachIndexed { i, line ->
      val parts = line.trim().split(" ")
      if (parts.size == 2) ranks[parts[0] to parts[1]] = i
    }
    val bs = ArrayList<Int>(); val cs = ArrayList<Int>()
    for (b in 33..126) { bs.add(b); cs.add(b) }
    for (b in 161..172) { bs.add(b); cs.add(b) }
    for (b in 174..255) { bs.add(b); cs.add(b) }
    var n = 0
    for (b in 0..255) if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
    for (i in bs.indices) byteEnc[bs[i]] = cs[i]
  }

  fun encode(text: String): LongArray {
    val ids = ArrayList<Int>()
    ids.add(49406)
    val clean = text.lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
    for (m in PAT.findAll(clean)) {
      val symbols = m.value.toByteArray(Charsets.UTF_8)
        .map { byteEnc[it.toInt() and 0xFF].toChar().toString() }.toMutableList()
      while (symbols.size > 1) {
        var best: Pair<String, String>? = null
        var bestRank = Int.MAX_VALUE
        for (i in 0 until symbols.size - 1) {
          val pr = symbols[i] to symbols[i + 1]
          val r = ranks[pr]
          if (r != null && r < bestRank) { bestRank = r; best = pr }
        }
        val b = best ?: break
        val merged = ArrayList<String>()
        var i = 0
        while (i < symbols.size) {
          if (i < symbols.size - 1 && symbols[i] == b.first && symbols[i + 1] == b.second) {
            merged.add(b.first + b.second); i += 2
          } else { merged.add(symbols[i]); i++ }
        }
        symbols.clear(); symbols.addAll(merged)
      }
      for (s in symbols) vocab[s]?.let { ids.add(it) }
    }
    val out = LongArray(77) { 49407L }
    val take = minOf(ids.size, 76)
    for (i in 0 until take) out[i] = ids[i].toLong()
    out[take] = 49407L
    return out
  }

  companion object {
    private val PAT = Regex("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+")
  }
}
