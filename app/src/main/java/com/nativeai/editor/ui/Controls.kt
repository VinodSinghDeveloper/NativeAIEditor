package com.nativeai.editor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, fmt: (Float) -> String = { "%.2f".format(it) }) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
    Text(label, fontSize = 12.sp, modifier = Modifier.width(92.dp))
    Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
    Text(fmt(value), fontSize = 11.sp, modifier = Modifier.width(48.dp))
  }
}

@Composable
fun Chip(selected: Boolean, label: String, onClick: () -> Unit, enabled: Boolean = true) {
  if (selected) {
    Button(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), modifier = Modifier.padding(2.dp)) { Text(label, fontSize = 12.sp) }
  } else {
    OutlinedButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), modifier = Modifier.padding(2.dp)) { Text(label, fontSize = 12.sp) }
  }
}

@Composable
fun PanelTitle(text: String) {
  Text(text, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp), color = MaterialTheme.colorScheme.secondary)
}
