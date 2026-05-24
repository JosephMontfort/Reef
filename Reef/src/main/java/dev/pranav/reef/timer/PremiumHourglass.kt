package dev.pranav.reef.timer

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun PremiumHourglass(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "Hourglass")
    
    // 60-second cycle (60000 ms)
    val cycleProgress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(60000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Cycle"
    )

    // Sand falls for first 95% (57 seconds), flips during last 5% (3 seconds)
    val sandFallProgress = (cycleProgress / 0.95f).coerceIn(0f, 1f)
    
    val flipAngle = if (cycleProgress > 0.95f) {
        val flipProgress = (cycleProgress - 0.95f) / 0.05f
        // Ease in-out for premium smooth flip
        val easedFlip = CubicBezierEasing(0.42f, 0f, 0.58f, 1f).transform(flipProgress)
        easedFlip * 180f
    } else {
        0f
    }

    val glassOutlineColor = Color.White.copy(alpha = 0.7f)
    val sandColor = Color(0xFFFACC15) // Premium Gold
    val frameColor = Color(0xFF1F2937) // Dark Gray

    Canvas(
        modifier = modifier
            .size(120.dp)
            .padding(8.dp)
            .graphicsLayer {
                rotationZ = flipAngle
            }
    ) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2

        val frameWidth = w * 0.7f
        val frameHeight = h * 0.85f
        val topY = (h - frameHeight) / 2
        val bottomY = topY + frameHeight

        // Draw premium curved hourglass shape
        val glassPath = Path().apply {
            moveTo(cx - frameWidth / 2, topY)
            lineTo(cx + frameWidth / 2, topY)
            cubicTo(
                cx + frameWidth / 2, cy - h * 0.25f,
                cx + w * 0.1f, cy - h * 0.1f,
                cx + w * 0.04f, cy
            )
            cubicTo(
                cx + w * 0.1f, cy + h * 0.1f,
                cx + frameWidth / 2, cy + h * 0.25f,
                cx + frameWidth / 2, bottomY
            )
            lineTo(cx - frameWidth / 2, bottomY)
            cubicTo(
                cx - frameWidth / 2, cy + h * 0.25f,
                cx - w * 0.1f, cy + h * 0.1f,
                cx - w * 0.04f, cy
            )
            cubicTo(
                cx - w * 0.1f, cy - h * 0.1f,
                cx - frameWidth / 2, cy - h * 0.25f,
                cx - frameWidth / 2, topY
            )
            close()
        }

        clipPath(glassPath) {
            val maxSandHeight = (cy - topY) * 0.95f
            
            // Top Sand (shrinking)
            val topSandHeight = maxSandHeight * (1f - sandFallProgress)
            drawRect(
                color = sandColor,
                topLeft = Offset(0f, cy - topSandHeight),
                size = Size(w, topSandHeight)
            )

            // Bottom Sand (growing)
            val bottomSandHeight = maxSandHeight * sandFallProgress
            drawRect(
                color = sandColor,
                topLeft = Offset(0f, bottomY - bottomSandHeight),
                size = Size(w, bottomSandHeight)
            )

            // Falling Stream
            if (sandFallProgress > 0.01f && sandFallProgress < 0.99f) {
                drawLine(
                    color = sandColor,
                    start = Offset(cx, cy),
                    end = Offset(cx, bottomY - bottomSandHeight + h * 0.02f),
                    strokeWidth = w * 0.015f,
                    cap = StrokeCap.Round
                )
            }
        }

        // Glass Outline
        drawPath(
            path = glassPath,
            color = glassOutlineColor,
            style = Stroke(width = w * 0.02f, join = StrokeJoin.Round)
        )

        // Wood/Metal Caps
        val capHeight = h * 0.06f
        val capWidth = frameWidth + w * 0.1f
        val capCorner = CornerRadius(w * 0.02f, w * 0.02f)

        drawRoundRect(
            color = frameColor,
            topLeft = Offset(cx - capWidth / 2, topY - capHeight),
            size = Size(capWidth, capHeight),
            cornerRadius = capCorner
        )
        drawRoundRect(
            color = frameColor,
            topLeft = Offset(cx - capWidth / 2, bottomY),
            size = Size(capWidth, capHeight),
            cornerRadius = capCorner
        )
    }
}
