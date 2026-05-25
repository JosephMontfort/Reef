package dev.pranav.reef.timer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.*
import kotlin.random.Random

// ══════════════════════════════════════════════════════════════════════════════
//  Physics coordinate space — 400×400 internal units, fully decoupled from
//  screen pixels. All particle positions live here; scaled to screen at draw time.
// ══════════════════════════════════════════════════════════════════════════════
private const val PW       = 400f
private const val PH       = 400f
private const val PCX      = PW / 2f   // 200 — horizontal centre
private const val PCY      = PH / 2f   // 200 — vertical centre / neck y

private const val CAP_H    = 26f       // metal cap thickness (top & bottom)
private const val BULB_HW  = 147f      // glass half-width at widest rim
private const val NECK_HW  = 12.5f     // glass half-width at the neck

// ══════════════════════════════════════════════════════════════════════════════
//  Flat particle array layout — STRIDE floats per grain, packed in one FloatArray.
//  Zero heap allocation in the physics loop; no GC pressure at 60 fps.
// ══════════════════════════════════════════════════════════════════════════════
private const val STRIDE    = 8
private const val P_X       = 0   // position x  (physics units)
private const val P_Y       = 1   // position y
private const val P_VX      = 2   // velocity x  (physics units / s)
private const val P_VY      = 3   // velocity y
private const val P_STATE   = 4   // see S_* constants below
private const val P_HUE     = 5   // 0f..1f → palette colour
private const val P_RADIUS  = 6   // grain radius (physics units)
private const val P_UNUSED  = 7   // reserved

private const val S_FALLING  = 0f
private const val S_SETTLED  = 1f
private const val S_INACTIVE = 2f

// ══════════════════════════════════════════════════════════════════════════════
//  Simulation constants
// ══════════════════════════════════════════════════════════════════════════════
private const val MAX_P          = 160    // total particle slots
private const val GRID_C         = 34     // CA grid columns
private const val GRID_R         = 34     // CA grid rows

private const val GRAV_PPS       = 2700f  // gravity (physics-units / s²)
private const val DRAG_PER_S     = 0.9984f // exponential air-drag coefficient per second
private const val RESTITUTE_WALL = 0.16f  // wall bounce
private const val RESTITUTE_FLOOR= 0.05f  // floor bounce
private const val FRICTION_SLIDE = 0.80f  // tangential friction on surface impact
private const val SPAWN_PER_SEC  = 4.5f   // grains emitted per second from neck

private const val FLIP_CYCLE_MS  = 60_000L  // 1 minute
private const val FLIP_STIFFNESS = 80f       // spring stiffness for flip animation
private const val FLIP_DAMPING   = 0.74f     // spring damping (slight satisfying overshoot)

// ══════════════════════════════════════════════════════════════════════════════
//  Colour palette — six warm-gold ARGB values interpolated at runtime.
//  Stored as raw ints; Color objects are only created when actually painting.
// ══════════════════════════════════════════════════════════════════════════════
private val PALETTE = intArrayOf(
    0xFFB07020.toInt(),   // 0 dark amber / burnt
    0xFFCA8E2C.toInt(),   // 1 deep gold
    0xFFDCA03A.toInt(),   // 2 warm gold
    0xFFECBB46.toInt(),   // 3 golden mid
    0xFFF5CC58.toInt(),   // 4 light gold
    0xFFFCE088.toInt()    // 5 pale highlight
)

private fun sandColor(hue: Float, alpha: Float = 1f): Color {
    val t  = (hue * (PALETTE.size - 1)).coerceIn(0f, PALETTE.size - 1f)
    val lo = t.toInt().coerceAtMost(PALETTE.size - 2)
    val f  = t - lo
    val ca = Color(PALETTE[lo])
    val cb = Color(PALETTE[lo + 1])
    return Color(
        red   = ca.red   + (cb.red   - ca.red)   * f,
        green = ca.green + (cb.green - ca.green) * f,
        blue  = ca.blue  + (cb.blue  - ca.blue)  * f,
        alpha = alpha
    )
}

