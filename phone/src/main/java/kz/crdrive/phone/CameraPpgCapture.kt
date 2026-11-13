package kz.crdrive.phone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Finger-on-camera PPG: the back camera with the torch on films the fingertip and the mean image
 * brightness over time is the pulse wave. Runs [DURATION_S] seconds, skips the first [SKIP_S]
 * (auto-exposure settling), then feeds the signal into [GlucoseAiModel].
 */
class CameraPpgCapture(private val context: Context) {
    interface Listener {
        fun onProgress(progress: Float, fingerOk: Boolean, wave: List<Double>)
        fun onDone(result: GlucoseAiModel.Result?, reason: String?)
    }

    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var listener: Listener? = null
    @Volatile private var running = false

    private val times = ArrayList<Long>()
    private val values = ArrayList<Double>()
    private val valuesV = ArrayList<Double>()
    private var fingerFrames = 0
    private var totalFrames = 0
    private var startNs = 0L
    private var aeLocked = false
    private val recent = ArrayDeque<Double>()

    @SuppressLint("MissingPermission")
    fun start(l: Listener) {
        if (running) return
        listener = l
        times.clear(); values.clear(); valuesV.clear(); recent.clear(); fingerFrames = 0; totalFrames = 0; startNs = 0L; aeLocked = false
        running = true
        val t = HandlerThread("ppg-camera").also { it.start() }
        thread = t; handler = Handler(t.looper)
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                val c = cm.getCameraCharacteristics(it)
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                    c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return fail("Камера не найдена")
            val map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val size = map?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.filter { it.width >= 160 && it.height >= 120 }?.minByOrNull { it.width * it.height }
                ?: return fail("Нет подходящего размера кадра")
            val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            reader = r
            r.setOnImageAvailableListener({ rd -> onFrame(rd) }, handler)
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    try {
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                try { s.setRepeatingRequest(buildRequest(camera, r, false), null, handler) }
                                catch (e: Exception) { fail("Не удалось запустить камеру") }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { fail("Камера не настроилась") }
                        }, handler)
                    } catch (e: Exception) { fail("Не удалось открыть камеру") }
                }
                override fun onDisconnected(camera: CameraDevice) { fail("Камера отключена") }
                override fun onError(camera: CameraDevice, error: Int) { fail("Ошибка камеры ($error)") }
            }, handler)
        } catch (e: Exception) {
            fail("Камера недоступна")
        }
    }

    private fun buildRequest(camera: CameraDevice, r: ImageReader, lock: Boolean): CaptureRequest {
        val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(r.surface)
        b.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
        b.set(CaptureRequest.CONTROL_AE_LOCK, lock)
        b.set(CaptureRequest.CONTROL_AWB_LOCK, lock)
        return b.build()
    }

    private fun onFrame(rd: ImageReader) {
        val img = rd.acquireLatestImage() ?: return
        try {
            if (!running) return
            val w = img.width; val h = img.height
            val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
            val y = yP.buffer; val u = uP.buffer; val v = vP.buffer
            var ySum = 0L; var uSum = 0L; var vSum = 0L; var n = 0
            var py = h / 4
            while (py < h * 3 / 4) {
                var px = w / 4
                while (px < w * 3 / 4) {
                    ySum += (y.get(py * yP.rowStride + px * yP.pixelStride).toInt() and 0xFF)
                    uSum += (u.get((py / 2) * uP.rowStride + (px / 2) * uP.pixelStride).toInt() and 0xFF)
                    vSum += (v.get((py / 2) * vP.rowStride + (px / 2) * vP.pixelStride).toInt() and 0xFF)
                    n++; px += 4
                }
                py += 4
            }
            if (n == 0) return
            val yMean = ySum.toDouble() / n; val uMean = uSum.toDouble() / n; val vMean = vSum.toDouble() / n
            val fingerOk = yMean > 40 && vMean - uMean > 15
            val ts = img.timestamp
            if (startNs == 0L) startNs = ts
            val elapsed = (ts - startNs) / 1e9
            totalFrames++; if (fingerOk) fingerFrames++
            if (elapsed >= SKIP_S) { times.add(ts); values.add(yMean); valuesV.add(vMean) }
            if (!aeLocked && elapsed >= 1.5) {
                aeLocked = true
                try { session?.setRepeatingRequest(buildRequest(device!!, reader!!, true), null, handler) } catch (_: Exception) {}
            }
            val progress = (elapsed / DURATION_S).toFloat().coerceIn(0f, 1f)
            recent.addLast(yMean); while (recent.size > 150) recent.removeFirst()
            if (totalFrames % 2 == 0) {
                val snapshot = ArrayList(recent)
                main.post { listener?.onProgress(progress, fingerOk, snapshot) }
            }
            if (elapsed >= DURATION_S) finish()
        } finally {
            img.close()
        }
    }

    private fun finish() {
        if (!running) return
        running = false
        val t = ArrayList(times); val vals = ArrayList(values); val valsV = ArrayList(valuesV)
        val fingerShare = if (totalFrames > 0) fingerFrames.toDouble() / totalFrames else 0.0
        releaseCamera()
        val l = listener
        if (fingerShare < 0.8 || t.size < 100) {
            main.post { l?.onDone(null, "Палец не был плотно на камере и вспышке. Повторите.") }
            return
        }
        // 1) uniform 30 Hz grid (camera frame times jitter), 2) best 28 s window and best channel by pulse-peak ratio
        val fsOut = 30.0
        val chans = listOf("Y" to resample(t, vals, fsOut), "V" to resample(t, valsV, fsOut))
        try {
            val dir = java.io.File(context.applicationContext.filesDir, "camera_ppg"); dir.mkdirs()
            val stamp = System.currentTimeMillis()
            val y0 = chans[0].second; val v0 = chans[1].second
            java.io.File(dir, "cam-$stamp.csv").bufferedWriter().use { w ->
                w.write("i,y,v\n")
                for (i in y0.indices) w.write(String.format(java.util.Locale.US, "%d,%.4f,%.4f\n", i, y0[i], v0.getOrElse(i) { 0.0 }))
            }
            lastCaptureAtMs = stamp
        } catch (_: Exception) {}
        val winLen = (GlucoseAiModel.MIN_SECONDS * fsOut).toInt()
        var bestScore = -1.0; var bestWin: DoubleArray? = null; var bestName = ""
        for ((name, sig) in chans) {
            if (sig.size < winLen) continue
            var s0 = 0
            while (s0 + winLen <= sig.size) {
                val w = sig.copyOfRange(s0, s0 + winLen)
                val sc = peakRatio(w, fsOut)
                if (sc > bestScore) { bestScore = sc; bestWin = w; bestName = name + "@" + (s0 / fsOut).toInt() + "s" }
                s0 += fsOut.toInt()
            }
        }
        val result = bestWin?.let { GlucoseAiModel.estimate(it, fsOut) }
        Log.i("CameraPpg", "frames=${t.size} fingerShare=%.2f best=$bestName peakRatio=%.3f hr=%.1f sqi=%.2f mmol=%.2f"
            .format(fingerShare, bestScore, result?.heartRate ?: -1.0, result?.quality ?: -1.0, result?.mmol ?: -1.0))
        main.post { l?.onDone(result, if (result == null) "Сигнал слишком шумный. Повторите, не двигая рукой." else null) }
    }

    private fun resample(t: List<Long>, v: List<Double>, fsOut: Double): DoubleArray {
        if (t.size < 2) return DoubleArray(0)
        val t0 = t.first()
        val n = (((t.last() - t0) / 1e9) * fsOut).toInt()
        val out = DoubleArray(n)
        var j = 0
        for (i in 0 until n) {
            val ti = t0 + (i / fsOut * 1e9).toLong()
            while (j < t.size - 2 && t[j + 1] < ti) j++
            val a = t[j]; val b = t[j + 1]
            val f = if (b == a) 0.0 else ((ti - a).toDouble() / (b - a)).coerceIn(0.0, 1.0)
            out[i] = v[j] + (v[j + 1] - v[j]) * f
        }
        return out
    }

    /** Share of spectral power in the strongest pulse-band peak (0.7-3 Hz): a standard PPG quality index. */
    private fun peakRatio(x: DoubleArray, fs: Double): Double {
        val n = x.size
        val mean = x.average()
        val w = DoubleArray(n) { (x[it] - mean) * (0.5 - 0.5 * cos(2 * PI * it / (n - 1))) }
        val freqs = ArrayList<Double>(); val pw = ArrayList<Double>()
        var f = 0.5
        while (f <= 8.0) {
            val om = 2 * PI * f / fs
            var re = 0.0; var im = 0.0
            for (i in 0 until n) { re += w[i] * cos(om * i); im -= w[i] * sin(om * i) }
            freqs.add(f); pw.add(re * re + im * im)
            f += 0.1
        }
        val total = pw.sum()
        var peak = 0.0
        for (k in pw.indices) if (freqs[k] in 0.7..3.0) {
            val s = pw[k] + (pw.getOrNull(k - 1) ?: 0.0) + (pw.getOrNull(k + 1) ?: 0.0)
            if (s > peak) peak = s
        }
        return if (total > 0) peak / total else 0.0
    }

    private fun fail(reason: String) {
        if (!running && device == null) { main.post { listener?.onDone(null, reason) }; return }
        running = false
        releaseCamera()
        main.post { listener?.onDone(null, reason) }
    }

    fun stop() {
        running = false
        releaseCamera()
    }

    private fun releaseCamera() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null; device = null; reader = null
        thread?.quitSafely(); thread = null; handler = null
    }

    companion object {
        @Volatile var lastCaptureAtMs = 0L
        const val DURATION_S = 36.0
        const val SKIP_S = 5.0
    }
}
