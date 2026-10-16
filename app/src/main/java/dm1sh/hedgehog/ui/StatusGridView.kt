package dm1sh.hedgehog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dm1sh.hedgehog.model.PartStatus
import kotlin.math.max
import kotlin.math.min

@Composable
fun StatusGridView(
    statuses: List<PartStatus>,
    tr: (String) -> String,
    onSpeakTooltip: (String) -> Unit,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier
) {
    val count = statuses.size
    if (count == 0) return

    val density = LocalDensity.current
    val boxSizeDp = 16.dp
    val boxGapDp = 6.dp
    val boxSizePx = with(density) { boxSizeDp.toPx() }
    val minGapPx = with(density) { boxGapDp.toPx() }
    val rowHeightPx = boxSizePx + minGapPx

    var containerWidthPx by remember { mutableIntStateOf(0) }
    var selectedTooltipIndex by remember { mutableIntStateOf(-1) }
    var tooltipOffsetPx by remember { mutableStateOf(Offset.Zero) }

    val cellMinPx = boxSizePx + minGapPx
    val boxesPerRow = if (containerWidthPx > 0) {
        max(1, (containerWidthPx / cellMinPx).toInt())
    } else {
        10
    }
    val actualGapPx = if (boxesPerRow > 1 && containerWidthPx > 0) {
        max(minGapPx, (containerWidthPx - boxesPerRow * boxSizePx) / boxesPerRow.toFloat())
    } else {
        minGapPx
    }
    val cellStepPx = boxSizePx + actualGapPx
    val totalRows = (count + boxesPerRow - 1) / boxesPerRow
    val contentHeightPx = max(rowHeightPx, totalRows * rowHeightPx - minGapPx)
    val contentHeightDp = with(density) { contentHeightPx.toDp() }
    val containerHeightDp = (contentHeightDp + 20.dp).coerceIn(60.dp, 260.dp)

    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(containerHeightDp)
            .onSizeChanged {
                containerWidthPx = it.width
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(containerHeightDp)
                .verticalScroll(scrollState)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(contentHeightDp)
                    .pointerInput(count, boxesPerRow, cellStepPx, rowHeightPx) {
                        detectTapGestures { tapPos ->
                            val col = (tapPos.x / cellStepPx).toInt()
                            val row = (tapPos.y / rowHeightPx).toInt()
                            val inBoxX = (tapPos.x % cellStepPx) <= boxSizePx
                            val inBoxY = (tapPos.y % rowHeightPx) <= boxSizePx
                            val idx = row * boxesPerRow + col
                            if (col in 0 until boxesPerRow && inBoxX && inBoxY && idx in 0 until count) {
                                if (selectedTooltipIndex == idx) {
                                    selectedTooltipIndex = -1
                                } else {
                                    selectedTooltipIndex = idx
                                    tooltipOffsetPx = Offset(
                                        x = col * cellStepPx,
                                        y = row * rowHeightPx - scrollState.value
                                    )
                                    val st = statuses[idx]
                                    val msg = "${tr("KEY_STAT_AREA_TEXT_1")}${idx + 1}: ${tr(st.trKey)}"
                                    onSpeakTooltip(msg)
                                }
                            } else {
                                selectedTooltipIndex = -1
                            }
                        }
                    }
            ) {
                val cornerPx = with(density) { 3.dp.toPx() }
                val scrollY = scrollState.value.toFloat()
                val viewH = with(density) { containerHeightDp.toPx() }
                val firstRow = max(0, (scrollY / rowHeightPx).toInt() - 1)
                val lastRow = min(totalRows, ((scrollY + viewH) / rowHeightPx).toInt() + 2)

                for (r in firstRow until lastRow) {
                    val startIdx = r * boxesPerRow
                    val endIdx = min(startIdx + boxesPerRow, count)
                    val y = r * rowHeightPx
                    var x = 0f
                    for (idx in startIdx until endIdx) {
                        val st = statuses[idx]
                        drawRoundRect(
                            color = st.color,
                            topLeft = Offset(x, y),
                            size = Size(boxSizePx, boxSizePx),
                            cornerRadius = CornerRadius(cornerPx, cornerPx)
                        )
                        x += cellStepPx
                    }
                }
            }
        }

        if (selectedTooltipIndex in 0 until count) {
            val st = statuses[selectedTooltipIndex]
            val tooltipText = "${tr("KEY_STAT_AREA_TEXT_1")}${selectedTooltipIndex + 1}: ${tr(st.trKey)}"
            val tx = tooltipOffsetPx.x.toInt().coerceIn(0, (containerWidthPx - 180).coerceAtLeast(0))
            val ty = (tooltipOffsetPx.y.toInt() - 44).coerceAtLeast(0)
            Box(
                modifier = Modifier
                    .offset { IntOffset(tx, ty) }
                    .background(Color(0xFF222222), RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFF555555), RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = tooltipText,
                    color = Color(0xFFEEEEEE),
                    fontSize = 15.sp,
                    fontFamily = fontFamily
                )
            }
        }
    }
}