// ══════════════════════════════════════════════════════════════════════════════
//  Hourglass geometry — single algebraic half-width function.
//  Avoids path-clipping for boundary checks: just compare |px - PCX| ≤ glassHW(py).
//  Uses smootherstep (6t⁵-15t⁴+10t³) for a natural glass-blowing curve.
//  Same formula works for BOTH top and bottom halves (symmetric about PCY).
// ══════════════════════════════════════════════════════════════════════════════
private fun ss5(t: Float): Float = t * t * t * (t * (6f * t - 15f) + 10f)

private fun glassHW(py: Float): Float {
    val t = when {
        py <= PCY -> ((py - CAP_H)       / (PCY - CAP_H)).coerceIn(0f, 1f)
        else      -> ((PH - CAP_H - py)  / (PH - CAP_H - PCY)).coerceIn(0f, 1f)
    }
    // t=0 at rim (widest = BULB_HW), t=1 at neck (narrowest = NECK_HW)
    return NECK_HW + (BULB_HW - NECK_HW) * (1f - ss5(t))
}

// ══════════════════════════════════════════════════════════════════════════════
//  Cellular-Automata heap grid
//
//  row = 0        → gravity floor  (meaning flips with orientation)
//  row = GRID_R-1 → neck
//
//  gDown = true  → grid maps to physics bottom half y ∈ [PCY, PH-CAP_H]
//  gDown = false → grid maps to physics top    half y ∈ [CAP_H, PCY]
// ══════════════════════════════════════════════════════════════════════════════
private fun pxToRow(py: Float, gDown: Boolean): Int {
    val t = if (gDown) (PH - CAP_H - py) / (PH - CAP_H - PCY)
             else      (py - CAP_H)       / (PCY - CAP_H)
    return (t * GRID_R).toInt().coerceIn(0, GRID_R - 1)
}

private fun pxToCol(px: Float): Int =
    ((px - PCX + BULB_HW) / (2f * BULB_HW) * GRID_C).toInt().coerceIn(0, GRID_C - 1)

private fun rowToPhysY(row: Int, gDown: Boolean): Float {
    val frac = (row + 0.5f) / GRID_R
    return if (gDown) (PH - CAP_H) - frac * (PH - CAP_H - PCY)
           else        CAP_H       + frac * (PCY - CAP_H)
}

private fun colToPhysX(col: Int): Float =
    PCX - BULB_HW + (col + 0.5f) / GRID_C * (2f * BULB_HW)

@Suppress("NOTHING_TO_INLINE")
private inline fun gIdx(c: Int, r: Int): Int = r * GRID_C + c

/**
 * Try to settle particle [idx] at grid cell (col, row) or a neighbour cell.
 * CA stability rule: a cell is stable only if row==0 (floor) or the cell directly
 * below it is already occupied — this naturally produces an angle of repose.
 */
private fun trySettle(
    par: FloatArray, idx: Int,
    col: Int, row: Int,
    grid: BooleanArray
): Boolean {
    val b = idx * STRIDE
    fun at(c: Int, r: Int): Boolean {
        if (c < 0 || c >= GRID_C || r < 0 || r >= GRID_R) return false
        if (grid[gIdx(c, r)]) return false
        if (r > 0 && !grid[gIdx(c, r - 1)]) return false   // would float
        grid[gIdx(c, r)] = true
        par[b + P_STATE] = S_SETTLED
        return true
    }
    // Exact position first, then left/right slide (natural angle-of-repose)
    return at(col, row) || at(col - 1, row) || at(col + 1, row)
}

