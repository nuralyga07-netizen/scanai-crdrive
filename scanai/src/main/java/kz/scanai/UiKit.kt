package kz.scanai

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

val Navy = Color(0xFF010217)
val Navy2 = Color(0xFF040B33)
val PanelC = Color(0xFF0B1238)
val Blue = Color(0xFF1E90FF)
val Cyan = Color(0xFF19D3FF)
val White = Color(0xFFFFFFFF)
val Muted = Color(0xFF8FA0C8)
val TrackC = Color(0xFF121B45)

val ZoneLow = Color(0xFFFF5C7A)
val ZoneOk = Color(0xFF35E5A0)
val ZoneWarn = Color(0xFFFFA83D)
val ZoneHigh = Color(0xFFFF4D5E)

fun zoneColor(v: Double): Color = when {
    v < 3.9 -> ZoneLow
    v < 7.8 -> ZoneOk
    v < 10.0 -> ZoneWarn
    else -> ZoneHigh
}

fun zoneWord(v: Double) = when {
    v < 3.9 -> "НИЗКИЙ"
    v < 7.8 -> "НОРМА"
    v < 10.0 -> "ПОВЫШЕН"
    else -> "ВЫСОКИЙ"
}

/** Deep-navy backdrop with slowly drifting light particles. */
@Composable
fun Cosmos(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val t by rememberInfiniteTransition(label = "cosmos").animateFloat(0f, 1f,
        infiniteRepeatable(tween(40000, easing = LinearEasing)), label = "t")
    val seeds = remember { List(26) { Triple(Math.random().toFloat(), Math.random().toFloat(), 0.4f + Math.random().toFloat()) } }
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Navy, Navy2, Navy)))) {
        Canvas(Modifier.fillMaxSize()) {
            seeds.forEach { (sx, sy, sp) ->
                val y = ((sy - t * sp) % 1f + 1f) % 1f
                val x = sx + 0.03f * sin((t * 2f * PI.toFloat() * sp) + sy * 9f)
                val a = 0.10f + 0.25f * sin(y * PI.toFloat())
                drawCircle(Cyan.copy(alpha = a), (1.2f + sp * 1.8f).dp.toPx(), Offset(x * size.width, y * size.height))
            }
        }
        content()
    }
}

@Composable
fun GradientButton(text: String, modifier: Modifier = Modifier, pulse: Boolean = true, onClick: () -> Unit) {
    val s by rememberInfiniteTransition(label = "btn").animateFloat(1f, if (pulse) 1.025f else 1f,
        infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "bs")
    Box(modifier.scale(s).height(58.dp).clip(RoundedCornerShape(29.dp))
        .background(Brush.horizontalGradient(listOf(Cyan, Blue, Color(0xFF3A5BFF)))).clickable { onClick() },
        contentAlignment = Alignment.Center) {
        Text(text, color = White, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 2.sp)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(58.dp).clip(RoundedCornerShape(29.dp)).background(PanelC).clickable { onClick() },
        contentAlignment = Alignment.Center) {
        Text(text, color = White, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 2.sp)
    }
}

/** The big ring: spinning scanner while measuring, zone-coloured result ring with a count-up number afterwards. */
@Composable
fun ScanGauge(running: Boolean, progress: Float, value: Double?, resultKey: Long) {
    val inf = rememberInfiniteTransition(label = "gauge")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "spin")
    val pulse by inf.animateFloat(0.85f, 1f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "pulse")
    val prog by animateFloatAsState(progress, tween(350), label = "prog")
    val count = remember { Animatable(0f) }
    LaunchedEffect(resultKey, value) {
        count.snapTo(0f)
        if (value != null) count.animateTo(value.toFloat(), tween(1400))
    }
    val accent = if (!running && value != null) zoneColor(value) else Cyan
    val fill = if (value != null) ((count.value - 2f) / 10f).coerceIn(0f, 1f) else 0f

    Box(Modifier.size(310.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(310.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.width / 2 - 26.dp.toPx()
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.30f * pulse), Color.Transparent), c, size.width / 2), size.width / 2, c)
            val arcTL = Offset(c.x - r, c.y - r)
            val arcSz = Size(r * 2, r * 2)
            drawArc(TrackC, 0f, 360f, false, arcTL, arcSz, style = Stroke(14.dp.toPx()))
            if (running) {
                rotate(spin, c) {
                    drawArc(Brush.sweepGradient(listOf(Color.Transparent, Cyan.copy(alpha = 0.0f), Cyan.copy(alpha = 0.55f)), c),
                        0f, 360f, false, arcTL, arcSz, style = Stroke(14.dp.toPx()))
                }
                drawArc(Brush.sweepGradient(listOf(Cyan, Blue, Color(0xFF6A5BFF), Cyan), c), -90f, 360f * prog.coerceIn(0f, 1f), false,
                    arcTL, arcSz, style = Stroke(18.dp.toPx(), cap = StrokeCap.Round))
                val ang = (-90f + 360f * prog) * PI.toFloat() / 180f
                val p = Offset(c.x + r * cos(ang), c.y + r * sin(ang))
                drawCircle(White.copy(alpha = 0.35f * pulse), 16.dp.toPx(), p)
                drawCircle(White, 7.dp.toPx(), p)
            } else if (value != null) {
                drawArc(Brush.sweepGradient(listOf(accent.copy(alpha = 0.55f), accent, accent.copy(alpha = 0.55f)), c), -90f, 360f * fill, false,
                    arcTL, arcSz, style = Stroke(18.dp.toPx(), cap = StrokeCap.Round))
                val ang = (-90f + 360f * fill) * PI.toFloat() / 180f
                val p = Offset(c.x + r * cos(ang), c.y + r * sin(ang))
                drawCircle(accent.copy(alpha = 0.4f * pulse), 18.dp.toPx(), p)
                drawCircle(White, 7.dp.toPx(), p)
            }
            for (i in 0 until 60) {
                val a = (i / 60f) * 2f * PI.toFloat() - PI.toFloat() / 2
                val r1 = r - 22.dp.toPx()
                val r2 = r1 - (if (i % 5 == 0) 9.dp.toPx() else 4.dp.toPx())
                val lit = (running && i / 60f <= prog) || (!running && value != null && i / 60f <= fill)
                drawLine(if (lit) accent.copy(alpha = 0.9f) else Color(0xFF1C2760),
                    Offset(c.x + r1 * cos(a), c.y + r1 * sin(a)), Offset(c.x + r2 * cos(a), c.y + r2 * sin(a)), 2.dp.toPx())
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                running -> {
                    Text("${(prog * 100).toInt().coerceIn(0, 100)}%", color = White, fontSize = 64.sp, fontWeight = FontWeight.Black)
                    Text("СКАНИРОВАНИЕ", color = Cyan, fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
                }
                value != null -> {
                    Text("%.1f".format(count.value), color = White, fontSize = 72.sp, fontWeight = FontWeight.Black)
                    Text("ммоль/л", color = Muted, fontSize = 15.sp)
                }
                else -> {
                    Text("—", color = Muted, fontSize = 72.sp, fontWeight = FontWeight.Black)
                    Text("ГОТОВ К СКАНУ", color = Muted, fontSize = 12.sp, letterSpacing = 3.sp)
                }
            }
        }
    }
}

