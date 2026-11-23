package com.nativeai.editor.ai
import ai.onnxruntime.*
import java.io.File
import java.nio.*
object Ort {
  val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
  private val cache = mutableMapOf<String, OrtSession>()
  @Synchronized fun session(file: File): OrtSession {
    val key = file.absolutePath + ":" + file.lastModified()
    cache.get(key)?.let { return it }
    val opts = OrtSession.SessionOptions().apply {
      setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
      setIntraOpNumThreads(4)
      runCatching { addNnapi() }
    }
    return env.createSession(file.absolutePath, opts).also { cache.put(key, it) }
  }
  fun tensor(data: FloatArray, shape: LongArray): OnnxTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
  fun tensorLong(data: LongArray, shape: LongArray): OnnxTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)
  fun inputNames(s: OrtSession): List<String> = s.inputNames.toList()
  fun outputNames(s: OrtSession): List<String> = s.outputNames.toList()
  fun fixedInputHW(s: OrtSession): Pair<Int, Int>? {
    val info = s.inputInfo.values.firstOrNull()?.info as? TensorInfo ?: return null
    val sh = info.shape
    if (sh.size == 4) {
      val d1 = sh.get(1).toInt(); val d2 = sh.get(2).toInt(); val d3 = sh.get(3).toInt()
      val nhwc = (d3 == 3 || d3 == 1) && d1 != 3
      val h = if (nhwc) d1 else d2; val w = if (nhwc) d2 else d3
      if (h > 0 && w > 0) return h to w
    }
    return null
  }
  fun isNhwcInput(s: OrtSession): Boolean {
    val sh = (s.inputInfo.values.firstOrNull()?.info as? TensorInfo)?.shape ?: return false
    return sh.size == 4 && (sh.get(3).toInt() == 3 || sh.get(3).toInt() == 1) && sh.get(1).toInt() != 3
  }
  fun isInt64Input(s: OrtSession, name: String): Boolean =
    (s.inputInfo.get(name)?.info as? TensorInfo)?.type == OnnxJavaType.INT64
}
