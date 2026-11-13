package kz.crdrive.phone

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class MapFix(val latitude: Double, val longitude: Double,
    val accuracyMeters: Float, val measuredAt: Long, val source: String) {
    fun isFresh(now: Long) = measuredAt <= now + 5_000 && now - measuredAt in 0..60_000 &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0 && accuracyMeters in 0f..200f
}

private const val FOLLOW_ZOOM = 16.0      // about a 500 m radius around the point on a phone screen
private const val RADIUS_M = 500.0

private fun metersBetween(a: GeoPoint, b: GeoPoint): Double {
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude) * cos(Math.toRadians(a.latitude))
    return 6_371_000.0 * sqrt(dLat * dLat + dLon * dLon)
}

private fun bearing(a: GeoPoint, b: GeoPoint): Float {
    val y = sin(Math.toRadians(b.longitude - a.longitude)) * cos(Math.toRadians(b.latitude))
    val x = cos(Math.toRadians(a.latitude)) * sin(Math.toRadians(b.latitude)) -
        sin(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * cos(Math.toRadians(b.longitude - a.longitude))
    return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
}

/** Blue navigation arrow with a white rim, drawn once (pointing up; the marker is rotated to the heading). */
private fun arrowBitmap(density: Float): Bitmap {
    val s = (64 * density).toInt()
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = AndroidCanvas(bmp)
    val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3329A8FF }
    c.drawCircle(s / 2f, s / 2f, s / 2f, glow)
    val p = AndroidPath().apply {
        moveTo(s / 2f, s * 0.16f); lineTo(s * 0.74f, s * 0.80f); lineTo(s / 2f, s * 0.64f); lineTo(s * 0.26f, s * 0.80f); close()
    }
    c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 5 * density; strokeJoin = Paint.Join.ROUND })
    c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF29A8FF.toInt() })
    return bmp
}

/** Full-screen live map: follows the watch point like a navigator until the user pans it away. */
@Composable
fun LiveMap(fix: MapFix?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var following by remember { mutableStateOf(true) }
    val trail = remember { mutableStateListOf<GeoPoint>() }
    var heading by remember { mutableStateOf(0f) }
    val map = remember(context) {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            controller.setZoom(14.0)
            controller.setCenter(GeoPoint(50.2839, 57.1670))
        }
    }
    DisposableEffect(map) {
        map.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
            if (e.action == MotionEvent.ACTION_MOVE) following = false
            false
        }
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    val arrow = remember { BitmapDrawable(context.resources, arrowBitmap(context.resources.displayMetrics.density)) }

    Box(modifier) {
        AndroidView(factory = { map }, update = { view ->
            if (fix != null && view.tag != fix.measuredAt) {
                val point = GeoPoint(fix.latitude, fix.longitude)
                val last = trail.lastOrNull()
                if (last == null || metersBetween(last, point) > 3) {
                    if (last != null && metersBetween(last, point) > 4) heading = bearing(last, point)
                    trail.add(point); if (trail.size > 300) trail.removeAt(0)
                }
                view.overlays.clear()
                view.overlays.add(Polygon(view).apply {
                    points = Polygon.pointsAsCircle(point, RADIUS_M)
                    fillPaint.color = 0x0F29A8FF; outlinePaint.color = 0x6629A8FF; outlinePaint.strokeWidth = 3f
                })
                if (fix.accuracyMeters > 8f) view.overlays.add(Polygon(view).apply {
                    points = Polygon.pointsAsCircle(point, fix.accuracyMeters.toDouble())
                    fillPaint.color = 0x2229A8FF; outlinePaint.color = 0x00000000
                })
                if (trail.size > 1) view.overlays.add(Polyline(view).apply {
                    setPoints(trail.toList()); outlinePaint.color = 0xFF29A8FF.toInt(); outlinePaint.strokeWidth = 9f
                })
                view.overlays.add(Marker(view).apply {
                    position = point; icon = arrow; rotation = -heading
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); setInfoWindow(null)
                })
                view.tag = fix.measuredAt
                if (following) {
                    if (view.zoomLevelDouble < 15.0 || view.zoomLevelDouble > 18.5) view.controller.setZoom(FOLLOW_ZOOM)
                    view.controller.animateTo(point)
                }
                view.invalidate()
            }
        }, modifier = Modifier.fillMaxSize())
        if (fix != null && !following) {
            Box(Modifier.align(Alignment.BottomEnd).padding(16.dp).size(56.dp).clip(CircleShape)
                .background(Color(0xFF29A8FF)).clickable {
                    following = true
                    map.controller.setZoom(FOLLOW_ZOOM)
                    map.controller.animateTo(GeoPoint(fix.latitude, fix.longitude))
                }, contentAlignment = Alignment.Center) {
                Text("◎", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (fix == null) {
            Box(Modifier.align(Alignment.TopCenter).padding(16.dp)
                .background(Color(0xCC08111C), RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 10.dp)) {
                Text("Ищем GPS часов…", color = Color.White, fontSize = 13.sp)
            }
        }
    }
}
