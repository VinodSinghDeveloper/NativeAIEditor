package com.nativeai.editor.core

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

object Exporter {
  fun save(context: Context, bitmap: Bitmap, format: Int, quality: Int): Uri? {
    val fmt = when (format) {
      1 -> Bitmap.CompressFormat.JPEG
      2 -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
      else -> Bitmap.CompressFormat.PNG
    }
    val ext = when (format) { 1 -> "jpg"; 2 -> "webp"; else -> "png" }
    val mime = when (format) { 1 -> "image/jpeg"; 2 -> "image/webp"; else -> "image/png" }
    val name = "AIEdit_${System.currentTimeMillis()}.$ext"
    val bmp = if (format == 0) bitmap else flattenWhite(bitmap)
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, name)
      put(MediaStore.Images.Media.MIME_TYPE, mime)
      if (Build.VERSION.SDK_INT >= 29) {
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NativeAIEditor")
        put(MediaStore.Images.Media.IS_PENDING, 1)
      }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    resolver.openOutputStream(uri)?.use { bmp.compress(fmt, quality, it) } ?: return null
    if (Build.VERSION.SDK_INT >= 29) {
      values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
      resolver.update(uri, values, null, null)
    }
    return uri
  }
}
