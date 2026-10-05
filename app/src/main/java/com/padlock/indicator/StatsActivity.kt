package com.padlock.indicator

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class StatsActivity : Activity() {

    private lateinit var stats: Stats
    private lateinit var content: LinearLayout
    private var period = 1 // 0 today, 1 last 7 days, 2 all time

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Unlock stats"
        stats = Stats(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun startOfToday(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun heading(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = 18f
            setPadding(0, dp(22), 0, dp(4))
        })
    }

    private fun line(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(0, dp(3), 0, dp(3))
        })
    }

    private fun note(text: String) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            alpha = 0.6f
            setPadding(0, dp(4), 0, dp(4))
        })
    }

    private fun bar(label: String, n: Int, total: Int, color: Int, avgSec: Double) {
        val pct = if (total > 0) n * 100 / total else 0
        val avg = if (avgSec > 0) String.format(Locale.US, " - avg %.1f s", avgSec) else ""
        content.addView(TextView(this).apply {
            text = label + ": " + n + " (" + pct + "%)" + avg
            textSize = 14f
            setPadding(0, dp(10), 0, dp(2))
        })
        val w = if (n > 0) pct.coerceAtLeast(1).toFloat() else 0f
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 100f
        }
        row.addView(View(this).apply {
            setBackgroundColor(color)
            layoutParams = LinearLayout.LayoutParams(0, dp(10), w)
        })
        row.addView(View(this).apply {
            setBackgroundColor(0x22000000)
            layoutParams = LinearLayout.LayoutParams(0, dp(10), 100f - w)
        })
        content.addView(row)
    }

    private fun render() {
        content.removeAllViews()

        val picker = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val names = arrayOf("Today", "7 days", "All time")
        for (i in names.indices) {
            picker.addView(Button(this).apply {
                text = names[i]
                isAllCaps = false
                isEnabled = i != period
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    period = i
                    render()
                }
            })
        }
        content.addView(picker)

        val now = System.currentTimeMillis()
        val since = when (period) {
            0 -> startOfToday()
            1 -> now - 7L * 24L * 3600L * 1000L
            else -> 0L
        }
        val list = stats.readAll().filter { it.time >= since }
        val ok = setOf("FINGERPRINT", "FACE", "PIN")
        val unlocks = list.filter { it.kind in ok }
        val total = unlocks.size

        fun count(k: String) = unlocks.count { it.kind == k }
        fun avg(k: String): Double {
            val l = unlocks.filter { it.kind == k && it.latencyMs > 0 }
            return if (l.isEmpty()) 0.0 else l.map { it.latencyMs }.average() / 1000.0
        }

        heading("Unlocks: $total")
        bar("Fingerprint", count("FINGERPRINT"), total, 0xFF0A84FF.toInt(), avg("FINGERPRINT"))
        bar("Face", count("FACE"), total, 0xFF34C759.toInt(), avg("FACE"))
        bar("PIN / password", count("PIN"), total, 0xFFFF9F0A.toInt(), avg("PIN"))

        val faceOk = count("FACE")
        val faceFail = list.count { it.kind == "FACE_FAIL" }
        val fpFail = list.count { it.kind == "FP_FAIL" }
        val pinFail = list.count { it.kind == "PIN_FAIL" }
        val faceTries = faceOk + faceFail

        heading("What's failing")
        var faceLine = "Face not recognized: $faceFail"
        if (faceTries > 0) {
            faceLine += " (face works " + (faceOk * 100 / faceTries) + "% of the time)"
        }
        line(faceLine)
        line("Fingerprint not recognized: $fpFail")
        line("Wrong PIN / password: $pinFail")
        note("Face failures come from the camera. Fingerprint and PIN failures only count when Android announces them.")

        heading("What I notice")
        val tips = ArrayList<String>()
        if (total == 0) {
            tips.add("Nothing recorded for this period yet. Use the phone normally and check back.")
        }
        if (faceTries >= 5 && faceOk * 100 / faceTries < 60) {
            tips.add(
                "Face unlock only worked " + (faceOk * 100 / faceTries) + "% of the time (" +
                    faceOk + " of " + faceTries + "). Re-enroll your face, or add a second look " +
                    "(with and without glasses)."
            )
        }
        val pinShare = if (total > 0) count("PIN") * 100 / total else 0
        if (total >= 10 && pinShare >= 35) {
            tips.add(
                "You type your PIN or password for " + pinShare + "% of unlocks. Try re-registering " +
                    "the fingerprint you use most (add the same finger twice) so it works more often."
            )
        }
        val faceAvg = avg("FACE")
        val fpAvg = avg("FINGERPRINT")
        if (faceAvg > 0 && fpAvg > 0) {
            if (faceAvg < fpAvg) {
                tips.add(
                    String.format(
                        Locale.US,
                        "Face is your fastest method (%.1f s vs %.1f s for fingerprint).",
                        faceAvg, fpAvg
                    )
                )
            } else {
                tips.add(
                    String.format(
                        Locale.US,
                        "Fingerprint is faster for you (%.1f s vs %.1f s for face).",
                        fpAvg, faceAvg
                    )
                )
            }
        }
        if (tips.isEmpty()) tips.add("Everything looks healthy so far.")
        for (t in tips) line("- $t")

        heading("Recent")
        val fmt = SimpleDateFormat("EEE HH:mm:ss", Locale.getDefault())
        for (e in list.takeLast(12).reversed()) {
            val name = when (e.kind) {
                "FINGERPRINT" -> "Fingerprint"
                "FACE" -> "Face"
                "PIN" -> "PIN / password"
                "FACE_FAIL" -> "Face not recognized"
                "FP_FAIL" -> "Fingerprint not recognized"
                "PIN_FAIL" -> "Wrong PIN / password"
                else -> e.kind
            }
            val lat = if (e.latencyMs > 0) String.format(Locale.US, " - %.1f s", e.latencyMs / 1000.0) else ""
            line(fmt.format(Date(e.time)) + "   " + name + lat)
        }
        if (list.isEmpty()) line("No entries yet.")

        content.addView(Button(this).apply {
            text = "Copy debug data"
            isAllCaps = false
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("padlock", stats.tail(60)))
                Toast.makeText(this@StatsActivity, "Copied the last 60 entries", Toast.LENGTH_SHORT).show()
            }
        })
        content.addView(Button(this).apply {
            text = "Clear stats"
            isAllCaps = false
            setOnClickListener {
                AlertDialog.Builder(this@StatsActivity)
                    .setMessage("Delete all recorded unlock stats?")
                    .setPositiveButton("Delete") { _, _ ->
                        stats.clear()
                        render()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
    }
}
