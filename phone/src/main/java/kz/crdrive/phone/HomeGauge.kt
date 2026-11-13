package kz.crdrive.phone

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

val CrGreen = Color(0xFF3EE08F)
val CrOrange = Color(0xFFFFA63D)
val CrRed = Color(0xFFFF4D5E)
private val CrPanel = Color(0xFF101D2B)
private val CrMuted = Color(0xFFA6B4C2)
private val CrTrack = Color(0xFF1B2A3A)

/** Live state scale: green = normal, orange = not great, red = bad. Higher score = worse. */
@Composable
fun StateGauge(score: Int?, word: String) {
    val frac by animateFloatAsState(((score ?: 0) / 100f).coerceIn(0f, 1f), tween(700), label = "state")
    val live = score != null
    val glow by rememberInfiniteTransition(label = "glow").animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "glow-a")
    val color = when {
        !live -> CrMuted
        frac < 0.42f -> CrGreen
        frac < 0.72f -> CrOrange
        else -> CrRed
    }
    Card(colors = CardDefaults.cardColors(containerColor = CrPanel), shape = RoundedCornerShape(32.dp),
        modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(vertical = 22.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(280.dp)) {
                    val stroke = 26.dp.toPx()
                    val inset = stroke / 2 + 8.dp.toPx()
                    val arc = Size(size.width - inset * 2, size.height - inset * 2)
                    val start = 150f; val sweep = 240f; val n = 64
                    val seg = sweep / n
                    for (i in 0 until n) {
                        val f = i / (n - 1f)
                        val zone = if (f < 0.42f) CrGreen else if (f < 0.72f) CrOrange else CrRed
                        val lit = live && f <= frac
                        drawArc(
                            color = if (lit) zone.copy(alpha = if (f >= frac - 0.06f) glow else 1f) else zone.copy(alpha = 0.16f),
                            startAngle = start + i * seg + 0.5f, sweepAngle = seg - 1f, useCenter = false,
                            topLeft = Offset(inset, inset), size = arc, style = Stroke(stroke, cap = StrokeCap.Butt))
                    }
                    if (live) {
                        val ang = (start + sweep * frac) * PI.toFloat() / 180f
                        val r = (size.width - inset * 2) / 2f
                        val c = Offset(size.width / 2 + r * cos(ang), size.height / 2 + r * sin(ang))
                        drawCircle(Color.White, 15.dp.toPx(), c)
                        drawCircle(color, 9.dp.toPx(), c)
                    }
                }
                Text(word, color = color, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
        }
    }
}

/** Pulse and watch battery under the gauge. */
@Composable
fun HomeTiles(bpm: Int?, battery: Int?, accent: Color) {
    val beat by rememberInfiniteTransition(label = "beat").animateFloat(
        0.92f, 1.12f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "beat-s")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = CrPanel), shape = RoundedCornerShape(26.dp)) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(accent.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Favorite, null, tint = accent, modifier = Modifier.size(24.dp).scale(if (bpm != null) beat else 1f))
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(bpm?.toString() ?: "—", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    Text("уд/мин", color = CrMuted, fontSize = 12.sp)
                }
            }
        }
        Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = CrPanel), shape = RoundedCornerShape(26.dp)) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(battery?.takeIf { it >= 0 }?.toString() ?: "—", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    Text(" %", color = CrMuted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 5.dp))
                }
                Spacer(Modifier.height(6.dp))
                val b = (battery ?: 0).coerceIn(0, 100) / 100f
                Box(Modifier.fillMaxWidth().height(8.dp).background(CrTrack, RoundedCornerShape(4.dp))) {
                    Box(Modifier.fillMaxWidth(b).height(8.dp).background(
                        if (b < 0.2f) CrRed else if (b < 0.4f) CrOrange else CrGreen, RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}
