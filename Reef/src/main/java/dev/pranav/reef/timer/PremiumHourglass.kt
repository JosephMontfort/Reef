package dev.pranav.reef.timer

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive as coIsActive
import kotlin.math.*
import kotlin.random.Random

// ═══════════════════════════════════════════════════════════
//  PHYSICS CONSTANTS
// ═══════════════════════════════════════════════════════════
private const val GRAVITY         = 2600f   // px / s²
private const val DRAG_COEFF      = 0.020f  // velocity-proportional drag
private const val RESTITUTION     = 0.16f   // bounciness on hard surface
private const val FRICTION        = 0.58f   // tangential friction on impact
private const val REPOSE_TAN      = 0.6745f // tan(34°) – angle of repose
private const val MAX_PARTICLES   = 170
private const val SPAWN_MS        = 62L     // ms between particle births
private const val FLIP_INTERVAL   = 60_000L // 1 minute per cycle
private const val FLIP_ANIM_MS    = 720     // flip animation duration
private const val COMPRESS_THRESH = 45      // settled count before base raise

// ═══════════════════════════════════════════════════════════
//  COLOUR PALETTE
// ═══════════════════════════════════════════════════════════
private val COL_SHADOW  = Color(0xFF7A4E0E)
private val COL_BASE    = Color(0xFFBF8020)
private val COL_MID     = Color(0xFFDB9E2E)
private val COL_BRIGHT  = Color(0xFFF2BC42)
private val COL_SHINE   = Color(0xFFFBDC78)
private val COL_GLINT   = Color(0xFFFFF3BA)

// ═══════════════════════════════════════════════════════════
//  PARTICLE
// ═══════════════════════════════════════════════════════════
private class Particle(
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    val r: Float,
    var settled: Boolean = false,
    var alpha: Float = 0f,
    val tint: Float       = Random.nextFloat(),        // 0=dark 1=bright
    val specular: Float   = 0.3f + Random.nextFloat() * 0.7f,
    val elongation: Float = 0.9f + Random.nextFloat() * 0.2f
)

// ═══════════════════════════════════════════════════════════
//  HOURGLASS GEOMETRY HELPER
// ═══════════════════════════════════════════════════════════
private fun wallHalfW(
    y: Float, cy: Float, h: Float,
    bulbHW: Float, neckHW: Float
): Float {
    // Cubic bezier control points for right wall (half-widths from cx)
    val p0 = bulbHW; val p1 = bulbHW * 0.82f
    val p2 = neckHW * 2.8f; val p3 = neckHW
    return if (y <= cy) {
        val t = (y / cy).coerceIn(0f, 1f); val m = 1f - t
        m*m*m*p0 + 3*m*m*t*p1 + 3*m*t*t*p2 + t*t*t*p3
    } else {
        val t = ((y - cy) / cy).coerceIn(0f, 1f); val m = 1f - t
        m*m*m*p3 + 3*m*m*t*p2 + 3*m*t*t*p1 + t*t*t*p0
    }
}

private fun buildGlassPath(
    cx: Float, cy: Float, w: Float, h: Float,
    bHW: Float, bulbH: Float, nHW: Float
) = Path().apply {
    moveTo(cx - bHW, 0f)
    lineTo(cx + bHW, 0f)
    cubicTo(cx + bHW, bulbH * 0.65f, cx + nHW * 2.8f, bulbH, cx + nHW, cy)
    cubicTo(cx + nHW * 2.8f, h - bulbH, cx + bHW, h - bulbH * 0.65f, cx + bHW, h)
    lineTo(cx - bHW, h)
    cubicTo(cx - bHW, h - bulbH * 0.65f, cx - nHW * 2.8f, h - bulbH, cx - nHW, cy)
    cubicTo(cx - nHW * 2.8f, bulbH, cx - bHW, bulbH * 0.65f, cx - bHW, 0f)
    close()
}

