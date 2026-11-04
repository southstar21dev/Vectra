package dev.southstar21.vectra.core.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** True where [NativeSegmentedControl] is the platform's own control (iOS: UISegmentedControl). */
expect val hasNativeSegmentedControl: Boolean

/**
 * The platform's segmented control (Liquid Glass on iOS 26). Only call it where
 * [hasNativeSegmentedControl] is true; elsewhere it draws nothing.
 */
@Composable
expect fun NativeSegmentedControl(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
)
