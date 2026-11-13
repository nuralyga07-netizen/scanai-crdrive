package kz.crdrive.phone

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class HistorySummary(val samples: Int, val days: Int, val medianBpm: Int?, val minimum: Int?, val maximum: Int?, val lastAlert: Long?)

/** Local-only history. No health data is sent to a server. */
class HistoryStore(context: Context) : SQLiteOpenHelper(context, "cr_drive_history.db", null, 1) {
    private var lastPrunedAt = 0L
    private var lastSavedAt: Long? = null
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE pulse (timestamp INTEGER PRIMARY KEY, bpm INTEGER NOT NULL, alert INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE reports (timestamp INTEGER PRIMARY KEY, level INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX pulse_time ON pulse(timestamp)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized fun save(point: HeartPoint, alert: Boolean) {
        if (lastSavedAt == null) {
            readableDatabase.rawQuery("SELECT MAX(timestamp) FROM pulse", null).use {
                lastSavedAt = if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
            }
        }
        if (!alert && point.timestamp - (lastSavedAt ?: 0L) < 60_000) return
        writableDatabase.execSQL("INSERT OR IGNORE INTO pulse(timestamp,bpm,alert) VALUES(?,?,?)",
            arrayOf<Any>(point.timestamp, point.bpm, if (alert) 1 else 0))
        lastSavedAt = point.timestamp
        pruneIfNeeded()
    }

    @Synchronized fun saveSleepiness(level: Int, timestamp: Long) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO reports(timestamp,level) VALUES(?,?)", arrayOf<Any>(timestamp, level))
        pruneIfNeeded()
    }

    @Synchronized fun recentPoints(now: Long = System.currentTimeMillis()): List<HeartPoint> {
        val result = mutableListOf<HeartPoint>()
        readableDatabase.rawQuery("SELECT timestamp,bpm FROM pulse WHERE timestamp>=? ORDER BY timestamp DESC LIMIT 60000",
            arrayOf((now - SEVEN_DAYS).toString())).use { cursor ->
            while (cursor.moveToNext()) result.add(HeartPoint(cursor.getInt(1), cursor.getLong(0)))
        }
        return result
    }

    @Synchronized fun summary(now: Long = System.currentTimeMillis()): HistorySummary {
        val points = recentPoints(now)
        val values = points.map { it.bpm }.sorted()
        val days = points.map { it.timestamp / DAY }.distinct().size
        var alertAt: Long? = null
        readableDatabase.rawQuery("SELECT MAX(timestamp) FROM pulse WHERE alert=1 AND timestamp>=?",
            arrayOf((now - SEVEN_DAYS).toString())).use { if (it.moveToFirst() && !it.isNull(0)) alertAt = it.getLong(0) }
        return HistorySummary(points.size, days, values.getOrNull(values.size / 2), values.firstOrNull(), values.lastOrNull(), alertAt)
    }

    @Synchronized fun lastSleepiness(): Pair<Int, Long>? {
        readableDatabase.rawQuery("SELECT level,timestamp FROM reports ORDER BY timestamp DESC LIMIT 1", null).use {
            return if (it.moveToFirst()) it.getInt(0) to it.getLong(1) else null
        }
    }

    private fun pruneIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastPrunedAt < 3_600_000) return
        lastPrunedAt = now
        val cutoff = now - SEVEN_DAYS
        writableDatabase.execSQL("DELETE FROM pulse WHERE timestamp<?", arrayOf(cutoff))
        writableDatabase.execSQL("DELETE FROM reports WHERE timestamp<?", arrayOf(cutoff))
    }

    companion object { const val DAY = 86_400_000L; const val SEVEN_DAYS = 7 * DAY }
}

/** A baseline is displayed only after several distinct days, never as a medical prediction. */
fun personalBaseline(points: List<HeartPoint>): Int? {
    if (points.size < 100 || points.map { it.timestamp / HistoryStore.DAY }.distinct().size < 3) return null
    val sorted = points.map { it.bpm }.filter { it in 35..220 }.sorted()
    return sorted.getOrNull(sorted.size / 2)
}