// ═══════════════════════════════════════════════════════════
//  PHYSICS STEP
// ═══════════════════════════════════════════════════════════
private fun physicsStep(
    particles: MutableList<Particle>,
    w: Float, h: Float, dt: Float,
    sandBaseY: Float
) {
    val cx  = w / 2f; val cy = h / 2f
    val bHW = w * 0.38f; val nHW = w * 0.042f
    val floor = (sandBaseY - 0f).coerceIn(0f, h)

    val settled = particles.filter { it.settled }

    for (p in particles) {
        p.alpha = (p.alpha + dt * 7f).coerceAtMost(1f)
        if (p.settled) continue

        // Gravity
        p.vy += GRAVITY * dt

        // Drag (proportional to speed)
        val spd = sqrt(p.vx * p.vx + p.vy * p.vy)
        if (spd > 1f) {
            val d = DRAG_COEFF * spd * dt
            p.vx -= p.vx / spd * d
            p.vy -= p.vy / spd * d
        }

        // Integrate
        p.x += p.vx * dt
        p.y += p.vy * dt

        // ── Wall collision ─────────────────────────────────────────────
        val hw  = wallHalfW(p.y, cy, h, bHW, nHW)
        val rWall = cx + hw - p.r
        val lWall = cx - hw + p.r

        if (p.x > rWall) {
            p.x = rWall
            p.vx = -abs(p.vx) * RESTITUTION
            p.vy *= FRICTION
        } else if (p.x < lWall) {
            p.x = lWall
            p.vx = abs(p.vx) * RESTITUTION
            p.vy *= FRICTION
        }

        // ── Floor / base collision ─────────────────────────────────────
        val fY = floor - p.r
        if (p.y >= fY) {
            p.y = fY
            if (abs(p.vy) > 60f) {
                p.vy = -abs(p.vy) * RESTITUTION
                p.vx *= FRICTION
            } else {
                p.vy = 0f
                p.vx *= FRICTION
            }
        }

        // ── Particle-particle collision ─────────────────────────────────
        if (p.y > cy) { // only in bottom half
            for (s in settled) {
                val dx = p.x - s.x; val dy = p.y - s.y
                val minD = p.r + s.r
                val d2 = dx * dx + dy * dy
                if (d2 < minD * minD && d2 > 0.01f) {
                    val d = sqrt(d2)
                    val nx = dx / d; val ny = dy / d
                    val overlap = minD - d
                    p.x += nx * overlap
                    p.y += ny * overlap

                    val dot = p.vx * nx + p.vy * ny
                    if (dot < 0f) {
                        p.vx -= (1f + RESTITUTION) * dot * nx
                        p.vy -= (1f + RESTITUTION) * dot * ny
                        p.vx *= FRICTION
                        p.vy *= FRICTION
                    }
                }
            }
        }

        // ── Settling check ─────────────────────────────────────────────
        if (abs(p.vx) < 25f && abs(p.vy) < 25f && p.y > cy) {
            // Check angle of repose against neighbours
            var canSettle = true
            for (s in settled) {
                val dx = s.x - p.x; val dy = s.y - p.y
                val d = sqrt(dx * dx + dy * dy)
                if (d < (p.r + s.r) * 1.4f && dy > 0f) {
                    // slope = |dx| / dy  →  if > tan(repose_angle), roll
                    if (dy > 0.01f && abs(dx) / dy > REPOSE_TAN) {
                        val rollDir = if (dx > 0f) 1f else -1f
                        p.vx = rollDir * 90f
                        p.vy = 15f
                        canSettle = false
                        break
                    }
                }
            }
            if (canSettle) {
                p.settled = true
                p.vx = 0f; p.vy = 0f
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  DRAW HELPERS
// ═══════════════════════════════════════════════════════════

private fun DrawScope.drawParticle(p: Particle) {
    if (p.alpha < 0.02f) return

    // Motion blur elongation when moving fast
    val scaleY = if (!p.settled) 1f else p.elongation
    val speed = sqrt(p.vx * p.vx + p.vy * p.vy)
    val stretch = if (!p.settled && speed > 300f) (1f + speed / 1200f).coerceAtMost(2.2f) else 1f

    val col = lerp(lerp(COL_SHADOW, COL_MID, p.tint), COL_BRIGHT, p.tint * 0.6f)
    val hiCol = lerp(COL_BRIGHT, COL_GLINT, p.specular)

    // Body
    drawCircle(
        brush = Brush.radialGradient(
            0f to hiCol.copy(alpha = p.alpha * p.specular * 0.9f),
            0.35f to col.copy(alpha = p.alpha),
            1f to COL_SHADOW.copy(alpha = p.alpha * 0.9f),
            center = Offset(p.x - p.r * 0.28f, p.y - p.r * 0.3f),
            radius = p.r * 1.2f
        ),
        radius = p.r * stretch,
        center = Offset(p.x, p.y)
    )

    // Specular glint
    if (p.specular > 0.55f) {
        drawCircle(
            color = COL_GLINT.copy(alpha = p.alpha * (p.specular - 0.55f) * 1.8f),
            radius = p.r * 0.28f,
            center = Offset(p.x - p.r * 0.32f, p.y - p.r * 0.34f)
        )
    }
}

private fun DrawScope.drawTopSand(
    cx: Float, cy: Float, w: Float, h: Float,
    bHW: Float, bulbH: Float, nHW: Float,
    fill: Float   // 1 = full, 0 = empty
) {
    if (fill < 0.005f) return

    // The top sand fills from the neck upward.
    // fill=1 → top of sand is at y=0 (full); fill=0 → y=cy (empty, at neck level)
    val sandTopY = cy * (1f - fill)
    val sandBotY = cy   // sand always goes to neck

    if (sandTopY >= sandBotY) return

    // Build a path that clips to the hourglass left+right walls
    val sandPath = Path()
    val steps = 40
    // Right edge: scan top-to-bottom
    sandPath.moveTo(cx + wallHalfW(sandTopY, cy, h, bHW, nHW), sandTopY)
    for (i in 0..steps) {
        val y = sandTopY + (sandBotY - sandTopY) * i / steps
        sandPath.lineTo(cx + wallHalfW(y, cy, h, bHW, nHW), y)
    }
    // Left edge: scan bottom-to-top
    for (i in steps downTo 0) {
        val y = sandTopY + (sandBotY - sandTopY) * i / steps
        sandPath.lineTo(cx - wallHalfW(y, cy, h, bHW, nHW), y)
    }
    sandPath.close()

    drawPath(
        sandPath,
        brush = Brush.verticalGradient(
            colors = listOf(COL_BRIGHT, COL_MID, COL_BASE),
            startY = sandTopY,
            endY = sandBotY
        )
    )

    // Wavy surface highlight on top sand
    val surfW = wallHalfW(sandTopY, cy, h, bHW, nHW) * 2f
    drawOval(
        brush = Brush.horizontalGradient(
            colors = listOf(
                COL_SHINE.copy(alpha = 0f),
                COL_SHINE.copy(alpha = 0.55f),
                COL_SHINE.copy(alpha = 0f)
            ),
            startX = cx - surfW / 2f,
            endX = cx + surfW / 2f
        ),
        topLeft = Offset(cx - surfW / 2f, sandTopY - 5f),
        size = Size(surfW, 10f)
    )

    // Rim of individual surface grains
    val grainCount = 8
    for (i in 0 until grainCount) {
        val fx = (i.toFloat() / grainCount) - 0.5f
        val xPos = cx + fx * surfW * 0.85f
        val yPos = sandTopY + sin(fx * PI.toFloat() * 2f) * 2.5f
        val gr = 2.5f + Random.nextFloat() * 1.5f
        drawCircle(
            color = lerp(COL_BASE, COL_BRIGHT, 0.4f + Random.nextFloat() * 0.4f),
            radius = gr,
            center = Offset(xPos, yPos)
        )
    }
}

private fun DrawScope.drawSandBase(
    cx: Float, cy: Float, w: Float, h: Float,
    bHW: Float, nHW: Float, baseY: Float
) {
    if (baseY >= h) return
    val path = Path()
    val steps = 40
    path.moveTo(cx + wallHalfW(baseY, cy, h, bHW, nHW), baseY)
    for (i in 0..steps) {
        val y = baseY + (h - baseY) * i / steps
        path.lineTo(cx + wallHalfW(y, cy, h, bHW, nHW), y)
    }
    for (i in steps downTo 0) {
        val y = baseY + (h - baseY) * i / steps
        path.lineTo(cx - wallHalfW(y, cy, h, bHW, nHW), y)
    }
    path.close()
    drawPath(
        path,
        brush = Brush.verticalGradient(
            colors = listOf(COL_MID, COL_BASE, COL_SHADOW),
            startY = baseY, endY = h
        )
    )
    // Surface highlight on the pile top
    val sw = wallHalfW(baseY, cy, h, bHW, nHW) * 2f
    drawOval(
        brush = Brush.horizontalGradient(
            listOf(Color.Transparent, COL_SHINE.copy(alpha = 0.4f), Color.Transparent),
            startX = cx - sw / 2f, endX = cx + sw / 2f
        ),
        topLeft = Offset(cx - sw / 2f, baseY - 4f),
        size = Size(sw, 8f)
    )
}

private fun DrawScope.drawStream(
    cx: Float, cy: Float, nHW: Float,
    bottomY: Float, streamPhase: Float
) {
    if (bottomY <= cy + 4f) return
    val streamLen = (bottomY - cy).coerceAtLeast(0f)
    val steps = 24
    val baseW = nHW * 0.45f

    // Draw stream as series of overlapping ovals that taper and wobble
    for (i in 0..steps) {
        val t = i.toFloat() / steps
        val y = cy + streamLen * t
        // Slight lateral wobble that travels downward
        val phase = (streamPhase - t * 0.4f) * 2f * PI.toFloat()
        val wobble = sin(phase) * nHW * 0.10f
        // Width tapers slightly, then spreads as it lands
        val w = baseW * (1f + t * 0.3f + if (t > 0.85f) (t - 0.85f) * 3f else 0f)
        val alpha = (0.85f - t * 0.15f) * (1f - abs(sin(phase)) * 0.1f)

        val blend = t
        val col = lerp(COL_SHINE, COL_BRIGHT, blend)
        drawCircle(
            color = col.copy(alpha = alpha * 0.9f),
            radius = w,
            center = Offset(cx + wobble, y)
        )
    }

    // Bright core
    drawLine(
        brush = Brush.verticalGradient(
            colors = listOf(
                COL_GLINT.copy(alpha = 0.9f),
                COL_BRIGHT.copy(alpha = 0.6f),
                COL_MID.copy(alpha = 0.3f)
            ),
            startY = cy, endY = cy + streamLen
        ),
        start = Offset(cx, cy),
        end = Offset(cx, cy + streamLen),
        strokeWidth = baseW * 0.5f,
        cap = StrokeCap.Round
    )
}

private fun DrawScope.drawGlassOverlay(
    glassPath: Path, cx: Float, cy: Float, w: Float, h: Float,
    bHW: Float, bulbH: Float, nHW: Float, shimmer: Float
) {
    // Glass stroke (edge)
    drawPath(
        glassPath,
        color = Color.White.copy(alpha = 0.30f),
        style = Stroke(width = 2.5f)
    )

    // Left-side reflection streak in top bulb
    val refPath = Path().apply {
        val lx = cx - bHW * 0.72f
        moveTo(lx + 8f, 14f)
        cubicTo(lx + 6f, bulbH * 0.5f, lx + nHW + 4f, bulbH * 0.85f, cx - nHW * 1.5f, cy - 10f)
    }
    drawPath(
        refPath,
        color = Color.White.copy(alpha = 0.55f),
        style = Stroke(width = 5f, cap = StrokeCap.Round)
    )
    // Thinner inner reflection
    val refPath2 = Path().apply {
        val lx = cx - bHW * 0.55f
        moveTo(lx + 4f, 18f)
        cubicTo(lx + 2f, bulbH * 0.45f, cx - nHW * 2f, bulbH * 0.9f, cx - nHW * 1.2f, cy - 6f)
    }
    drawPath(
        refPath2,
        color = Color.White.copy(alpha = 0.28f),
        style = Stroke(width = 2.5f, cap = StrokeCap.Round)
    )

    // Bottom bulb reflection
    val refBot = Path().apply {
        val lx = cx - bHW * 0.68f
        moveTo(cx - nHW * 1.4f, cy + 12f)
        cubicTo(lx + 6f, h - bulbH * 0.85f, lx + 5f, h - bulbH * 0.5f, lx + 10f, h - 14f)
    }
    drawPath(
        refBot,
        color = Color.White.copy(alpha = 0.38f),
        style = Stroke(width = 4f, cap = StrokeCap.Round)
    )

    // Subtle animated shimmer
    val shimY = h * (shimmer % 1f)
    drawCircle(
        color = Color.White.copy(alpha = 0.06f),
        radius = bHW * 0.6f,
        center = Offset(cx - bHW * 0.3f, shimY)
    )
}

private fun DrawScope.drawCaps(
    cx: Float, w: Float, h: Float, bHW: Float
) {
    val capH = 24f; val capW = bHW * 2f + 28f; val rounding = CornerRadius(10f, 10f)
    val capBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFF4A5568), Color(0xFF1A202C))
    )
    // Top cap
    drawRoundRect(
        brush = capBrush,
        topLeft = Offset(cx - capW / 2f, -capH / 2f),
        size = Size(capW, capH),
        cornerRadius = rounding
    )
    // Top cap shine
    drawRoundRect(
        color = Color.White.copy(alpha = 0.18f),
        topLeft = Offset(cx - capW / 2f, -capH / 2f),
        size = Size(capW, capH / 2f),
        cornerRadius = rounding
    )
    // Bottom cap
    drawRoundRect(
        brush = capBrush,
        topLeft = Offset(cx - capW / 2f, h - capH / 2f),
        size = Size(capW, capH),
        cornerRadius = rounding
    )
}

// ═══════════════════════════════════════════════════════════
//  MAIN COMPOSABLE
// ═══════════════════════════════════════════════════════════

@Composable
fun PremiumHourglass(
    progress: Float,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density

    // ── Flip ─────────────────────────────────────────────────────────────
    var flipCount by remember { mutableIntStateOf(0) }
    var isFlipping by remember { mutableStateOf(false) }
    val flipAngle by animateFloatAsState(
        targetValue = flipCount * 180f,
        animationSpec = tween(FLIP_ANIM_MS, easing = FastOutSlowInEasing),
        finishedListener = { isFlipping = false },
        label = "flip"
    )

    // ── Simulation state ─────────────────────────────────────────────────
    var redrawTick  by remember { mutableIntStateOf(0) }
    val particles    = remember { mutableListOf<Particle>() }
    var cycleProgress by remember { mutableFloatStateOf(1f) }
    var sandBaseY    by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var canvasW      by remember { mutableFloatStateOf(0f) }
    var canvasH      by remember { mutableFloatStateOf(0f) }

    // ── Stream shimmer ───────────────────────────────────────────────────
    val inf = rememberInfiniteTransition(label = "inf")
    val streamPhase by inf.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(480, easing = LinearEasing)),
        "sp"
    )
    val shimmer by inf.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2200, easing = LinearEasing)),
        "sh"
    )

    // ── Game loop ────────────────────────────────────────────────────────
    LaunchedEffect(isActive) {
        var cycleStart  = System.currentTimeMillis()
        var lastFrame   = cycleStart
        var lastSpawn   = cycleStart

        while (coIsActive) {
            val now  = System.currentTimeMillis()
            val dtMs = (now - lastFrame).coerceIn(1L, 48L)
            val dt   = dtMs / 1000f
            lastFrame = now

            if (canvasW > 0f && canvasH > 0f && !isFlipping) {
                val elapsed = now - cycleStart
                cycleProgress = (1f - elapsed.toFloat() / FLIP_INTERVAL).coerceIn(0f, 1f)

                // Spawn
                if (isActive && cycleProgress > 0.01f && now - lastSpawn > SPAWN_MS
                    && particles.size < MAX_PARTICLES
                ) {
                    val cx = canvasW / 2f; val cy = canvasH / 2f
                    val nHW = canvasW * 0.042f
                    particles.add(
                        Particle(
                            x  = cx + (Random.nextFloat() - 0.5f) * nHW * 0.55f,
                            y  = cy + 2f,
                            vx = (Random.nextFloat() - 0.5f) * 90f,
                            vy = 110f + Random.nextFloat() * 70f,
                            r  = (canvasW * 0.026f).coerceIn(3f, 6f)
                        )
                    )
                    lastSpawn = now
                }

                // Physics
                val baseY = sandBaseY.takeIf { it < Float.MAX_VALUE } ?: canvasH
                physicsStep(particles, canvasW, canvasH, dt, baseY)

                // Compress settled into base
                val settledList = particles.filter { it.settled }
                if (settledList.size > COMPRESS_THRESH) {
                    val deepest = settledList.sortedByDescending { it.y }.take(COMPRESS_THRESH / 2)
                    val newBase = deepest.minOf { it.y } - 2f
                    if (sandBaseY == Float.MAX_VALUE || newBase < sandBaseY) sandBaseY = newBase
                    deepest.forEach { particles.remove(it) }
                }

                // Trigger flip
                if (elapsed >= FLIP_INTERVAL && progress > 0.02f) {
                    flipCount++
                    isFlipping = true
                    cycleStart = now
                }
            }
            redrawTick++
            delay(16L)
        }
    }

    // Reset after flip animation
    LaunchedEffect(flipCount) {
        if (flipCount > 0) {
            delay(FLIP_ANIM_MS.toLong() + 80L)
            particles.clear()
            cycleProgress = 1f
            sandBaseY = canvasH.takeIf { it > 0f } ?: Float.MAX_VALUE
        }
    }

    // ── Render ───────────────────────────────────────────────────────────
    Box(
        modifier = modifier.size(190.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp)
                .onSizeChanged {
                    canvasW = it.width.toFloat()
                    canvasH = it.height.toFloat()
                    if (sandBaseY == Float.MAX_VALUE) sandBaseY = canvasH
                }
                .graphicsLayer {
                    rotationX = flipAngle
                    cameraDistance = 11f * density
                }
        ) {
            @Suppress("UNUSED_EXPRESSION")
            redrawTick // read to register recomposition dependency

            val w   = size.width; val h   = size.height
            val cx  = w / 2f;    val cy  = h / 2f
            val bHW = w * 0.38f
            val bulbH = h * 0.44f
            val nHW = w * 0.042f

            val glassPath = buildGlassPath(cx, cy, w, h, bHW, bulbH, nHW)

            // Glass background tint
            drawPath(
                glassPath,
                brush = Brush.horizontalGradient(
                    listOf(Color(0x06FFFFFF), Color(0x1EFFFFFF), Color(0x06FFFFFF)),
                    startX = cx - bHW, endX = cx + bHW
                )
            )

            clipPath(glassPath) {
                // Top sand (bulk fill, not particles)
                drawTopSand(cx, cy, w, h, bHW, bulbH, nHW, cycleProgress)

                // Sand stream
                if (!isFlipping && cycleProgress > 0.01f && isActive) {
                    val pileTop = particles.filter { it.settled }
                        .minOfOrNull { it.y }
                        ?.minus(2f)
                        ?: sandBaseY.coerceAtMost(h)
                    drawStream(cx, cy, nHW, pileTop.coerceAtMost(h), streamPhase)
                }

                // Compressed sand base
                val bY = sandBaseY
                if (bY < h) drawSandBase(cx, cy, w, h, bHW, nHW, bY)

                // Live particles
                for (p in particles.toList()) drawParticle(p)
            }

            // Glass overlays
            drawGlassOverlay(glassPath, cx, cy, w, h, bHW, bulbH, nHW, shimmer)

            // Caps
            drawCaps(cx, w, h, bHW)
        }
    }
}

// Lerp helper for colors
private fun lerp(a: Color, b: Color, t: Float): Color = Color(
    red   = (a.red   + (b.red   - a.red)   * t).coerceIn(0f, 1f),
    green = (a.green + (b.green - a.green) * t).coerceIn(0f, 1f),
    blue  = (a.blue  + (b.blue  - a.blue)  * t).coerceIn(0f, 1f),
    alpha = (a.alpha + (b.alpha - a.alpha) * t).coerceIn(0f, 1f)
)

private fun CornerRadius(x: Float, y: Float) = androidx.compose.ui.geometry.CornerRadius(x, y)
