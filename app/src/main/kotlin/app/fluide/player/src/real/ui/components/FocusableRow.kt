package app.fluide.player.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

private val RowShape = RoundedCornerShape(12.dp)

/** Focused row: blue to cyan tinted glass. */
private val RowFocusBrush = Brush.horizontalGradient(
    listOf(FluideColors.Blue.copy(alpha = 0.55f), FluideColors.Cyan.copy(alpha = 0.22f)),
)

/** Selected (current group, playing channel...) but not focused: a subtle glass pill. */
private val RowSelectedFill = SolidColor(Color(0x17FFFFFF))

private val RowIdleFill = SolidColor(Color.Transparent)

/**
 * A row that works with a TV remote (D-pad focus, OK, long OK) and with touch. The focused row is
 * clearly highlighted (tinted glass, cyan border, slightly enlarged): on a TV the focus is the only
 * cursor the user has. A selected row gets a subtle pill with a small cyan marker at its start.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusableRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    selected: Boolean = false,
    onFocused: (() -> Unit)? = null,
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.02f else 1f, label = "focusableRow")
    // The modifier chain keeps the same structure in every state (only values change), so the
    // clickable / focus node is never recreated while the row has the focus.
    val fill = when {
        focused -> RowFocusBrush
        selected -> RowSelectedFill
        else -> RowIdleFill
    }
    val borderColor = when {
        focused -> FluideColors.Cyan
        selected -> FluideColors.GlassBorder
        else -> Color.Transparent
    }
    Row(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                if (it.isFocused && !focused) onFocused?.invoke()
                focused = it.isFocused
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RowShape)
            .background(fill, RowShape)
            .border(if (focused) 2.dp else 1.dp, borderColor, RowShape)
            .drawBehind {
                if (selected) {
                    // Small cyan marker at the start of the selected row.
                    val height = size.height * 0.46f
                    val width = 3.dp.toPx()
                    drawRoundRect(
                        color = FluideColors.Cyan,
                        topLeft = Offset(4.dp.toPx(), (size.height - height) / 2f),
                        size = Size(width, height),
                        cornerRadius = CornerRadius(width / 2f),
                    )
                }
            }
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Text without an explicit color stays readable on the highlight.
        CompositionLocalProvider(
            LocalContentColor provides if (focused) Color.White else MaterialTheme.colorScheme.onSurface,
        ) {
            content(focused)
        }
    }
}