/** Horizontal scale with a glowing needle that springs to the value; colours follow the glucose zones. */
@Composable
fun GlucoseStick(value: Double?) {
    val target = if (value != null) ((value - 2.0) / 10.0).toFloat().coerceIn(0f, 1f) else 0.5f
    val pos by animateFloatAsState(target, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessVeryLow), label = "stick")
    val glow by rememberInfiniteTransition(label = "sg").animateFloat(0.5f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "g")
    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(64.dp)) {
            val barH = 14.dp.toPx()
            val top = size.height / 2 - barH / 2 + 6.dp.toPx()
            val stops = arrayOf(0f to ZoneLow, 0.12f to ZoneLow, 0.19f to ZoneOk, 0.58f to ZoneOk, 0.66f to ZoneWarn,
                0.78f to ZoneWarn, 0.84f to ZoneHigh, 1f to ZoneHigh)
            drawRoundRect(Brush.horizontalGradient(colorStops = stops), Offset(0f, top), Size(size.width, barH), CornerRadius(barH / 2))
            if (value != null) {
                val x = pos * size.width
                val col = zoneColor(value)
                drawRoundRect(col.copy(alpha = 0.35f * glow), Offset(x - 11.dp.toPx(), top - 14.dp.toPx()),
                    Size(22.dp.toPx(), barH + 28.dp.toPx()), CornerRadius(11.dp.toPx()))
                drawRoundRect(White, Offset(x - 3.5.dp.toPx(), top - 10.dp.toPx()),
                    Size(7.dp.toPx(), barH + 20.dp.toPx()), CornerRadius(3.5.dp.toPx()))
            }
        }
        Box(Modifier.fillMaxWidth()) {
            Text("2", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterStart))
            Text("3,9 · 7,8 · 10", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
            Text("12", color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

/** Glowing pulse line: your live signal while scanning, a calm scrolling heartbeat otherwise. */
@Composable
fun PulseLine(values: List<Double>, running: Boolean, color: Color) {
    val phase by rememberInfiniteTransition(label = "pl").animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "ph")
    Canvas(Modifier.fillMaxWidth().height(80.dp)) {
        val p = Path()
        if (running && values.size > 20) {
            val k = 25
            val c = values.mapIndexed { i, v -> v - values.subList(max(0, i - k / 2), min(values.size - 1, i + k / 2) + 1).average() }
            val lo = c.min()
            val hi = c.max()
            val span = max(hi - lo, 1e-9)
            c.forEachIndexed { i, v ->
                val x = size.width * i / (c.size - 1f)
                val y = size.height - ((v - lo) / span).toFloat() * size.height * 0.8f - size.height * 0.1f
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
        } else {
            val n = 160
            for (i in 0..n) {
                val x = size.width * i / n
                val u0 = ((i / n.toFloat()) * 2.2f - phase * 2.2f) % 1f
                val uu = if (u0 < 0) u0 + 1f else u0
                val bump = when {
                    uu in 0.40f..0.44f -> 0.10f
                    uu in 0.44f..0.48f -> -0.12f
                    uu in 0.48f..0.52f -> 0.55f
                    uu in 0.52f..0.56f -> -0.25f
                    uu in 0.62f..0.72f -> 0.08f * sin((uu - 0.62f) / 0.10f * PI.toFloat())
                    else -> 0f
                }
                val y = size.height * 0.55f - size.height * bump
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
        }
        drawPath(p, color.copy(alpha = 0.25f), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
        drawPath(p, Brush.horizontalGradient(listOf(Blue.copy(alpha = 0.2f), color, White)), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}
