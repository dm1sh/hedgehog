package dm1sh.hedgehog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dm1sh.hedgehog.model.PartStatus
import kotlin.math.max
import kotlin.math.min

@Composable
fun StatusGridView(
    statuses: List<PartStatus>,
    tr: (String) -> String,
    onSpeakTooltip: (String) -> Unit,
    modifier: Modifier = Modifier,
    offlineFallbackMap: Map<Int, Boolean> = emptyMap(),
    maxHeightDp: androidx.compose.ui.unit.Dp = 280.dp
) {
    val count = statuses.size
    if (count == 0) return

    val fallbackOutlineColor = MaterialTheme.colorScheme.onSurface

    val density = LocalDensity.current
    val boxSizeDp = 16.dp
    val boxGapDp = 6.dp
    val boxSizePx = with(density) { boxSizeDp.toPx() }
    val gapPx = with(density) { boxGapDp.toPx() }

    var containerWidthPx by remember { mutableIntStateOf(0) }
    var containerHeightPx by remember { mutableIntStateOf(0) }
    var selectedTooltipIndex by remember { mutableIntStateOf(-1) }
    var tooltipOffsetPx by remember { mutableStateOf(Offset.Zero) }

    val cellStepPx = boxSizePx + gapPx
    val rowHeightPx = boxSizePx + gapPx
    val boxesPerRow = if (containerWidthPx > 0 && cellStepPx > 0f) {
        ((containerWidthPx + gapPx) / cellStepPx).toInt().coerceAtLeast(1)
    } else {
        10
    }
    val gridWidthPx = boxesPerRow * boxSizePx + (boxesPerRow - 1) * gapPx
    val startXPx = if (containerWidthPx > 0) {
        max(0f, (containerWidthPx - gridWidthPx) / 2f)
    } else {
        0f
    }
    val totalRows = (count + boxesPerRow - 1) / boxesPerRow
    val contentHeightPx = max(rowHeightPx, totalRows * rowHeightPx - gapPx)
    val contentHeightDp = with(density) { contentHeightPx.toDp() }
    val minRows = 10
    val minViewportHeightDp = boxSizeDp * minRows + boxGapDp * (minRows - 1)
    val effectiveMaxHeightDp = maxOf(minViewportHeightDp, maxHeightDp)
    val viewportHeightDp = contentHeightDp.coerceIn(minViewportHeightDp, effectiveMaxHeightDp)

    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(viewportHeightDp)
            .onSizeChanged {
                containerWidthPx = it.width
                containerHeightPx = it.height
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(contentHeightDp)
                    .pointerInput(count, boxesPerRow, cellStepPx, rowHeightPx, startXPx) {
                        detectTapGestures { tapPos ->
                            val localX = tapPos.x - startXPx
                            if (localX >= 0f && tapPos.y >= 0f) {
                                val col = (localX / cellStepPx).toInt()
                                val row = (tapPos.y / rowHeightPx).toInt()
                                val inBoxX = (localX % cellStepPx) <= boxSizePx
                                val inBoxY = (tapPos.y % rowHeightPx) <= boxSizePx
                                val idx = row * boxesPerRow + col
                                if (col in 0 until boxesPerRow && inBoxX && inBoxY && idx in 0 until count) {
                                    if (selectedTooltipIndex == idx) {
                                        selectedTooltipIndex = -1
                                    } else {
                                        selectedTooltipIndex = idx
                                        tooltipOffsetPx = Offset(
                                            x = startXPx + col * cellStepPx,
                                            y = row * rowHeightPx - scrollState.value
                                        )
                                        val st = statuses[idx]
                                        val fallbackSuffix = if (offlineFallbackMap[idx] == true) {
                                            " (${tr("KEY_VOICE_OFFLINE")})"
                                        } else {
                                            ""
                                        }
                                        val msg = "${tr("KEY_STAT_AREA_TEXT_1")}${idx + 1}: ${tr(st.trKey).trim()}$fallbackSuffix"
                                        onSpeakTooltip(msg)
                                    }
                                } else {
                                    selectedTooltipIndex = -1
                                }
                            } else {
                                selectedTooltipIndex = -1
                            }
                        }
                    }
            ) {
                val cornerPx = with(density) { 3.dp.toPx() }
                val strokePx = with(density) { 2.dp.toPx() }
                val halfStroke = strokePx / 2f
                val scrollY = scrollState.value.toFloat()
                val viewH = if (containerHeightPx > 0) containerHeightPx.toFloat() else size.height
                val firstRow = max(0, (scrollY / rowHeightPx).toInt() - 1)
                val lastRow = min(totalRows, ((scrollY + viewH) / rowHeightPx).toInt() + 2)

                for (r in firstRow until lastRow) {
                    val startIdx = r * boxesPerRow
                    val endIdx = min(startIdx + boxesPerRow, count)
                    val y = r * rowHeightPx
                    var x = startXPx
                    for (idx in startIdx until endIdx) {
                        val st = statuses[idx]
                        drawRoundRect(
                            color = st.color,
                            topLeft = Offset(x, y),
                            size = Size(boxSizePx, boxSizePx),
                            cornerRadius = CornerRadius(cornerPx, cornerPx)
                        )
                        if (offlineFallbackMap[idx] == true) {
                            drawRoundRect(
                                color = fallbackOutlineColor,
                                topLeft = Offset(x + halfStroke, y + halfStroke),
                                size = Size(boxSizePx - strokePx, boxSizePx - strokePx),
                                cornerRadius = CornerRadius(
                                    max(0f, cornerPx - halfStroke),
                                    max(0f, cornerPx - halfStroke)
                                ),
                                style = Stroke(width = strokePx)
                            )
                        }
                        x += cellStepPx
                    }
                }
            }
        }

        if (selectedTooltipIndex in 0 until count) {
            val st = statuses[selectedTooltipIndex]
            val fallbackSuffix = if (offlineFallbackMap[selectedTooltipIndex] == true) {
                " (${tr("KEY_VOICE_OFFLINE")})"
            } else {
                ""
            }
            val tooltipText = "${tr("KEY_STAT_AREA_TEXT_1")}${selectedTooltipIndex + 1}: ${tr(st.trKey).trim()}$fallbackSuffix"
            val tx = tooltipOffsetPx.x.toInt().coerceIn(0, (containerWidthPx - 200).coerceAtLeast(0))
            val ty = (tooltipOffsetPx.y.toInt() - 42).coerceAtLeast(0)
            Surface(
                modifier = Modifier.offset { IntOffset(tx, ty) },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                tonalElevation = 4.dp,
                shadowElevation = 4.dp
            ) {
                Text(
                    text = tooltipText,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}
