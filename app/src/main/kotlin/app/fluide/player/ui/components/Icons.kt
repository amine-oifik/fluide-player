package app.fluide.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class Glyph {
    TV, MOVIE, SERIES, REPLAY, PLAYLIST, SETTINGS, REFRESH, EXIT, SEARCH, PLAY, PAUSE, STAR, LOCK, BACK, FORWARD, REWIND, NEXT, LIST, GUIDE,
    HOME, HEART, INFO, GRID, LANGUAGE, USER, CLOCK, CHECK,
    MIC, ALARM, MOON, SPEED, SWITCH, CALENDAR, DOWNLOAD, UPLOAD, PALETTE, MULTISCREEN,
}

/**
 * Simple line icons drawn on a canvas (no icon font or vector resources to ship), scaled to [size].
 */
@Composable
fun AppIcon(glyph: Glyph, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val stroke = Stroke(width = w * 0.08f, cap = StrokeCap.Round)
        val joined = Stroke(width = w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (glyph) {
            Glyph.TV -> {
                drawRoundRect(tint, Offset(w * 0.08f, w * 0.16f), Size(w * 0.84f, w * 0.56f), CornerRadius(w * 0.08f), style = stroke)
                drawLine(tint, Offset(w * 0.35f, w * 0.86f), Offset(w * 0.65f, w * 0.86f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.5f, w * 0.72f), Offset(w * 0.5f, w * 0.86f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.36f, w * 0.04f), Offset(w * 0.5f, w * 0.16f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.64f, w * 0.04f), Offset(w * 0.5f, w * 0.16f), stroke.width, StrokeCap.Round)
            }
            Glyph.MOVIE -> {
                drawRoundRect(tint, Offset(w * 0.1f, w * 0.14f), Size(w * 0.8f, w * 0.72f), CornerRadius(w * 0.06f), style = stroke)
                for (i in 0..3) {
                    val y = w * (0.24f + i * 0.17f)
                    drawRect(tint, Offset(w * 0.17f, y), Size(w * 0.08f, w * 0.07f))
                    drawRect(tint, Offset(w * 0.75f, y), Size(w * 0.08f, w * 0.07f))
                }
                triangle(tint, w * 0.42f, w * 0.38f, w * 0.24f)
            }
            Glyph.SERIES -> {
                drawRoundRect(tint, Offset(w * 0.22f, w * 0.08f), Size(w * 0.7f, w * 0.56f), CornerRadius(w * 0.06f), style = stroke)
                drawLine(tint, Offset(w * 0.08f, w * 0.28f), Offset(w * 0.08f, w * 0.86f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.08f, w * 0.86f), Offset(w * 0.72f, w * 0.86f), stroke.width, StrokeCap.Round)
                triangle(tint, w * 0.48f, w * 0.24f, w * 0.24f)
            }
            Glyph.REPLAY -> {
                drawArc(tint, 40f, 290f, false, Offset(w * 0.14f, w * 0.14f), Size(w * 0.72f, w * 0.72f), style = stroke)
                arrowHead(tint, Offset(w * 0.78f, w * 0.28f), w * 0.18f, 200f)
                drawLine(tint, Offset(w * 0.5f, w * 0.32f), Offset(w * 0.5f, w * 0.52f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.5f, w * 0.52f), Offset(w * 0.64f, w * 0.6f), stroke.width, StrokeCap.Round)
            }
            Glyph.PLAYLIST, Glyph.LIST -> {
                for (i in 0..2) {
                    val y = w * (0.24f + i * 0.22f)
                    drawLine(tint, Offset(w * 0.1f, y), Offset(if (i == 2 && glyph == Glyph.PLAYLIST) w * 0.5f else w * 0.9f, y), stroke.width, StrokeCap.Round)
                }
                if (glyph == Glyph.PLAYLIST) triangle(tint, w * 0.62f, w * 0.58f, w * 0.26f)
            }
            Glyph.SETTINGS -> {
                val c = Offset(w / 2, w / 2)
                for (i in 0 until 8) {
                    rotate(i * 45f, c) {
                        drawRoundRect(tint, Offset(w * 0.43f, w * 0.06f), Size(w * 0.14f, w * 0.2f), CornerRadius(w * 0.03f))
                    }
                }
                drawCircle(tint, w * 0.29f, c, style = stroke)
                drawCircle(tint, w * 0.1f, c, style = stroke)
            }
            Glyph.REFRESH -> {
                drawArc(tint, -60f, 300f, false, Offset(w * 0.16f, w * 0.16f), Size(w * 0.68f, w * 0.68f), style = stroke)
                arrowHead(tint, Offset(w * 0.68f, w * 0.2f), w * 0.2f, 120f)
            }
            Glyph.EXIT -> {
                drawLine(tint, Offset(w * 0.55f, w * 0.12f), Offset(w * 0.15f, w * 0.12f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.15f, w * 0.12f), Offset(w * 0.15f, w * 0.88f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.15f, w * 0.88f), Offset(w * 0.55f, w * 0.88f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.4f, w * 0.5f), Offset(w * 0.9f, w * 0.5f), stroke.width, StrokeCap.Round)
                arrowHead(tint, Offset(w * 0.92f, w * 0.5f), w * 0.2f, 0f)
            }
            Glyph.SEARCH -> {
                drawCircle(tint, w * 0.28f, Offset(w * 0.42f, w * 0.42f), style = stroke)
                drawLine(tint, Offset(w * 0.62f, w * 0.62f), Offset(w * 0.88f, w * 0.88f), stroke.width * 1.3f, StrokeCap.Round)
            }
            Glyph.PLAY -> triangle(tint, w * 0.26f, w * 0.14f, w * 0.72f)
            Glyph.PAUSE -> {
                drawRoundRect(tint, Offset(w * 0.22f, w * 0.14f), Size(w * 0.18f, w * 0.72f), CornerRadius(w * 0.04f))
                drawRoundRect(tint, Offset(w * 0.6f, w * 0.14f), Size(w * 0.18f, w * 0.72f), CornerRadius(w * 0.04f))
            }
            Glyph.STAR -> {
                val path = Path()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) w * 0.46f else w * 0.19f
                    val angle = Math.toRadians((-90 + i * 36).toDouble())
                    val x = w / 2 + (r * Math.cos(angle)).toFloat()
                    val y = w * 0.53f + (r * Math.sin(angle)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, tint)
            }
            Glyph.LOCK -> {
                drawRoundRect(tint, Offset(w * 0.18f, w * 0.44f), Size(w * 0.64f, w * 0.46f), CornerRadius(w * 0.08f))
                drawArc(tint, 180f, 180f, false, Offset(w * 0.3f, w * 0.14f), Size(w * 0.4f, w * 0.5f), style = stroke)
            }
            Glyph.BACK -> {
                drawLine(tint, Offset(w * 0.86f, w * 0.5f), Offset(w * 0.2f, w * 0.5f), stroke.width, StrokeCap.Round)
                arrowHead(tint, Offset(w * 0.14f, w * 0.5f), w * 0.26f, 180f)
            }
            Glyph.FORWARD, Glyph.REWIND -> {
                val flip = glyph == Glyph.REWIND
                for (i in 0..1) {
                    val x0 = w * (0.12f + i * 0.38f)
                    val path = Path().apply {
                        if (!flip) {
                            moveTo(x0, w * 0.2f); lineTo(x0 + w * 0.38f, w * 0.5f); lineTo(x0, w * 0.8f)
                        } else {
                            moveTo(x0 + w * 0.38f, w * 0.2f); lineTo(x0, w * 0.5f); lineTo(x0 + w * 0.38f, w * 0.8f)
                        }
                        close()
                    }
                    drawPath(path, tint)
                }
            }
            Glyph.NEXT -> {
                triangle(tint, w * 0.16f, w * 0.18f, w * 0.64f)
                drawRoundRect(tint, Offset(w * 0.7f, w * 0.18f), Size(w * 0.12f, w * 0.64f), CornerRadius(w * 0.03f))
            }
            Glyph.GUIDE -> {
                drawRoundRect(tint, Offset(w * 0.1f, w * 0.14f), Size(w * 0.8f, w * 0.74f), CornerRadius(w * 0.08f), style = stroke)
                drawLine(tint, Offset(w * 0.1f, w * 0.34f), Offset(w * 0.9f, w * 0.34f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.3f, w * 0.06f), Offset(w * 0.3f, w * 0.2f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.7f, w * 0.06f), Offset(w * 0.7f, w * 0.2f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.24f, w * 0.52f), Offset(w * 0.76f, w * 0.52f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.24f, w * 0.7f), Offset(w * 0.56f, w * 0.7f), stroke.width, StrokeCap.Round)
            }
            Glyph.HOME -> {
                val roof = Path().apply {
                    moveTo(w * 0.1f, w * 0.47f)
                    lineTo(w * 0.5f, w * 0.12f)
                    lineTo(w * 0.9f, w * 0.47f)
                }
                drawPath(roof, tint, style = joined)
                val walls = Path().apply {
                    moveTo(w * 0.22f, w * 0.4f)
                    lineTo(w * 0.22f, w * 0.86f)
                    lineTo(w * 0.78f, w * 0.86f)
                    lineTo(w * 0.78f, w * 0.4f)
                }
                drawPath(walls, tint, style = joined)
                drawRoundRect(tint, Offset(w * 0.42f, w * 0.6f), Size(w * 0.16f, w * 0.26f), CornerRadius(w * 0.03f))
            }
            Glyph.HEART -> {
                val path = Path().apply {
                    moveTo(w * 0.5f, w * 0.87f)
                    cubicTo(w * 0.24f, w * 0.69f, w * 0.07f, w * 0.53f, w * 0.07f, w * 0.36f)
                    cubicTo(w * 0.07f, w * 0.22f, w * 0.18f, w * 0.13f, w * 0.3f, w * 0.13f)
                    cubicTo(w * 0.39f, w * 0.13f, w * 0.46f, w * 0.18f, w * 0.5f, w * 0.26f)
                    cubicTo(w * 0.54f, w * 0.18f, w * 0.61f, w * 0.13f, w * 0.7f, w * 0.13f)
                    cubicTo(w * 0.82f, w * 0.13f, w * 0.93f, w * 0.22f, w * 0.93f, w * 0.36f)
                    cubicTo(w * 0.93f, w * 0.53f, w * 0.76f, w * 0.69f, w * 0.5f, w * 0.87f)
                    close()
                }
                drawPath(path, tint)
            }
            Glyph.INFO -> {
                drawCircle(tint, w * 0.4f, Offset(w / 2, w / 2), style = stroke)
                drawCircle(tint, w * 0.055f, Offset(w * 0.5f, w * 0.31f))
                drawLine(tint, Offset(w * 0.5f, w * 0.46f), Offset(w * 0.5f, w * 0.71f), stroke.width, StrokeCap.Round)
            }
            Glyph.GRID -> {
                for (row in 0..1) {
                    for (col in 0..1) {
                        drawRoundRect(
                            tint,
                            Offset(w * (0.12f + col * 0.46f), w * (0.12f + row * 0.46f)),
                            Size(w * 0.3f, w * 0.3f),
                            CornerRadius(w * 0.07f),
                            style = stroke,
                        )
                    }
                }
            }
            Glyph.LANGUAGE -> {
                drawCircle(tint, w * 0.4f, Offset(w / 2, w / 2), style = stroke)
                drawOval(tint, Offset(w * 0.32f, w * 0.1f), Size(w * 0.36f, w * 0.8f), style = stroke)
                drawLine(tint, Offset(w * 0.1f, w * 0.5f), Offset(w * 0.9f, w * 0.5f), stroke.width, StrokeCap.Round)
            }
            Glyph.USER -> {
                drawCircle(tint, w * 0.16f, Offset(w * 0.5f, w * 0.3f), style = stroke)
                drawArc(tint, 180f, 180f, false, Offset(w * 0.16f, w * 0.6f), Size(w * 0.68f, w * 0.56f), style = stroke)
                drawLine(tint, Offset(w * 0.16f, w * 0.88f), Offset(w * 0.84f, w * 0.88f), stroke.width, StrokeCap.Round)
            }
            Glyph.CLOCK -> {
                val c = Offset(w / 2, w / 2)
                drawCircle(tint, w * 0.4f, c, style = stroke)
                drawLine(tint, c, Offset(w * 0.5f, w * 0.26f), stroke.width, StrokeCap.Round)
                drawLine(tint, c, Offset(w * 0.67f, w * 0.6f), stroke.width, StrokeCap.Round)
            }
            Glyph.CHECK -> {
                val path = Path().apply {
                    moveTo(w * 0.16f, w * 0.52f)
                    lineTo(w * 0.4f, w * 0.75f)
                    lineTo(w * 0.86f, w * 0.27f)
                }
                drawPath(path, tint, style = Stroke(width = w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Glyph.MIC -> {
                drawRoundRect(tint, Offset(w * 0.36f, w * 0.08f), Size(w * 0.28f, w * 0.48f), CornerRadius(w * 0.14f), style = stroke)
                drawArc(tint, 0f, 180f, false, Offset(w * 0.22f, w * 0.26f), Size(w * 0.56f, w * 0.46f), style = stroke)
                drawLine(tint, Offset(w * 0.5f, w * 0.72f), Offset(w * 0.5f, w * 0.88f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.34f, w * 0.88f), Offset(w * 0.66f, w * 0.88f), stroke.width, StrokeCap.Round)
            }
            Glyph.ALARM -> {
                // A bell, its knob and its clapper.
                val bell = Path().apply {
                    moveTo(w * 0.16f, w * 0.74f)
                    lineTo(w * 0.84f, w * 0.74f)
                    cubicTo(w * 0.75f, w * 0.65f, w * 0.74f, w * 0.57f, w * 0.74f, w * 0.46f)
                    cubicTo(w * 0.74f, w * 0.3f, w * 0.63f, w * 0.18f, w * 0.5f, w * 0.18f)
                    cubicTo(w * 0.37f, w * 0.18f, w * 0.26f, w * 0.3f, w * 0.26f, w * 0.46f)
                    cubicTo(w * 0.26f, w * 0.57f, w * 0.25f, w * 0.65f, w * 0.16f, w * 0.74f)
                    close()
                }
                drawPath(bell, tint, style = joined)
                drawLine(tint, Offset(w * 0.5f, w * 0.08f), Offset(w * 0.5f, w * 0.18f), stroke.width, StrokeCap.Round)
                drawArc(tint, 0f, 180f, false, Offset(w * 0.4f, w * 0.68f), Size(w * 0.2f, w * 0.2f), style = stroke)
            }
            Glyph.MOON -> {
                // Crescent: the outer circle, then the inner edge along a second circle.
                val moon = Path().apply {
                    arcTo(Rect(Offset(w * 0.5f, w * 0.5f), w * 0.38f), 13.3f, 256.1f, true)
                    arcTo(Rect(Offset(w * 0.7f, w * 0.34f), w * 0.3f), 227.2f, -171.6f, false)
                    close()
                }
                drawPath(moon, tint, style = joined)
            }
            Glyph.SPEED -> {
                // Gauge: an open dial, its needle and its hub.
                val hub = Offset(w * 0.5f, w * 0.6f)
                drawArc(tint, 150f, 240f, false, Offset(w * 0.12f, w * 0.22f), Size(w * 0.76f, w * 0.76f), style = stroke)
                drawLine(tint, hub, Offset(w * 0.71f, w * 0.39f), stroke.width, StrokeCap.Round)
                drawCircle(tint, w * 0.075f, hub)
            }
            Glyph.SWITCH -> {
                drawLine(tint, Offset(w * 0.12f, w * 0.32f), Offset(w * 0.76f, w * 0.32f), stroke.width, StrokeCap.Round)
                arrowHead(tint, Offset(w * 0.9f, w * 0.32f), w * 0.2f, 0f)
                drawLine(tint, Offset(w * 0.88f, w * 0.68f), Offset(w * 0.24f, w * 0.68f), stroke.width, StrokeCap.Round)
                arrowHead(tint, Offset(w * 0.1f, w * 0.68f), w * 0.2f, 180f)
            }
            Glyph.CALENDAR -> {
                drawRoundRect(tint, Offset(w * 0.1f, w * 0.16f), Size(w * 0.8f, w * 0.72f), CornerRadius(w * 0.08f), style = stroke)
                drawLine(tint, Offset(w * 0.1f, w * 0.36f), Offset(w * 0.9f, w * 0.36f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.32f, w * 0.06f), Offset(w * 0.32f, w * 0.22f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.68f, w * 0.06f), Offset(w * 0.68f, w * 0.22f), stroke.width, StrokeCap.Round)
                for (row in 0..1) {
                    for (col in 0..2) {
                        drawRoundRect(
                            tint,
                            Offset(w * (0.25f + col * 0.2f), w * (0.48f + row * 0.18f)),
                            Size(w * 0.1f, w * 0.1f),
                            CornerRadius(w * 0.02f),
                        )
                    }
                }
            }
            Glyph.DOWNLOAD, Glyph.UPLOAD -> {
                if (glyph == Glyph.DOWNLOAD) {
                    drawLine(tint, Offset(w * 0.5f, w * 0.08f), Offset(w * 0.5f, w * 0.5f), stroke.width, StrokeCap.Round)
                    arrowHead(tint, Offset(w * 0.5f, w * 0.64f), w * 0.22f, 90f)
                } else {
                    drawLine(tint, Offset(w * 0.5f, w * 0.64f), Offset(w * 0.5f, w * 0.22f), stroke.width, StrokeCap.Round)
                    arrowHead(tint, Offset(w * 0.5f, w * 0.08f), w * 0.22f, 270f)
                }
                val tray = Path().apply {
                    moveTo(w * 0.14f, w * 0.62f)
                    lineTo(w * 0.14f, w * 0.88f)
                    lineTo(w * 0.86f, w * 0.88f)
                    lineTo(w * 0.86f, w * 0.62f)
                }
                drawPath(tray, tint, style = joined)
            }
            Glyph.PALETTE -> {
                // Painter's palette with its thumb notch and four paint dots.
                val board = Path().apply {
                    moveTo(w * 0.5f, w * 0.1f)
                    cubicTo(w * 0.76f, w * 0.1f, w * 0.92f, w * 0.28f, w * 0.92f, w * 0.48f)
                    cubicTo(w * 0.92f, w * 0.64f, w * 0.8f, w * 0.7f, w * 0.68f, w * 0.66f)
                    cubicTo(w * 0.58f, w * 0.63f, w * 0.52f, w * 0.7f, w * 0.56f, w * 0.78f)
                    cubicTo(w * 0.6f, w * 0.88f, w * 0.52f, w * 0.92f, w * 0.44f, w * 0.9f)
                    cubicTo(w * 0.22f, w * 0.86f, w * 0.08f, w * 0.68f, w * 0.08f, w * 0.48f)
                    cubicTo(w * 0.08f, w * 0.26f, w * 0.26f, w * 0.1f, w * 0.5f, w * 0.1f)
                    close()
                }
                drawPath(board, tint, style = joined)
                drawCircle(tint, w * 0.065f, Offset(w * 0.28f, w * 0.52f))
                drawCircle(tint, w * 0.065f, Offset(w * 0.34f, w * 0.32f))
                drawCircle(tint, w * 0.065f, Offset(w * 0.54f, w * 0.25f))
                drawCircle(tint, w * 0.065f, Offset(w * 0.73f, w * 0.38f))
            }
            Glyph.MULTISCREEN -> {
                // A screen split in four, on its stand.
                drawRoundRect(tint, Offset(w * 0.06f, w * 0.12f), Size(w * 0.88f, w * 0.62f), CornerRadius(w * 0.07f), style = stroke)
                drawLine(tint, Offset(w * 0.5f, w * 0.12f), Offset(w * 0.5f, w * 0.74f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.06f, w * 0.43f), Offset(w * 0.94f, w * 0.43f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.5f, w * 0.74f), Offset(w * 0.5f, w * 0.88f), stroke.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.32f, w * 0.88f), Offset(w * 0.68f, w * 0.88f), stroke.width, StrokeCap.Round)
            }
        }
    }
}

/** Right-pointing filled triangle in the box (x, y, side). */
private fun DrawScope.triangle(color: Color, x: Float, y: Float, side: Float) {
    val path = Path().apply {
        moveTo(x, y)
        lineTo(x + side * 0.87f, y + side / 2)
        lineTo(x, y + side)
        close()
    }
    drawPath(path, color)
}

/** Filled arrow head at [tip], pointing to [degrees] (0 = right, clockwise). */
private fun DrawScope.arrowHead(color: Color, tip: Offset, length: Float, degrees: Float) {
    rotate(degrees, tip) {
        val path = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(tip.x - length, tip.y - length * 0.6f)
            lineTo(tip.x - length, tip.y + length * 0.6f)
            close()
        }
        drawPath(path, color)
    }
}
