package com.smartcopy.app

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val time: Long,
    val mode: TransferMode,
    val done: Int,
    val skipped: Int,
    val failed: Int,
    val bytes: Long,
    val durationMs: Long,
    val avgSpeed: Double,
    val peakSpeed: Double,
    val destination: String,
    val verified: Boolean,
    val cancelled: Boolean,
    val failures: List<Pair<String, String>>
)

data class SessionItem(
    val id: Long,
    val src: Uri,
    val name: String,
    val size: Long,
    val rel: List<String>,
    val srcParent: Uri?
)

data class SessionFolder(val tree: Uri, val docId: String, val rel: List<String>)

data class Session(
    val destination: Uri,
    val mode: TransferMode,
    val savedAt: Long,
    val items: List<SessionItem>,
    val folders: List<SessionFolder>
)

data class ResumeOffer(val files: Int, val bytes: Long, val destinationName: String, val savedAt: Long)

/** Sauvegarde de l'historique et de la file en cours (pour reprendre après une interruption). */
object Persistence {

    private const val HISTORY_MAX = 50
    private const val HISTORY_FILE = "history.json"
    private const val SESSION_FILE = "session.json"

    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    private fun finite(v: Double): Double = if (v.isNaN() || v.isInfinite()) 0.0 else v

    private fun strings(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return List(a.length()) { a.getString(it) }
    }

    private fun modeOf(name: String): TransferMode = try {
        TransferMode.valueOf(name)
    } catch (e: Exception) {
        TransferMode.COPY
    }

    // ---------- Historique ----------

    fun loadHistory(ctx: Context): List<HistoryEntry> = try {
        val f = File(ctx.filesDir, HISTORY_FILE)
        if (!f.exists()) {
            emptyList()
        } else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val fl = o.optJSONArray("failures")
                val failures = if (fl == null) {
                    emptyList()
                } else {
                    (0 until fl.length()).map { k ->
                        val x = fl.getJSONObject(k)
                        Pair(x.optString("name"), x.optString("error"))
                    }
                }
                HistoryEntry(
                    time = o.optLong("time"),
                    mode = modeOf(o.optString("mode", "COPY")),
                    done = o.optInt("done"),
                    skipped = o.optInt("skipped"),
                    failed = o.optInt("failed"),
                    bytes = o.optLong("bytes"),
                    durationMs = o.optLong("durationMs"),
                    avgSpeed = o.optDouble("avgSpeed", 0.0),
                    peakSpeed = o.optDouble("peakSpeed", 0.0),
                    destination = o.optString("destination"),
                    verified = o.optBoolean("verified"),
                    cancelled = o.optBoolean("cancelled"),
                    failures = failures
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun saveHistory(ctx: Context, list: List<HistoryEntry>) {
        try {
            val arr = JSONArray()
            for (h in list.take(HISTORY_MAX)) {
                val o = JSONObject()
                o.put("time", h.time)
                o.put("mode", h.mode.name)
                o.put("done", h.done)
                o.put("skipped", h.skipped)
                o.put("failed", h.failed)
                o.put("bytes", h.bytes)
                o.put("durationMs", h.durationMs)
                o.put("avgSpeed", finite(h.avgSpeed))
                o.put("peakSpeed", finite(h.peakSpeed))
                o.put("destination", h.destination)
                o.put("verified", h.verified)
                o.put("cancelled", h.cancelled)
                val fl = JSONArray()
                for (p in h.failures) {
                    val x = JSONObject()
                    x.put("name", p.first)
                    x.put("error", p.second)
                    fl.put(x)
                }
                o.put("failures", fl)
                arr.put(o)
            }
            writeAtomic(File(ctx.filesDir, HISTORY_FILE), arr.toString())
        } catch (e: Exception) {
        }
    }

    // ---------- Session (file d'attente en cours) ----------

    fun saveSession(ctx: Context, s: Session?) {
        val f = File(ctx.filesDir, SESSION_FILE)
        try {
            if (s == null || s.items.isEmpty()) {
                f.delete()
                return
            }
            val o = JSONObject()
            o.put("destination", s.destination.toString())
            o.put("mode", s.mode.name)
            o.put("savedAt", s.savedAt)
            val arr = JSONArray()
            for (i in s.items) {
                val x = JSONObject()
                x.put("id", i.id)
                x.put("src", i.src.toString())
                x.put("name", i.name)
                x.put("size", i.size)
                x.put("rel", JSONArray(i.rel))
                val parent = i.srcParent
                if (parent != null) x.put("srcParent", parent.toString())
                arr.put(x)
            }
            o.put("items", arr)
            val folders = JSONArray()
            for (d in s.folders) {
                val x = JSONObject()
                x.put("tree", d.tree.toString())
                x.put("docId", d.docId)
                x.put("rel", JSONArray(d.rel))
                folders.put(x)
            }
            o.put("folders", folders)
            writeAtomic(f, o.toString())
        } catch (e: Exception) {
        }
    }

    fun loadSession(ctx: Context): Session? = try {
        val f = File(ctx.filesDir, SESSION_FILE)
        if (!f.exists()) {
            null
        } else {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("items")
            val items = (0 until arr.length()).map { k ->
                val x = arr.getJSONObject(k)
                SessionItem(
                    id = x.getLong("id"),
                    src = Uri.parse(x.getString("src")),
                    name = x.getString("name"),
                    size = x.optLong("size"),
                    rel = strings(x.optJSONArray("rel")),
                    srcParent = if (x.has("srcParent")) Uri.parse(x.getString("srcParent")) else null
                )
            }
            val fa = o.optJSONArray("folders")
            val folders = if (fa == null) {
                emptyList()
            } else {
                (0 until fa.length()).map { k ->
                    val x = fa.getJSONObject(k)
                    SessionFolder(Uri.parse(x.getString("tree")), x.getString("docId"), strings(x.optJSONArray("rel")))
                }
            }
            if (items.isEmpty()) {
                null
            } else {
                Session(
                    destination = Uri.parse(o.getString("destination")),
                    mode = modeOf(o.optString("mode", "COPY")),
                    savedAt = o.optLong("savedAt"),
                    items = items,
                    folders = folders
                )
            }
        }
    } catch (e: Exception) {
        null
    }
}