// ══════════════════════════════════════════════════════════════════════════════
//  PremiumHourglass composable
// ══════════════════════════════════════════════════════════════════════════════
@Composable
fun PremiumHourglass(
    progress: Float,    // 1f = session full, 0f = session finished
    isActive: Boolean,  // false when paused
    modifier: Modifier = Modifier
) {
    // ── Pre-allocated physics buffers — NEVER reallocated after first composition ──
    val par  = remember {
        FloatArray(MAX_P * STRIDE).also { a ->
            for (i in 0 until MAX_P) a[i * STRIDE + P_STATE] = S_INACTIVE
        }
    }
    val grid = remember { BooleanArray(GRID_C * GRID_R) }
    val rng  = remember { Random(System.currentTimeMillis()) }

    // Pre-allocated draw-point buffers — cleared & refilled every frame.
    // Three hue buckets replace up to MAX_P individual drawCircle GPU calls.
    val pts0 = remember { ArrayList<Offset>(MAX_P) }           // dark grains
    val pts1 = remember { ArrayList<Offset>(MAX_P) }           // mid grains
    val pts2 = remember { ArrayList<Offset>(MAX_P) }           // light grains
    val ptsA = remember { ArrayList<Offset>(GRID_C * GRID_R) } // settled pile A (dark)
    val ptsB = remember { ArrayList<Offset>(GRID_C * GRID_R) } // settled pile B (mid)

    // Pre-allocated Path — reset + rebuilt each frame, no heap allocation.
    val glassPath = remember { Path() }

    // ── Compose-observable state ──────────────────────────────────────────────
    var tick        by remember { mutableIntStateOf(0) }
    var flipCount   by remember { mutableIntStateOf(0) }
    var topProgress by remember { mutableFloatStateOf(1f) }

    // Cumulative flip degrees: 0 → 180 → 360 → …
    // Spring animation gives the hourglass a satisfying settle at each position.
    val flipDeg = remember { Animatable(0f) }

    // ── Pause-aware elapsed time
    // activeMs = total active (non-paused) milliseconds elapsed.
    // resumeAt = wall-clock ms when isActive most recently became true.
    // Using two longs is enough for correct pause/resume without external params.
    var activeMs by remember { mutableLongStateOf(0L) }
    var resumeAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isActive) {
        if (isActive) {
            resumeAt = System.currentTimeMillis()
        } else {
            if (resumeAt > 0L) activeMs += System.currentTimeMillis() - resumeAt
        }
    }

    // ── Flip timer + topProgress (advances only while active) ─────────────────
    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        while (true) {
            val now   = System.currentTimeMillis()
            val total = activeMs + (now - resumeAt)
            topProgress = 1f - (total % FLIP_CYCLE_MS).toFloat() / FLIP_CYCLE_MS

            val flips = (total / FLIP_CYCLE_MS).toInt()
            if (flips > flipCount) {
                flipCount = flips
                // Unsettle all grains and clear the grid.
                // The physics loop will naturally reform the pile in the new orientation
                // as gravity rotates through the flip animation.
                grid.fill(false)
                for (i in 0 until MAX_P) {
                    val b = i * STRIDE
                    if (par[b + P_STATE] == S_SETTLED) {
                        par[b + P_STATE] = S_FALLING
                        par[b + P_VX]    = (rng.nextFloat() - 0.5f) * 45f
                        par[b + P_VY]    = 0f
                    }
                }
            }
            delay(80L)
        }
    }

    // ── Flip spring animation ─────────────────────────────────────────────────
    // Target = flipCount * 180°.  Spring gives a slight satisfying overshoot
    // (like a real hourglass settling into its stand).
    //
    // Crucially, the physics loop reads flipDeg.value LIVE each frame, so the
    // gravity vector rotates with the visual: sand slides sideways at 90°,
    // reverses at 180°.  This is physically correct behaviour.
    LaunchedEffect(flipCount) {
        flipDeg.animateTo(
            targetValue   = flipCount * 180f,
            animationSpec = spring(dampingRatio = FLIP_DAMPING, stiffness = FLIP_STIFFNESS)
        )
    }

    // ── Physics loop — driven by a tight delay loop on the Main dispatcher ────
    // Using delay(16) ≈ 60 fps cadence.  System.nanoTime() gives precise dt.
    // The withFrameNanos alternative would require importing Compose internals;
    // delay(16) is simpler and accurate enough for sand grain physics.
    LaunchedEffect(isActive) {
        if (!isActive) return@LaunchedEffect
        var lastNs   = System.nanoTime()
        var spawnAcc = 0f

        while (true) {
            delay(16L)
            val nowNs = System.nanoTime()
            val dt    = ((nowNs - lastNs) / 1_000_000_000f).coerceIn(0f, 0.033f)
            lastNs    = nowNs
            if (dt <= 0f) continue

            // ── Derive gravity from the live animated flip angle ───────────
            // deg=0°  → gravY=+GRAV (normal down)
            // deg=90° → gravX=+GRAV (sideways during spin)
            // deg=180°→ gravY=-GRAV (fully inverted)
            val rad   = (flipDeg.value * PI / 180.0).toFloat()
            val gravX = GRAV_PPS * sin(rad)
            val gravY = GRAV_PPS * cos(rad)
            val gDown = gravY >= 0f

            // ── Update each falling particle ──────────────────────────────
            for (i in 0 until MAX_P) {
                val b = i * STRIDE
                if (par[b + P_STATE] != S_FALLING) continue

                var px = par[b + P_X];  var py = par[b + P_Y]
                var vx = par[b + P_VX]; var vy = par[b + P_VY]
                val r  = par[b + P_RADIUS]

                // Gravity
                vx += gravX * dt
                vy += gravY * dt

                // Air drag: v(t) = v0 · drag^(t_frames).
                // pow via Math to avoid ambiguity with Kotlin stdlib overloads.
                val drag = Math.pow(DRAG_PER_S.toDouble(), (dt * 60.0)).toFloat()
                vx *= drag; vy *= drag

                // Terminal velocity cap
                val spd = sqrt(vx * vx + vy * vy)
                if (spd > 1100f) { val inv = 1100f / spd; vx *= inv; vy *= inv }

                // Integrate position
                var nx = px + vx * dt
                var ny = py + vy * dt

                // ── Wall collision (algebraic O(1) boundary check) ─────────
                val clampY = ny.coerceIn(CAP_H, PH - CAP_H)
                val limit  = (glassHW(clampY) - r).coerceAtLeast(0f)
                val relX   = nx - PCX
                if (abs(relX) > limit) {
                    val sgn = if (relX > 0f) 1f else -1f
                    nx  = PCX + sgn * limit
                    vx  = -vx * RESTITUTE_WALL
                    vy *= FRICTION_SLIDE
                }

                // ── Floor / ceiling bounce ────────────────────────────────
                if (gDown) {
                    if (ny > PH - CAP_H - r) {
                        ny = PH - CAP_H - r
                        vy = -abs(vy) * RESTITUTE_FLOOR
                        vx *= FRICTION_SLIDE
                    }
                } else {
                    if (ny < CAP_H + r) {
                        ny = CAP_H + r
                        vy = abs(vy) * RESTITUTE_FLOOR
                        vx *= FRICTION_SLIDE
                    }
                }

                // Write back (no new object allocations)
                par[b + P_X] = nx; par[b + P_VX] = vx
                par[b + P_Y] = ny; par[b + P_VY] = vy

                // ── Settlement check ───────────────────────────────────────
                // Only attempt settlement in the gravity-floor half.
                val inActiveHalf = (gDown && ny >= PCY) || (!gDown && ny <= PCY)
                if (!inActiveHalf) continue

                val col       = pxToCol(nx)
                val row       = pxToRow(ny, gDown)
                val pileBelow = row == 0 || (row > 0 && grid[gIdx(col, row - 1)])
                if (pileBelow) trySettle(par, i, col, row, grid)
            }

            // ── Spawn new grains from the neck ────────────────────────────
            spawnAcc += SPAWN_PER_SEC * dt
            while (spawnAcc >= 1f) {
                spawnAcc -= 1f
                if (topProgress <= 0.02f || progress <= 0.01f) break

                // Find an inactive slot — plain indexed loop avoids Range allocation
                var slot = -1
                for (i in 0 until MAX_P) {
                    if (par[i * STRIDE + P_STATE] == S_INACTIVE) { slot = i; break }
                }
                if (slot < 0) break

                val b  = slot * STRIDE
                val jx = (rng.nextFloat() - 0.5f) * NECK_HW * 0.85f
                par[b + P_X]      = PCX + jx
                par[b + P_Y]      = PCY + (if (gDown) -NECK_HW * 0.5f else NECK_HW * 0.5f)
                par[b + P_VX]     = jx * 4f + (rng.nextFloat() - 0.5f) * 55f
                par[b + P_VY]     = (if (gDown) 95f else -95f) + (rng.nextFloat() - 0.5f) * 45f
                par[b + P_STATE]  = S_FALLING
                par[b + P_HUE]    = rng.nextFloat()
                par[b + P_RADIUS] = 5.8f + rng.nextFloat() * 4.4f  // 5.8 – 10.2 phys units
                par[b + P_UNUSED] = 0f
            }

            tick++ // Triggers Canvas recompose
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────────
    // Reading `tick` here creates a Compose recompose dependency so the Canvas
    // redraws every physics frame.
    @Suppress("UNUSED_EXPRESSION") tick

    val currentDeg = flipDeg.value
    val currentTP  = topProgress

    Canvas(
        modifier = modifier
            .size(180.dp)
            .padding(8.dp)
    ) {
        val scX = size.width  / PW
        val scY = size.height / PH
        val scR = (scX + scY) * 0.5f  // isotropic radius/stroke scale

        // Rotate the entire canvas output around its centre.
        // The physics coordinate system is NOT transformed here — only the
        // rendered pixels rotate.  Since gravity is derived from the same
        // flipDeg.value, particle trajectories and the visual always agree.
        rotate(degrees = currentDeg, pivot = center) {
            val rad   = (currentDeg * PI / 180.0).toFloat()
            val gDown = cos(rad) >= 0f

            // Fill alpha: smoothly fade the bulk sand fill during the rotation
            // so it doesn't abruptly snap when gDown switches (at ±90°).
            val fillAlpha = abs(cos(rad.toDouble())).toFloat().coerceIn(0f, 1f)

            drawHourglassScene(
                scX        = scX,
                scY        = scY,
                scR        = scR,
                gDown      = gDown,
                fillAlpha  = fillAlpha,
                topProg    = currentTP,
                sessProg   = progress,
                par        = par,
                grid       = grid,
                glassPath  = glassPath,
                pts0       = pts0,
                pts1       = pts1,
                pts2       = pts2,
                ptsA       = ptsA,
                ptsB       = ptsB
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
//  DrawScope extension — all canvas rendering in one place
// ══════════════════════════════════════════════════════════════════════════════
private fun DrawScope.drawHourglassScene(
    scX:       Float,
    scY:       Float,
    scR:       Float,
    gDown:     Boolean,
    fillAlpha: Float,
    topProg:   Float,
    sessProg:  Float,
    par:       FloatArray,
    grid:      BooleanArray,
    glassPath: Path,                  // pre-allocated, reset each call
    pts0:      ArrayList<Offset>,
    pts1:      ArrayList<Offset>,
    pts2:      ArrayList<Offset>,
    ptsA:      ArrayList<Offset>,
    ptsB:      ArrayList<Offset>
) {
    // ── 1. Rebuild glass path (reuses the pre-allocated Path object) ──────────
    glassPath.reset()
    val pathSteps = 28
    // Start top-left, sweep right along top rim
    glassPath.moveTo((PCX - BULB_HW) * scX, CAP_H * scY)
    glassPath.lineTo((PCX + BULB_HW) * scX, CAP_H * scY)
    // Right wall: top → bottom
    for (s in 0..pathSteps) {
        val py = CAP_H + s.toFloat() / pathSteps * (PH - 2f * CAP_H)
        glassPath.lineTo((PCX + glassHW(py)) * scX, py * scY)
    }
    // Bottom rim right → left
    glassPath.lineTo((PCX - BULB_HW) * scX, (PH - CAP_H) * scY)
    // Left wall: bottom → top
    for (s in pathSteps downTo 0) {
        val py = CAP_H + s.toFloat() / pathSteps * (PH - 2f * CAP_H)
        glassPath.lineTo((PCX - glassHW(py)) * scX, py * scY)
    }
    glassPath.close()

    // ── 2. Glass body (faint translucent fill gives the glass material depth) ──
    drawPath(
        glassPath,
        brush = Brush.horizontalGradient(
            0.00f to Color(0x1CFFFFFF),
            0.15f to Color(0x26FFFFFF),
            0.50f to Color(0x0AFFFFFF),
            0.85f to Color(0x22FFFFFF),
            1.00f to Color(0x18FFFFFF),
            startX = 0f, endX = size.width
        )
    )

    // ── 3. All sand content clipped to the glass interior ─────────────────────
    clipPath(glassPath) {

        // ── 3a. Bulk sand fill for the "full" side ───────────────────────────
        // fillAlpha fades during rotation so there is no abrupt jump when the
        // gravity direction crosses 90° and gDown switches.
        if (topProg > 0.005f && fillAlpha > 0.04f) {
            val (fillTop, fillBot) = if (gDown) {
                // Normal orientation: fill grows downward from CAP_H
                Pair(
                    CAP_H * scY,
                    (CAP_H + (PCY - CAP_H) * topProg) * scY
                )
            } else {
                // Inverted: fill grows upward from PH-CAP_H
                Pair(
                    (PH - CAP_H - (PH - CAP_H - PCY) * topProg) * scY,
                    (PH - CAP_H) * scY
                )
            }
            if (fillBot > fillTop) {
                // Gradient: lighter at top (diffuse light), darker at base
                drawRect(
                    brush = Brush.verticalGradient(
                        colors  = listOf(
                            sandColor(0.28f, fillAlpha),
                            sandColor(0.42f, fillAlpha),
                            sandColor(0.55f, fillAlpha * 0.88f)
                        ),
                        startY = fillTop,
                        endY   = fillBot
                    ),
                    topLeft = Offset(0f, fillTop),
                    size    = Size(size.width, (fillBot - fillTop).coerceAtLeast(0f))
                )

                // Sand surface shimmer (slightly convex highlight)
                val surfY  = if (gDown) fillBot else fillTop
                val physY  = (surfY / scY).coerceIn(CAP_H + 1f, PH - CAP_H - 1f)
                val surfW  = glassHW(physY) * scX * 1.85f
                drawOval(
                    color   = sandColor(0.68f, 0.50f * fillAlpha),
                    topLeft = Offset(size.width / 2f - surfW / 2f, surfY - 5f * scR),
                    size    = Size(surfW, 10f * scR)
                )
            }
        }

        // ── 3b. Settled grain pile (CA grid → drawPoints, 2 colour passes) ───
        // Two interleaved passes give the pile a subtle grain-colour texture
        // without needing per-cell draw calls.
        ptsA.clear(); ptsB.clear()
        for (r in 0 until GRID_R) {
            for (c in 0 until GRID_C) {
                if (!grid[gIdx(c, r)]) continue
                val wy = rowToPhysY(r, gDown)
                val wx = colToPhysX(c)
                if (abs(wx - PCX) > glassHW(wy) + 1f) continue  // outside glass
                val off = Offset(wx * scX, wy * scY)
                if ((c + r) % 2 == 0) ptsA.add(off) else ptsB.add(off)
            }
        }
        // cellPx slightly larger than one grid cell → adjacent points overlap,
        // producing a solid-looking pile instead of a grid of dots.
        val cellPx = 2f * BULB_HW / GRID_C * scX * 1.14f
        if (ptsA.isNotEmpty()) drawPoints(
            ptsA, PointMode.Points, sandColor(0.33f),
            strokeWidth = cellPx, cap = StrokeCap.Round
        )
        if (ptsB.isNotEmpty()) drawPoints(
            ptsB, PointMode.Points, sandColor(0.47f),
            strokeWidth = cellPx, cap = StrokeCap.Round
        )

        // ── 3c. Falling grains — 3 hue buckets → 3 drawPoints GPU commands ───
        // Splitting by hue adds realistic colour variation to the stream while
        // keeping GPU draw call count at 3 instead of up to MAX_P.
        pts0.clear(); pts1.clear(); pts2.clear()
        var sumR = 0f; var nR = 0
        for (i in 0 until MAX_P) {
            val b   = i * STRIDE
            if (par[b + P_STATE] != S_FALLING) continue
            val hue = par[b + P_HUE]
            val off = Offset(par[b + P_X] * scX, par[b + P_Y] * scY)
            when {
                hue < 0.34f -> pts0.add(off)
                hue < 0.67f -> pts1.add(off)
                else        -> pts2.add(off)
            }
            sumR += par[b + P_RADIUS]; nR++
        }
        val avgRpx = if (nR > 0) sumR / nR * scR else 5.5f * scR
        if (pts0.isNotEmpty()) drawPoints(
            pts0, PointMode.Points, sandColor(0.13f),
            strokeWidth = avgRpx * 1.85f, cap = StrokeCap.Round
        )
        if (pts1.isNotEmpty()) drawPoints(
            pts1, PointMode.Points, sandColor(0.50f),
            strokeWidth = avgRpx * 2.00f, cap = StrokeCap.Round
        )
        if (pts2.isNotEmpty()) drawPoints(
            pts2, PointMode.Points, sandColor(0.85f),
            strokeWidth = avgRpx * 1.65f, cap = StrokeCap.Round
        )

        // ── 3d. Neck stream ribbon ─────────────────────────────────────────
        if (topProg > 0.02f && sessProg > 0.01f) {
            val streamA   = (topProg * 1.4f).coerceAtMost(0.82f)
            val neckY     = PCY * scY
            val streamLen = NECK_HW * scY * 3.2f
            val sign      = if (gDown) 1f else -1f
            val p1 = Offset(PCX * scX, neckY - sign * streamLen * 0.15f)
            val p2 = Offset(PCX * scX, neckY + sign * streamLen)
            // Main stream body
            drawLine(
                color       = sandColor(0.60f, streamA),
                start       = p1, end = p2,
                strokeWidth = NECK_HW * scX * 0.40f,
                cap         = StrokeCap.Round
            )
            // Central highlight on the stream (specular effect)
            drawLine(
                color       = sandColor(0.88f, streamA * 0.38f),
                start       = p1, end = p2,
                strokeWidth = NECK_HW * scX * 0.14f,
                cap         = StrokeCap.Round
            )
        }
    } // end clipPath

    // ── 4. Glass edge highlights ───────────────────────────────────────────────
    // Left rim catch-light: bright thin line along the inside of the left edge
    val hlPath = Path().apply {
        moveTo((PCX - BULB_HW + 10f) * scX, (CAP_H + 12f) * scY)
        for (s in 0..18) {
            val py = CAP_H + 12f + s.toFloat() / 18f * (PCY - CAP_H - 24f)
            lineTo((PCX - glassHW(py) + 7f) * scX, py * scY)
        }
    }
    drawPath(hlPath, color = Color.White.copy(alpha = 0.50f),
        style = Stroke(3.0f * scR, cap = StrokeCap.Round))

    // Right counter-highlight (shadow tint for depth)
    val shPath = Path().apply {
        moveTo((PCX + BULB_HW - 8f) * scX, (CAP_H + 10f) * scY)
        for (s in 0..14) {
            val py = CAP_H + 10f + s.toFloat() / 14f * (PCY - CAP_H - 20f)
            lineTo((PCX + glassHW(py) - 5f) * scX, py * scY)
        }
    }
    drawPath(shPath, color = Color.Black.copy(alpha = 0.18f),
        style = Stroke(2.4f * scR, cap = StrokeCap.Round))

    // Glass outline
    drawPath(glassPath, color = Color(0x4CFFFFFF), style = Stroke(1.6f * scR))

    // ── 5. Metal caps (top and bottom) ────────────────────────────────────────
    val capW    = (BULB_HW * 2f + 28f) * scX
    val capLeft = (PCX - BULB_HW - 14f) * scX
    val capH2   = 21f * scY

    for (isTop in listOf(true, false)) {
        val capTop = if (isTop) -2f * scY else (PH - CAP_H + 1f) * scY
        val gradTop   = capTop
        val gradBot   = capTop + capH2

        // Cap body with realistic metal gradient
        drawRoundRect(
            brush = Brush.verticalGradient(
                0.00f to Color(0xFF545454),
                0.30f to Color(0xFF303030),
                0.70f to Color(0xFF1E1E1E),
                1.00f to Color(0xFF282828),
                startY = gradTop, endY = gradBot
            ),
            topLeft      = Offset(capLeft, capTop),
            size         = Size(capW, capH2),
            cornerRadius = CornerRadius(9f * scR)
        )
        // Top sheen
        drawRoundRect(
            color        = Color.White.copy(alpha = 0.14f),
            topLeft      = Offset(capLeft + 5f * scX, capTop + 1.5f * scY),
            size         = Size(capW - 10f * scX, capH2 * 0.36f),
            cornerRadius = CornerRadius(7f * scR)
        )
        // Edge specular dot on the left
        drawCircle(
            color  = Color.White.copy(alpha = 0.22f),
            radius = 3.5f * scR,
            center = Offset(capLeft + 12f * scX, capTop + capH2 * 0.35f)
        )
        // Bottom shadow strip
        drawRoundRect(
            color        = Color.Black.copy(alpha = 0.25f),
            topLeft      = Offset(capLeft + 3f * scX, capTop + capH2 * 0.64f),
            size         = Size(capW - 6f * scX, capH2 * 0.33f),
            cornerRadius = CornerRadius(5f * scR)
        )
    }
}

