package com.master.allvideodownloader

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

enum class St { WAITING, FETCHING, DOWNLOADING, MERGING, PAUSED, COMPLETED, FAILED, CANCELLED }

data class Task(
    val id: String,
    val url: String,
    var title: String = "",
    var thumb: String = "",
    var site: String = "",
    val audioOnly: Boolean = false,
    val height: Int = 0,      // 0 = best available
    val kbps: Int = 0,        // 0 = best available (audio only)
    var label: String = "",
    var format: String = "",
    var status: St = St.WAITING,
    var progress: Int = 0,
    var done: Long = 0,
    var total: Long = 0,
    var speed: String = "",
    var eta: String = "",
    var message: String = "",
    var uri: String = "",
    var path: String = "",
    var retries: Int = 0,
    val created: Long = System.currentTimeMillis(),
    var finished: Long = 0,
    var size: Long = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("title", title); put("thumb", thumb); put("site", site)
        put("audioOnly", audioOnly); put("height", height); put("kbps", kbps); put("label", label)
        put("format", format); put("status", status.name); put("progress", progress)
        put("total", total); put("message", message); put("uri", uri); put("path", path)
        put("retries", retries); put("created", created); put("finished", finished); put("size", size)
    }

    companion object {
        fun fromJson(j: JSONObject) = Task(
            id = j.getString("id"), url = j.getString("url"), title = j.optString("title"),
            thumb = j.optString("thumb"), site = j.optString("site"),
            audioOnly = j.optBoolean("audioOnly"), height = j.optInt("height"), kbps = j.optInt("kbps"),
            label = j.optString("label"), format = j.optString("format"),
            status = try { St.valueOf(j.optString("status")) } catch (e: Exception) { St.FAILED },
            progress = j.optInt("progress"), total = j.optLong("total"), message = j.optString("message"),
            uri = j.optString("uri"), path = j.optString("path"), retries = j.optInt("retries"),
            created = j.optLong("created"), finished = j.optLong("finished"), size = j.optLong("size")
        )
    }
}

object Store {
    private lateinit var dir: File
    val tasks = CopyOnWriteArrayList<Task>()
    val history = CopyOnWriteArrayList<Task>()

    fun init(c: Context) {
        dir = c.filesDir
        load("tasks.json", tasks)
        load("history.json", history)
    }

    private fun load(name: String, list: MutableList<Task>) {
        try {
            val f = File(dir, name)
            if (f.exists()) {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) list.add(Task.fromJson(a.getJSONObject(i)))
            }
        } catch (e: Exception) { }
    }

    @Synchronized
    fun save() {
        try {
            val a = JSONArray(); tasks.forEach { a.put(it.toJson()) }
            File(dir, "tasks.json").writeText(a.toString())
            val h = JSONArray(); history.forEach { h.put(it.toJson()) }
            File(dir, "history.json").writeText(h.toString())
        } catch (e: Exception) { }
    }
}

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { sp = c.getSharedPreferences("mavd_settings", Context.MODE_PRIVATE) }

    var theme: Int                      // 0 system, 1 light, 2 dark
        get() = sp.getInt("theme", 0)
        set(v) = sp.edit().putInt("theme", v).apply()
    var lang: Int                       // 0 system, 1 English, 2 Bengali
        get() = sp.getInt("lang", 0)
        set(v) = sp.edit().putInt("lang", v).apply()
    var defV: Int
        get() = sp.getInt("defV", 0)
        set(v) = sp.edit().putInt("defV", v).apply()
    var defA: Int
        get() = sp.getInt("defA", 0)
        set(v) = sp.edit().putInt("defA", v).apply()
    var folder: String
        get() = sp.getString("folder", "MASTER ALL VIDEO DOWNLOADER") ?: "MASTER ALL VIDEO DOWNLOADER"
        set(v) {
            val s = v.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
            sp.edit().putString("folder", if (s.isEmpty()) "MASTER ALL VIDEO DOWNLOADER" else s).apply()
        }
    var wifiOnly: Boolean
        get() = sp.getBoolean("wifi", false)
        set(v) = sp.edit().putBoolean("wifi", v).apply()
    var maxPar: Int
        get() = sp.getInt("maxPar", 2)
        set(v) = sp.edit().putInt("maxPar", v).apply()
    var autoRetry: Boolean
        get() = sp.getBoolean("retry", true)
        set(v) = sp.edit().putBoolean("retry", v).apply()

    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
