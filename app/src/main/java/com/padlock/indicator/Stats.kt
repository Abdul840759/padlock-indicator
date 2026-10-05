package com.padlock.indicator

import android.content.Context
import java.io.File

/**
 * Tiny on-device log of unlocks and failures. One line per event:
 * epochMillis,KIND,latencyMs,note
 * KIND: FINGERPRINT, FACE, PIN, FACE_FAIL, FP_FAIL, PIN_FAIL
 */
class Stats(context: Context) {

    data class Entry(val time: Long, val kind: String, val latencyMs: Long, val note: String)

    private val file = File(context.applicationContext.filesDir, "unlocks.csv")

    fun add(kind: String, latencyMs: Long, note: String) {
        synchronized(LOCK) {
            try {
                file.appendText("${System.currentTimeMillis()},$kind,$latencyMs,$note\n")
                if (file.length() > 200_000L) {
                    val keep = file.readLines().takeLast(1500)
                    file.writeText(keep.joinToString("\n") + "\n")
                }
            } catch (_: Exception) {
            }
        }
    }

    fun readAll(): List<Entry> {
        val out = ArrayList<Entry>()
        synchronized(LOCK) {
            try {
                if (!file.exists()) return out
                for (line in file.readLines()) {
                    val p = line.split(",", limit = 4)
                    if (p.size < 4) continue
                    val time = p[0].toLongOrNull() ?: continue
                    val lat = p[2].toLongOrNull() ?: 0L
                    out.add(Entry(time, p[1], lat, p[3]))
                }
            } catch (_: Exception) {
            }
        }
        return out
    }

    fun tail(n: Int): String {
        synchronized(LOCK) {
            return try {
                if (!file.exists()) "" else file.readLines().takeLast(n).joinToString("\n")
            } catch (_: Exception) {
                ""
            }
        }
    }

    fun clear() {
        synchronized(LOCK) {
            try {
                file.delete()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private val LOCK = Any()
    }
}
