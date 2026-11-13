package kz.crdrive.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Events from the watch: big cards, colour by severity, minimum text. */
@Composable
fun EventsTab(telemetry: TelemetryState) {
    val items = buildList {
        if (telemetry.lowPulseAlert) add("Низкий пульс" to CrRed)
        if (telemetry.motionAlert) add("Нет движения" to CrOrange)
        telemetry.events.forEach { e ->
            val clean = e.removePrefix("⚠").trim()
            val color = when {
                e.startsWith("⚠") || clean.contains("Низкий", true) || clean.contains("тревог", true) -> CrRed
                clean.startsWith("ЭКГ") -> CrGreen
                clean.contains("повыш", true) || clean.contains("Резкий", true) || clean.contains("выше", true) -> CrOrange
                else -> Color(0xFF35BDF2)
            }
            add(clean to color)
        }
    }.distinctBy { it.first }
    if (items.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(84.dp).background(CrGreen.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Text("✓", color = CrGreen, fontSize = 44.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(14.dp))
            Text("Всё спокойно", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        return
    }
    items.forEachIndexed { i, (title, color) ->
        Row(Modifier.fillMaxWidth().background(Color(0xFF101D2B), RoundedCornerShape(24.dp)).padding(vertical = 4.dp)) {
            Box(Modifier.padding(start = 6.dp).width(6.dp).height(64.dp).align(Alignment.CenterVertically)
                .background(color, RoundedCornerShape(3.dp)))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp).weight(1f)) {
                Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(if (i == 0) "только что" else "ранее", color = Color(0xFFA6B4C2), fontSize = 12.sp)
            }
            Box(Modifier.align(Alignment.CenterVertically).padding(end = 18.dp).size(14.dp).background(color, CircleShape))
        }
        Spacer(Modifier.height(10.dp))
    }
}
