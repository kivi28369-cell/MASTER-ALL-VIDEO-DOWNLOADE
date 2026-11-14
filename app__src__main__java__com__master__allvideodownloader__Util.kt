package com.master.allvideodownloader

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

object U {
    fun size(b: Long): String {
        if (b <= 0) return ""
        val u = arrayOf("B", "KB", "MB", "GB")
        var v = b.toDouble(); var i = 0
        while (v >= 1024 && i < 3) { v /= 1024; i++ }
        return if (i == 0) "$b B" else String.format(Locale.US, "%.1f %s", v, u[i])
    }

    fun eta(s: Long): String {
        if (s <= 0) return ""
        val m = s / 60; val h = m / 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m % 60, s % 60) else String.format(Locale.US, "%02d:%02d", m, s % 60)
    }

    fun qLabel(c: Context, h: Int): String = if (h == 0) c.getString(R.string.best_quality) else when (h) {
        720 -> "720p HD"; 1080 -> "1080p Full HD"; 1440 -> "1440p 2K"; 2160 -> "2160p 4K"; else -> "${h}p"
    }

    fun bytes(n: String, u: String): Long {
        val v = n.toDoubleOrNull() ?: return 0L
        val m = when (u.firstOrNull()) { 'K' -> 1024.0; 'M' -> 1048576.0; 'G' -> 1073741824.0; else -> 1.0 }
        return (v * m).toLong()
    }

    fun host(u: String): String = try { Uri.parse(u).host?.removePrefix("www.") ?: u } catch (e: Exception) { u }

    fun mime(ext: String): String = when (ext.lowercase()) {
        "mp4" -> "video/mp4"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "opus", "ogg" -> "audio/ogg"; else -> "*/*"
    }

    fun urls(text: String): List<String> =
        Regex("https?://[^\\s]+").findAll(text).map { it.value.trimEnd(',', ')', '.', '"', '\'') }.distinct().toList()

    fun toast(c: Context, msg: String) = Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()

    fun dirSize(f: File): Long = if (f.isFile) f.length() else (f.listFiles()?.sumOf { dirSize(it) } ?: 0L)

    fun exists(c: Context, uri: String): Boolean = try {
        c.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    } catch (e: Exception) { false }

    fun deleteUri(c: Context, uri: String) { try { c.contentResolver.delete(Uri.parse(uri), null, null) } catch (e: Exception) { } }

    fun open(c: Context, t: Task) {
        if (t.uri.isEmpty() || !exists(c, t.uri)) { toast(c, c.getString(R.string.file_missing)); return }
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(t.uri), mime(t.format)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { c.startActivity(i) } catch (e: ActivityNotFoundException) { toast(c, c.getString(R.string.no_app)) }
    }

    fun share(c: Context, t: Task) {
        if (t.uri.isEmpty() || !exists(c, t.uri)) { toast(c, c.getString(R.string.file_missing)); return }
        val i = Intent(Intent.ACTION_SEND).setType(mime(t.format)).putExtra(Intent.EXTRA_STREAM, Uri.parse(t.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        c.startActivity(Intent.createChooser(i, t.title))
    }

    fun folder(c: Context) {
        try { c.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { toast(c, c.getString(R.string.no_app)) }
    }
}

object Acts {
    const val PAUSE = 1; const val CANCEL = 2; const val RETRY = 3; const val OPEN = 4
    const val SHARE = 5; const val FOLDER = 6; const val DELETE = 7

    fun handle(c: Context, t: Task, a: Int, hist: Boolean) {
        when (a) {
            PAUSE -> if (t.status == St.PAUSED) Engine.resume(t.id) else Engine.pause(t.id)
            CANCEL -> Engine.cancel(t.id)
            RETRY -> if (hist) redownload(c, t) else Engine.retry(t.id)
            OPEN -> U.open(c, t)
            SHARE -> U.share(c, t)
            FOLDER -> U.folder(c)
            DELETE -> when {
                hist -> Engine.deleteHistory(t.id)
                t.status == St.COMPLETED -> MaterialAlertDialogBuilder(c).setTitle(R.string.delete_title).setMessage(R.string.delete_msg)
                    .setPositiveButton(R.string.btn_delete) { _, _ -> Engine.remove(t.id, true) }
                    .setNegativeButton(R.string.cancel, null).show()
                else -> Engine.remove(t.id, false)
            }
        }
    }

    private fun redownload(c: Context, o: Task) {
        val n = Task(id = UUID.randomUUID().toString(), url = o.url, title = o.title, thumb = o.thumb, site = o.site,
            audioOnly = o.audioOnly, height = o.height, kbps = o.kbps, label = o.label, format = o.format)
        U.toast(c, c.getString(if (Engine.add(n)) R.string.added else R.string.dup))
    }
}

object Img {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String, v: ImageView) {
        v.tag = url
        v.setImageDrawable(null)
        if (url.isEmpty()) return
        cache.get(url)?.let { v.setImageBitmap(it); return }
        pool.execute {
            try {
                val bytes = URL(url).openStream().use { it.readBytes() }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1
                while (o.outWidth / (s * 2) >= 360) s *= 2
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
                if (bmp != null) { cache.put(url, bmp); main.post { if (v.tag == url) v.setImageBitmap(bmp) } }
            } catch (e: Exception) { }
        }
    }
}
