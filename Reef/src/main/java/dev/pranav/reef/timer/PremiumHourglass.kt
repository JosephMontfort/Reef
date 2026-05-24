package dev.pranav.reef.timer

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp

@Composable
fun PremiumHourglass(
    progress: Float, // 1f = Full Top, 0f = Empty Top
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    // Stream animation for falling granular sand
    val infiniteTransition = rememberInfiniteTransition(label = "stream")
    val streamPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing)
        ),
        label = "sand_stream"
    )

    // Ultra-Premium Colors
    val sandColorDark = Color(0xFFD4AF37)   // 3D Depth Gold
    val sandColorLight = Color(0xFFFDE047)  // Highlight Gold
    val glassColorLight = Color(0x99FFFFFF) // Thick Glass
    val glassColorDark = Color(0x1AFFFFFF)  // Thin Glass
    
    // Smooth progress interpolation so it drains like liquid
    val smoothProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(1000, easing = LinearOutSlowInEasing),
        label = "drain"
    )

    Canvas(modifier = modifier.size(180.dp).padding(16.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2
        val bulbW = w * 0.75f
        val bulbH = h * 0.44f
        val neckW = w * 0.08f

        // 1. Draw The 3D Glass Path
        val glassPath = Path().apply {
            moveTo(cx - bulbW/2, 0f)
            lineTo(cx + bulbW/2, 0f)
            cubicTo(cx + bulbW/2, bulbH * 0.6f, cx + neckW, bulbH, cx + neckW/2, cy)
            cubicTo(cx + neckW, h - bulbH, cx + bulbW/2, h - bulbH * 0.6f, cx + bulbW/2, h)
            lineTo(cx - bulbW/2, h)
            cubicTo(cx - bulbW/2, h - bulbH * 0.6f, cx - neckW, h - bulbH, cx - neckW/2, cy)
            cubicTo(cx - neckW, bulbH, cx - bulbW/2, bulbH * 0.6f, cx - bulbW/2, 0f)
            close()
        }

        // Glass Depth Background
        val glassBrush = Brush.horizontalGradient(
            colors = listOf(glassColorDark, glassColorLight, glassColorDark),
            startX = cx - bulbW/2,
            endX = cx + bulbW/2
        )
        drawPath(glassPath, brush = glassBrush)

        // 2. Volumetric Sand Drawing
        clipPath(glassPath) {
            
            // TOP SAND (Shrinking Cylinder)
            val topSandH = bulbH * smoothProgress
            val topSandY = bulbH - topSandH
            if (smoothProgress > 0.01f) {
                drawRect(
                    brush = Brush.horizontalGradient(listOf(sandColorDark, sandColorLight, sandColorDark)),
                    topLeft = Offset(0f, topSandY),
                    size = Size(w, topSandH + (cy - bulbH)) // Extends down to neck
                )
                // Simulated 3D Top Surface (Ellipse)
                val topSurfaceW = bulbW * (1f - (topSandY/bulbH).coerceIn(0f, 1f))
                drawOval(
                    color = sandColorLight,
                    topLeft = Offset(cx - topSurfaceW/2, topSandY - 6.dp.toPx()),
                    size = Size(topSurfaceW, 12.dp.toPx())
                )
            }

            // BOTTOM SAND (Growing Pyramid)
            val bottomSandH = bulbH * (1f - smoothProgress)
            val bottomSandY = h - bottomSandH
            if (smoothProgress < 0.99f) {
                val bottomSandPath = Path().apply {
                    moveTo(cx, h - bottomSandH - 15f) // Peak of the pyramid
                    lineTo(cx + bulbW/2, h)
                    lineTo(cx - bulbW/2, h)
                    close()
                }
                drawPath(
                    path = bottomSandPath,
                    brush = Brush.horizontalGradient(listOf(sandColorDark, sandColorLight, sandColorDark))
                )
            }

            // FALLING GRANULAR STREAM
            if (isActive && smoothProgress > 0f && smoothProgress < 1f) {
                drawLine(
                    color = sandColorLight,
                    start = Offset(cx, cy),
                    end = Offset(cx, bottomSandY),
                    strokeWidth = neckW * 0.5f,
                    pathEffect = PathEffect.dashPath(
                        intervals = floatArrayOf(15f, 10f), // Granular dots
                        phase = streamPhase * 25f          // Animation speed
                    )
                )
            }
        }

        // 3. Specular Highlight (The Glass Reflection)
        val highlightPath = Path().apply {
            moveTo(cx - bulbW/2 + 10f, 15f)
            cubicTo(cx - bulbW/2 + 10f, bulbH * 0.6f, cx - neckW + 5f, bulbH, cx - neckW/2 + 2f, cy)
        }
        drawPath(
            path = highlightPath,
            color = Color.White.copy(alpha = 0.7f),
            style = Stroke(width = 6f, cap = StrokeCap.Round)
        )

        // 4. Premium Dark Chrome Caps
        val capBrush = Brush.verticalGradient(
            colors = listOf(Color(0xFF424949), Color(0xFF1B2631))
        )
        val capCorner = CornerRadius(16f, 16f)
        drawRoundRect(
            brush = capBrush,
            topLeft = Offset(cx - bulbW/2 - 15f, -15f),
            size = Size(bulbW + 30f, 30f),
            cornerRadius = capCorner
        )
        drawRoundRect(
            brush = capBrush,
            topLeft = Offset(cx - bulbW/2 - 15f, h - 15f),
            size = Size(bulbW + 30f, 30f),
            cornerRadius = capCorner
        )
    }
}
