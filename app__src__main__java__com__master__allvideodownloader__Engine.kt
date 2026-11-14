package com.master.allvideodownloader

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class UserError(msg: String) : Exception(msg)
private class Stop : Exception()

/** Information found for a pasted link. */
class Info(
    val url: String, val title: String, val thumb: String, val site: String, val duration: Long,
    val videos: List<Pair<Int, Long>>,   // height -> estimated size (0 = unknown)
    val maxAbr: Int
) {
    fun sizeFor(h: Int): Long = if (h == 0) (videos.maxByOrNull { it.first }?.second ?: 0L) else (videos.firstOrNull { it.first == h }?.second ?: 0L)
}

object Engine {
    lateinit var app: Application
    private val ready = CountDownLatch(1)
    @Volatile var initOk = false
    @Volatile var initErr = ""
    @Volatile var serviceUp = false
    private val pool = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private val running = ConcurrentHashMap<String, Boolean>()
    private val pid = ConcurrentHashMap<String, String>()
    private val stopReq = ConcurrentHashMap<String, String>()
    private val hold = ConcurrentHashMap<String, Long>()
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var lastNotify = 0L
    private val ACTIVE = setOf(St.WAITING, St.FETCHING, St.DOWNLOADING, St.MERGING)
    private val rxSize = Regex("of\\s+~?\\s*([\\d.]+)\\s*([KMG]?i?B)")
    private val rxSpeed = Regex("at\\s+([\\d.]+\\s*[KMG]?i?B/s)")

