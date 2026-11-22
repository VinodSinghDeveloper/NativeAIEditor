package com.nativeai.editor

import android.app.Application
import org.opencv.android.OpenCVLoader

class App : Application() {
  override fun onCreate() {
    super.onCreate()
    check(OpenCVLoader.initLocal()) { "OpenCV native init failed" }
  }
}
