package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.runtime.Composable

/** A full-screen dialog to edit a chord's guitar shape. [label] is the shape label; onSave(null) goes back to the default shape. */
@Composable
fun ShapeEditor(label: String, shape: List<Int>?, defaultShape: List<Int>?, capo: Int,
                onSave: (List<Int>?) -> Unit, onCancel: () -> Unit) {}