    fun init(a: Application) {
        app = a
        Store.init(a)
        // anything that was running when the app died is paused, not lost
        Store.tasks.forEach {
            if (it.status == St.FETCHING || it.status == St.DOWNLOADING || it.status == St.MERGING) {
                it.status = St.PAUSED; it.message = a.getString(R.string.interrupted); it.speed = ""; it.eta = ""
            }
        }
        Store.save()
        pool.execute {
            try {
                YoutubeDL.getInstance().init(a)
                FFmpeg.getInstance().init(a)
                initOk = true
            } catch (e: Exception) {
                initErr = e.message ?: ""
            } finally { ready.countDown() }
        }
        try {
            val cm = a.getSystemService(ConnectivityManager::class.java)
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { schedule() }
            })
        } catch (e: Exception) { }
    }

    fun notifyChange(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotify < 300) return
        lastNotify = now
        main.post { updateService(); listeners.forEach { it() } }
    }

    private fun updateService() {
        val active = Store.tasks.any { it.status in ACTIVE }
        try {
            if (active && !serviceUp) {
                ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
            } else if (!active && serviceUp) {
                app.stopService(Intent(app, DownloadService::class.java))
            }
        } catch (e: Exception) { }
    }

    private fun set(t: Task, s: St) { t.status = s; Store.save(); notifyChange(true) }
    fun find(id: String): Task? = Store.tasks.firstOrNull { it.id == id }
    private fun workDir(id: String) = File(app.getExternalFilesDir(null), "dl/$id")

    /* ---------------- analyze ---------------- */
    private fun codec(f: JSONObject, k: String): String {
        val s = f.optString(k, "none")
        return if (s.isEmpty() || s == "null") "none" else s
    }

    fun analyze(url: String): Info {
        ready.await()
        if (!initOk) throw UserError(app.getString(R.string.err_init) + ": " + initErr)
        val r = YoutubeDLRequest(url)
        r.addOption("--dump-single-json"); r.addOption("--no-playlist"); r.addOption("--no-warnings")
        val out = YoutubeDL.getInstance().execute(r).out ?: ""
        val line = out.lines().lastOrNull { it.trim().startsWith("{") } ?: throw UserError(app.getString(R.string.err_generic))
        val j = JSONObject(line)
        val vm = TreeMap<Int, Long>()
        var abest = 0L; var maxAbr = 0; var audioOnlyExists = false
        val fs = j.optJSONArray("formats")
        if (fs != null) for (i in 0 until fs.length()) {
            val f = fs.getJSONObject(i)
            val vc = codec(f, "vcodec"); val ac = codec(f, "acodec"); val h = f.optInt("height", 0)
            var sz = f.optLong("filesize", 0); if (sz <= 0) sz = f.optLong("filesize_approx", 0)
            if (vc != "none" && h > 0) vm[h] = maxOf(vm[h] ?: 0L, sz)
            if (vc == "none" && ac != "none") {
                audioOnlyExists = true
                abest = maxOf(abest, sz); maxAbr = maxOf(maxAbr, f.optDouble("abr", 0.0).toInt())
            }
        }
        if (vm.isEmpty() && j.optInt("height", 0) > 0) vm[j.optInt("height")] = maxOf(j.optLong("filesize", 0), j.optLong("filesize_approx", 0))
        val videos = vm.entries.map { Pair(it.key, if (it.value > 0) it.value + (if (audioOnlyExists) abest else 0L) else 0L) }
        val ek = j.optString("extractor_key")
        val site = if (ek.isEmpty() || ek == "Generic") U.host(url) else ek
        val thumb = j.optString("thumbnail").let { if (it == "null") "" else it }
        return Info(url, j.optString("title", url), thumb, site, j.optLong("duration", 0), videos, maxAbr)
    }

    fun friendly(e: Throwable): String {
        if (e is UserError) return e.message ?: ""
        val m = e.message ?: e.toString(); val l = m.lowercase()
        val id = when {
            "unsupported url" in l -> R.string.err_unsupported
            "drm" in l -> R.string.err_drm
            "private video" in l || "sign in" in l || "log in" in l || "login" in l || "members-only" in l || "confirm your age" in l -> R.string.err_private
            "no space left" in l || "enospc" in l -> R.string.err_storage
            "http error 403" in l || "http error 401" in l || "http error 429" in l || "forbidden" in l -> R.string.err_server
            "unable to download" in l || "urlopen error" in l || "getaddrinfo" in l || "timed out" in l || "unreachable" in l || "connection" in l -> R.string.err_network
            "requested format is not available" in l -> R.string.err_format
            "ffmpeg" in l || "postprocess" in l || "merg" in l -> R.string.err_merge
            else -> 0
        }
        return if (id != 0) app.getString(id) else app.getString(R.string.err_generic) + ": " + m.take(160)
    }

    /* ---------------- queue control ---------------- */
    fun add(t: Task): Boolean {
        val dup = Store.tasks.any {
            it.url == t.url && it.audioOnly == t.audioOnly && it.height == t.height && it.kbps == t.kbps &&
                (it.status in ACTIVE || it.status == St.PAUSED)
        }
        if (dup) return false
        Store.tasks.add(t); Store.save(); notifyChange(true); schedule()
        return true
    }

    @Synchronized
    fun schedule() {
        val limit = Prefs.maxPar.coerceIn(1, 5)
        val metered = try { app.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered } catch (e: Exception) { false }
        val blocked = Prefs.wifiOnly && metered
        val wifiMsg = app.getString(R.string.wifi_wait)
        val now = System.currentTimeMillis()
        for (t in Store.tasks) {
            if (t.status != St.WAITING || running.containsKey(t.id)) continue
            if (blocked) { t.message = wifiMsg; continue }
            if (t.message == wifiMsg) t.message = ""
            if (running.size >= limit) break
            if ((hold[t.id] ?: 0L) > now) continue
            running[t.id] = true
            pool.execute { runTask(t) }
        }
        notifyChange(true)
    }

    fun pause(id: String) {
        val t = find(id) ?: return
        if (running.containsKey(id)) {
            stopReq[id] = "pause"
            pid[id]?.let { try { YoutubeDL.getInstance().destroyProcessById(it) } catch (e: Exception) { } }
        } else if (t.status == St.WAITING) { set(t, St.PAUSED) }
    }

    fun resume(id: String) {
        val t = find(id) ?: return
        if (t.status == St.PAUSED) { t.message = ""; set(t, St.WAITING); schedule() }
    }

    fun cancel(id: String) {
        val t = find(id) ?: return
        if (running.containsKey(id)) {
            stopReq[id] = "cancel"
            pid[id]?.let { try { YoutubeDL.getInstance().destroyProcessById(it) } catch (e: Exception) { } }
        } else if (t.status != St.COMPLETED && t.status != St.CANCELLED) {
            workDir(id).deleteRecursively()
            t.speed = ""; t.eta = ""; t.message = ""; t.status = St.CANCELLED; t.finished = System.currentTimeMillis()
            record(t); Store.save(); notifyChange(true); schedule()
        }
    }

    fun retry(id: String) {
        val t = find(id) ?: return
        if (t.status == St.FAILED || t.status == St.CANCELLED) {
            t.message = ""; t.retries = 0; if (t.status == St.CANCELLED) t.progress = 0
            set(t, St.WAITING); schedule()
        }
    }

    fun remove(id: String, deleteFile: Boolean) {
        val t = find(id) ?: return
        if (running.containsKey(id)) cancel(id)
        Store.tasks.remove(t)
        workDir(id).deleteRecursively()
        if (deleteFile && t.uri.isNotEmpty()) U.deleteUri(app, t.uri)
        Store.save(); notifyChange(true)
    }

    fun deleteHistory(id: String) { Store.history.removeAll { it.id == id }; Store.save(); notifyChange(true) }

    fun clearHistory(deleteFiles: Boolean) {
        if (deleteFiles) Store.history.forEach { if (it.uri.isNotEmpty()) U.deleteUri(app, it.uri) }
        Store.history.clear(); Store.save(); notifyChange(true)
    }

    private fun record(t: Task) {
        val c = t.copy()
        Store.history.removeAll { it.id == t.id }
        Store.history.add(0, c)
        while (Store.history.size > 500) Store.history.removeAt(Store.history.size - 1)
    }

    /** Deletes leftover temporary folders. Returns freed bytes. */
    fun cleanTemp(): Long {
        var n = 0L
        File(app.getExternalFilesDir(null), "dl").listFiles()?.forEach { d ->
            val t = find(d.name)
            if (t == null || t.status == St.CANCELLED || t.status == St.COMPLETED) { n += U.dirSize(d); d.deleteRecursively() }
        }
        return n
    }

    /* ---------------- the download itself ---------------- */
    private fun request(t: Task, dir: File): YoutubeDLRequest {
        val r = YoutubeDLRequest(t.url)
        r.addOption("-o", dir.absolutePath + "/%(title).80s.%(ext)s")
        r.addOption("--no-playlist"); r.addOption("--windows-filenames"); r.addOption("--no-mtime")
        r.addOption("--retries", "5"); r.addOption("--fragment-retries", "5")
        if (t.audioOnly) {
            r.addOption("-f", "ba/b"); r.addOption("-x"); r.addOption("--audio-format", "mp3")
            r.addOption("--audio-quality", if (t.kbps == 0) "0" else "${t.kbps}K")
        } else {
            val sel = if (t.height == 0) "bv*+ba/b" else "bv*[height<=${t.height}]+ba/b[height<=${t.height}]/b"
            r.addOption("-f", sel)
            r.addOption("-S", "res,ext:mp4:m4a")
            r.addOption("--merge-output-format", "mp4")
        }
        return r
    }

    private fun runTask(t: Task) {
        var streams = 1; var stage = 0
        try {
            ready.await()
            if (!initOk) throw UserError(app.getString(R.string.err_init) + ": " + initErr)
            if (t.title.isEmpty()) {
                set(t, St.FETCHING)
                val i = analyze(t.url)
                t.title = i.title.ifEmpty { t.url }; t.thumb = i.thumb; t.site = i.site
                if (t.total == 0L) t.total = if (t.audioOnly) 0L else i.sizeFor(t.height)
                if (stopReq.containsKey(t.id)) throw Stop()
            }
            val dir = workDir(t.id).apply { mkdirs() }
            t.message = ""; set(t, St.DOWNLOADING)
            val p = UUID.randomUUID().toString(); pid[t.id] = p
            YoutubeDL.getInstance().execute(request(t, dir), p) { pr, eta, line ->
                if (line.contains("format(s)")) Regex("Downloading (\\d+) format").find(line)?.let { streams = it.groupValues[1].toInt().coerceAtLeast(1) }
                if (line.contains("[download] Destination:")) stage++
                if (line.contains("[Merger]") || line.contains("[ExtractAudio]") || line.contains("[VideoConvertor]") || line.contains("[Fixup")) {
                    if (t.status != St.MERGING) { t.speed = ""; t.eta = ""; t.progress = 99; set(t, St.MERGING) }
                } else if (pr >= 0f) {
                    val s = maxOf(stage, 1)
                    t.progress = (((s - 1) * 100f + pr) / streams).toInt().coerceIn(0, 99)
                    val m = rxSize.find(line)
                    val streamTotal = if (m != null) U.bytes(m.groupValues[1], m.groupValues[2]) else 0L
                    if (t.total > 0) t.done = t.total * t.progress / 100 else t.done = (streamTotal * pr / 100f).toLong()
                    if (t.total == 0L && streamTotal > 0 && streams == 1) t.total = streamTotal
                    t.speed = rxSpeed.find(line)?.groupValues?.get(1)?.replace(" ", "") ?: ""
                    t.eta = U.eta(eta)
                    if (t.status != St.DOWNLOADING) t.status = St.DOWNLOADING
                    notifyChange()
                }
            }
            pid.remove(t.id)
            if (stopReq.containsKey(t.id)) throw Stop()
            val f = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                ?.maxByOrNull { it.lastModified() } ?: throw UserError(app.getString(R.string.err_generic) + ": no output file")
            verify(t, f)
            publish(t, f)
            dir.deleteRecursively()
            t.progress = 100; t.done = t.size; t.total = t.size; t.speed = ""; t.eta = ""; t.message = ""
            t.finished = System.currentTimeMillis(); t.status = St.COMPLETED
            record(t)
        } catch (e: Exception) {
            val req = stopReq[t.id]
            t.speed = ""; t.eta = ""
            if (req == "pause") {
                t.status = St.PAUSED
            } else if (req == "cancel") {
                workDir(t.id).deleteRecursively()
                t.status = St.CANCELLED; t.finished = System.currentTimeMillis(); record(t)
            } else {
                val l = (e.message ?: "").lowercase()
                val retryable = e !is UserError && !("unsupported url" in l || "private" in l || "drm" in l || "sign in" in l)
                t.message = friendly(e)
                if (Prefs.autoRetry && retryable && t.retries < 3) {
                    t.retries++; t.status = St.WAITING
                    hold[t.id] = System.currentTimeMillis() + 5000
                    main.postDelayed({ schedule() }, 5200)
                } else {
                    t.status = St.FAILED; t.finished = System.currentTimeMillis(); record(t)
                }
            }
        } finally {
            running.remove(t.id); pid.remove(t.id); stopReq.remove(t.id)
            Store.save(); notifyChange(true); schedule()
        }
    }

    /** A finished video must contain BOTH a video and an audio track. */
    private fun verify(t: Task, f: File) {
        val ex = MediaExtractor()
        var v = false; var a = false; var readable = true
        try {
            ex.setDataSource(f.absolutePath)
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) v = true
                if (mime.startsWith("audio/")) a = true
            }
        } catch (e: Exception) { readable = false } finally { ex.release() }
        if (!readable) return
        if (!a) throw UserError(app.getString(R.string.err_no_audio))
        if (!t.audioOnly && !v) throw UserError(app.getString(R.string.err_no_video))
    }

    private fun publish(t: Task, f: File) {
        val folder = "Download/" + Prefs.folder
        val cr = app.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, U.mime(f.extension))
            put(MediaStore.Downloads.RELATIVE_PATH, folder)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw UserError(app.getString(R.string.err_storage))
        try {
            cr.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            throw e
        }
        t.uri = uri.toString(); t.path = folder + "/" + f.name; t.size = f.length(); t.format = f.extension.lowercase()
    }
}
