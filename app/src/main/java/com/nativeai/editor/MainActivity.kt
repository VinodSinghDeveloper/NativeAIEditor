package com.nativeai.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nativeai.editor.ui.EditorScreen
import com.nativeai.editor.ui.Theme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      Theme {
        val vm: AppViewModel = viewModel()
        EditorScreen(vm)
      }
    }
  }
}
