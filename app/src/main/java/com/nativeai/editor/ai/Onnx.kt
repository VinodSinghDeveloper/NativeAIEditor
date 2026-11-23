package com.nativeai.editor.ai

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.min

object Ort {
  val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
  private val sessions = HashMap<String, OrtSession>()

  fun session(file: File): OrtSession = synchronized(sessions) {
    sessions.getOrPut(file.absolutePath) {
      val opts = OrtSession.SessionOptions()
      opts.setIntraOpNumThreads(min(4, Runtime.getRuntime().availableProcessors()))
      opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
      env.createSession(file.absolutePath, opts)
    }
  }

  fun evict(file: File) = synchronized(sessions) { sessions.remove(file.absolutePath)?.close() }

  fun fTensor(shape: LongArray, data: FloatArray): OnnxTensor =
    OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)

  fun lTensor(shape: LongArray, data: LongArray): OnnxTensor =
    OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)

  fun tensorFor(session: OrtSession, name: String, data: FloatArray, shape: LongArray): OnnxTensor {
    val ti = session.inputInfo[name]?.info as? TensorInfo
    return if (ti != null && ti.type == OnnxJavaType.INT64)
      lTensor(shape, LongArray(data.size) { data[it].toLong() })
    else fTensor(shape, data)
  }

  fun fixedInputHW(file: File): Pair<Int, Int>? {
    val s = session(file)
    val name = s.inputNames.first()
    val ti = s.inputInfo[name]?.info as? TensorInfo ?: return null
    val sh = ti.shape
    if (sh.size == 4 && sh[2] > 0 && sh[3] > 0) return sh[2].toInt() to sh[3].toInt()
    return null
  }
}

fun readFloat(t: OnnxTensor): FloatArray {
  val buf = t.floatBuffer
  val arr = FloatArray(buf.remaining())
  buf.get(arr)
  return arr
}

inline fun <R> withRun(file: File, inputs: Map<String, OnnxTensor>, block: (OrtSession.Result) -> R): R {
  val res = Ort.session(file).run(inputs)
  try { return block(res) } finally {
    res.close()
    inputs.values.forEach { it.close() }
  }

  fun isNhwcInput(file: java.io.File): Boolean {
    val sh = (session(file).inputInfo.values.firstOrNull()?.info as? ai.onnxruntime.TensorInfo)?.shape ?: return false
    return sh.size == 4 && (sh.get(3).toInt() == 3 || sh.get(3).toInt() == 1) && sh.get(1).toInt() != 3
  }
  fun readFloatAny(res: ai.onnxruntime.OrtSession.Result): FloatArray =
    readFloat(firstTensor(res))

}
