package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

actual val hasNativeSegmentedControl: Boolean = false

@Composable
actual fun NativeSegmentedControl(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier) = Unit
