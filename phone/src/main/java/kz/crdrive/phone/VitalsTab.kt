package kz.crdrive.phone

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

private val VPanel = Color(0xFF101D2B)
private val VMuted = Color(0xFFA6B4C2)
private val VRed = Color(0xFFFF6B7A)
private val VBlue = Color(0xFF35BDF2)
private val VMint = Color(0xFFB9FF62)
private val VAmber = Color(0xFFFFB84D)

/** Approximate skin-independent estimate: about +1 °C per 10 bpm above the resting baseline; not a measurement. */
private fun estimateTemperature(bpm: Int?, baseline: Int?, spo2: Double?): Double? {
    if (bpm == null) return null
    var t = 36.6 + (bpm - (baseline ?: 70)) / 10.0 * 0.6
    if (spo2 != null && spo2 < 94) t += 0.2
    return t.coerceIn(35.8, 38.5)
}

/** All watch indicators in one place. */
@Composable
fun VitalsTab(telemetry: TelemetryState, now: Long) {
    val bpm = telemetry.bpm
    val spo2 = telemetry.contextHealth.oxygenPercent
    val history = telemetry.samples.takeLast(60).map { it.bpm.toDouble() }
    val temp = estimateTemperature(bpm, telemetry.personalBaseline ?: telemetry.assessment.baseline, spo2)
    val ecg = telemetry.ecg

    VitalCard(
        title = "ПУЛЬС", value = bpm?.toString() ?: "—", unit = "уд/мин", accent = VRed,
        modifier = Modifier.fillMaxWidth()
    ) { if (history.size > 3) Sparkline(history, VRed) }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VitalCard("КИСЛОРОД", spo2?.let { "%.0f".format(it) } ?: "—", "%", VBlue, Modifier.weight(1f))
        VitalCard("ТЕМПЕРАТУРА ≈", temp?.let { "%.1f".format(it) } ?: "—", "°C", VAmber, Modifier.weight(1f))
    }
    Spacer(Modifier.height(12.dp))
    VitalCard("ЭКГ", ecg?.hr?.toString() ?: "—", if (ecg != null) "уд/мин · контакт ${ecg.quality}%" else "", VMint, Modifier.fillMaxWidth()) {
        if (ecg != null && ecg.wave.size > 10) Sparkline(ecg.wave.map { it.toDouble() }, VMint)
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VitalCard("ЗАРЯД ЧАСОВ", telemetry.battery?.takeIf { it >= 0 }?.toString() ?: "—", "%", VMint, Modifier.weight(1f))
        VitalCard("ШАГИ", telemetry.sensors.tripSteps?.toString() ?: "—", "", VBlue, Modifier.weight(1f))
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VitalCard("ДВИЖЕНИЕ", telemetry.sensors.acceleration?.let { "%.1f".format(it) } ?: "—", "м/с²", VAmber, Modifier.weight(1f))
        VitalCard("НА РУКЕ", when (telemetry.sensors.onBody) { true -> "да"; false -> "нет"; null -> "—" }, "", VRed, Modifier.weight(1f))
    }
}

@Composable
private fun VitalCard(
    title: String, value: String, unit: String, accent: Color, modifier: Modifier,
    content: @Composable () -> Unit = {}
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = VPanel), shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(accent, CircleShape))
                Text(title, color = VMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(value, color = if (value == "—") VMuted else Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text(unit, color = VMuted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
            content()
        }
    }
}

@Composable
private fun Sparkline(values: List<Double>, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(64.dp).padding(top = 8.dp)) {
        val lo = values.min(); val hi = values.max(); val span = max(hi - lo, 4.0)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1f)
            val y = size.height - ((v - lo) / span).toFloat() * (size.height * 0.85f) - size.height * 0.05f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
}
