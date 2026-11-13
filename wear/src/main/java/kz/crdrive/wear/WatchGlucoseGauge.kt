package kz.crdrive.wear

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.wear.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val G_MIN = 2.0
private const val G_MAX = 12.0

private fun gZone(v: Double): Color = when {
    v < 3.9 -> Color(0xFFFF6B6B)
    v < 5.6 -> Color(0xFF52E2BD)
    v < 7.8 -> Color(0xFFB9FF62)
    v < 10.0 -> Color(0xFFFFB84D)
    else -> Color(0xFFFF6B6B)
}

/** Round live gauge (mmol/L) with the model estimate in the centre; progress ring while recording. */
@Composable
internal fun WatchGlucoseGauge(language: WatchLanguage, mmol: Double?, progress: Float, recording: Boolean) {
    val target = mmol?.let { ((it - G_MIN) / (G_MAX - G_MIN)).toFloat().coerceIn(0f, 1f) } ?: 0f
    val animated by animateFloatAsState(target, tween(800), label = "wgl")
    Box(Modifier.size(176.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(176.dp)) {
            val stroke = 15.dp.toPx()
            val inset = stroke / 2 + 3.dp.toPx()
            val arc = Size(size.width - inset * 2, size.height - inset * 2)
            val start = 150f
            val sweep = 240f
            val n = 48
            val seg = sweep / n
            for (i in 0 until n) {
                val frac = i / (n - 1f)
                val on = mmol != null && frac <= animated
                drawArc(
                    color = if (on) gZone(G_MIN + frac * (G_MAX - G_MIN)) else Color(0xFF1B2A3A),
                    startAngle = start + i * seg + 0.5f, sweepAngle = seg - 1f, useCenter = false,
                    topLeft = Offset(inset, inset), size = arc, style = Stroke(stroke, cap = StrokeCap.Butt))
            }
            if (mmol == null && progress > 0f) {
                drawArc(
                    color = Color(0xFFB9FF62).copy(alpha = 0.9f), startAngle = start, sweepAngle = sweep * progress,
                    useCenter = false, topLeft = Offset(inset, inset), size = arc,
                    style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (mmol != null) {
                Text("%.1f".format(mmol), color = gZone(mmol), fontSize = 44.sp,
                    fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold)
                Text(watchText(language, "ммоль/л", "ммоль/л", "mmol/L"), color = Color(0xFFA9B7C8),
                    fontSize = 13.sp, fontFamily = FontFamily.SansSerif)
                val zone = when {
                    mmol < 3.9 -> watchText(language, "НИЖЕ НОРМЫ", "НОРМАДАН ТӨМЕН", "BELOW RANGE")
                    mmol < 7.8 -> watchText(language, "НОРМА", "НОРМА", "IN RANGE")
                    else -> watchText(language, "ВЫШЕ НОРМЫ", "НОРМАДАН ЖОҒАРЫ", "ABOVE RANGE")
                }
                Text(zone, color = gZone(mmol), fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(top = 4.dp)
                        .background(gZone(mmol).copy(alpha = 0.16f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 3.dp))
            } else {
                Text(if (recording) "${(progress * 100).toInt()}%" else "—", color = Color.White, fontSize = 40.sp,
                    fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold)
                Text(
                    if (recording) watchText(language, "запись сигнала", "сигнал жазылуда", "recording")
                    else watchText(language, "ммоль/л", "ммоль/л", "mmol/L"),
                    color = Color(0xFFA9B7C8), fontSize = 12.sp, fontFamily = FontFamily.SansSerif)
            }
        }
    }
}
