package kz.crdrive.phone

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal data class QueuedTelemetry(val id: Long, val body: String)

/** Durable FIFO used while the phone is offline or the cloud endpoint is unavailable. */
internal class TelemetryQueueStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE telemetry_queue (id INTEGER PRIMARY KEY AUTOINCREMENT, body TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun enqueue(body: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL("INSERT INTO telemetry_queue(body) VALUES (?)", arrayOf(body))
            writableDatabase.execSQL(
                "DELETE FROM telemetry_queue WHERE id IN (SELECT id FROM telemetry_queue ORDER BY id ASC LIMIT (SELECT MAX(COUNT(*) - ?, 0) FROM telemetry_queue))",
                arrayOf(MAX_QUEUE_ROWS)
            )
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    @Synchronized
    fun peek(): QueuedTelemetry? = readableDatabase.rawQuery(
        "SELECT id, body FROM telemetry_queue ORDER BY id ASC LIMIT 1", null
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else QueuedTelemetry(cursor.getLong(0), cursor.getString(1))
    }

    @Synchronized
    fun peekLatest(): QueuedTelemetry? = readableDatabase.rawQuery(
        "SELECT id, body FROM telemetry_queue ORDER BY id DESC LIMIT 1", null
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else QueuedTelemetry(cursor.getLong(0), cursor.getString(1))
    }

    @Synchronized
    fun remove(id: Long) {
        writableDatabase.delete("telemetry_queue", "id = ?", arrayOf(id.toString()))
    }

    @Synchronized
    fun count(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM telemetry_queue", null
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    companion object {
        private const val DATABASE_NAME = "telemetry_queue.db"
        private const val DATABASE_VERSION = 1
        // More than 13 hours at one packet per second. Oldest packets are discarded
        // only if this safety bound is reached, preventing unbounded disk growth.
        private const val MAX_QUEUE_ROWS = 50_000
    }
}
