package com.master.allvideodownloader

import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val net = Executors.newCachedThreadPool()
    private val queue = Executors.newSingleThreadExecutor() // downloads run one after another
    private val ui = Handler(Looper.getMainLooper())
    private val ready = CountDownLatch(1)
    @Volatile private var initOk = false
    private var currentTitle = ""
    private lateinit var prefs: SharedPreferences
    private lateinit var urlBox: EditText
    private lateinit var status: TextView
    private lateinit var vtitle: TextView
    private lateinit var thumb: ImageView
    private lateinit var quality: Spinner
    private lateinit var btnDownload: Button
    private lateinit var queueList: LinearLayout
    private lateinit var history: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("mavd", MODE_PRIVATE)
        AppCompatDelegate.setDefaultNightMode(prefs.getInt("night", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        urlBox = findViewById(R.id.url)
        status = findViewById(R.id.status)
        vtitle = findViewById(R.id.vtitle)
        thumb = findViewById(R.id.thumb)
        quality = findViewById(R.id.quality)
        btnDownload = findViewById(R.id.btnDownload)
        queueList = findViewById(R.id.queueList)
        history = findViewById(R.id.history)
        btnDownload.isEnabled = false
        showHistory()

        findViewById<Button>(R.id.btnAnalyze).setOnClickListener { analyze() }
        btnDownload.setOnClickListener { enqueue() }
        findViewById<Button>(R.id.btnLang).setOnClickListener {
            val isBn = resources.configuration.locales[0].language == "bn"
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(if (isBn) "en" else "bn"))
        }
        findViewById<Button>(R.id.btnTheme).setOnClickListener {
            val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val mode = if (night) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
            prefs.edit().putInt("night", mode).apply()
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        status.text = getString(R.string.preparing)
        net.execute {
            try {
                YoutubeDL.getInstance().init(application)
                FFmpeg.getInstance().init(application)
                initOk = true
                ui.post { status.text = getString(R.string.ready) }
            } catch (e: Exception) {
                ui.post { status.text = getString(R.string.err_init) + ": " + (e.message ?: "").take(200) }
            } finally {
                ready.countDown()
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val link = Regex("https?://\\S+").find(text)?.value ?: return
        urlBox.setText(link)
        analyze()
    }

    private fun analyze() {
        val link = urlBox.text.toString().trim()
        if (!link.startsWith("http")) { status.text = getString(R.string.invalid_link); return }
        status.text = getString(R.string.analyzing)
        btnDownload.isEnabled = false
        net.execute {
            ready.await()
            if (!initOk) return@execute
            try {
                val info = YoutubeDL.getInstance().getInfo(link)
                val bmp = info.thumbnail?.let {
                    try { BitmapFactory.decodeStream(URL(it).openStream()) } catch (e: Exception) { null }
                }
                ui.post {
                    currentTitle = info.title ?: link
                    vtitle.text = currentTitle
                    if (bmp != null) { thumb.setImageBitmap(bmp); thumb.visibility = View.VISIBLE }
                    status.text = getString(R.string.ready)
                    btnDownload.isEnabled = true
                }
            } catch (e: Exception) {
                ui.post { status.text = getString(R.string.err_generic) + ": " + (e.message ?: "").take(200) }
            }
        }
    }

    private fun enqueue() {
        val link = urlBox.text.toString().trim()
        val mode = quality.selectedItemPosition
        val name = currentTitle.ifEmpty { link }
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12, 0, 12) }
        val label = TextView(this).apply { text = name + " — " + getString(R.string.waiting) }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        row.addView(label); row.addView(bar); queueList.addView(row)
        queue.execute {
            ready.await()
            if (!initOk) { ui.post { label.text = name + " — " + getString(R.string.err_init) }; return@execute }
            ui.post { label.text = name }
            try {
                val dir = File(getExternalFilesDir(null), "dl").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val req = YoutubeDLRequest(link)
                req.addOption("-o", dir.absolutePath + "/%(title).80s.%(ext)s")
                req.addOption("--no-playlist")
                when (mode) {
                    3 -> { req.addOption("-x"); req.addOption("--audio-format", "mp3") }
                    1 -> { req.addOption("-f", "bv*[height<=720]+ba/b[height<=720]/b"); req.addOption("--merge-output-format", "mp4") }
                    2 -> { req.addOption("-f", "bv*[height<=480]+ba/b[height<=480]/b"); req.addOption("--merge-output-format", "mp4") }
                    else -> { req.addOption("-f", "bv*+ba/b"); req.addOption("--merge-output-format", "mp4") }
                }
                YoutubeDL.getInstance().execute(req, UUID.randomUUID().toString()) { p, _, _ ->
                    ui.post { bar.progress = p.toInt() }
                }
                val out = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.maxByOrNull { it.lastModified() }
                    ?: throw IllegalStateException("No file")
                val where = publish(out)
                addHistory(name)
                ui.post { bar.progress = 100; label.text = name + "\n" + getString(R.string.saved_to) + " " + where }
            } catch (e: Exception) {
                ui.post { label.text = name + "\n" + getString(R.string.err_generic) + ": " + (e.message ?: "").take(200) }
            }
        }
    }

    private fun publish(f: File): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val folder = "Download/MASTER ALL VIDEO DOWNLOADER"
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.RELATIVE_PATH, folder)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw IllegalStateException("Cannot save")
            contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            f.delete()
            return folder + "/" + f.name
        }
        return f.absolutePath
    }

    private fun addHistory(name: String) {
        val d = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val old = prefs.getString("hist", "") ?: ""
        val all = (d + "  " + name + "\n" + old).lines().filter { it.isNotBlank() }.take(50).joinToString("\n")
        prefs.edit().putString("hist", all).apply()
        ui.post { showHistory() }
    }

    private fun showHistory() {
        val h = prefs.getString("hist", "") ?: ""
        history.text = if (h.isEmpty()) getString(R.string.no_history) else h
    }
}
