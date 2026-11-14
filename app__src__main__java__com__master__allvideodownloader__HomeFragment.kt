package com.master.allvideodownloader

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import java.util.UUID
import java.util.concurrent.Executors

class HomeFragment : Fragment(R.layout.fragment_home) {
    companion object { @Volatile var pending: String? = null }

    private val ui = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private var token = 0
    private var info: Info? = null
    private lateinit var url: TextInputEditText
    private lateinit var busy: LinearProgressIndicator
    private lateinit var error: TextView
    private lateinit var infoCard: View
    private lateinit var thumb: ImageView
    private lateinit var iTitle: TextView
    private lateinit var iSite: TextView
    private lateinit var cbV: CheckBox
    private lateinit var cbA: CheckBox
    private lateinit var vGroup: ChipGroup
    private lateinit var aGroup: ChipGroup
    private lateinit var vHint: TextView
    private lateinit var aHint: TextView
    private lateinit var mode: TextView
    private lateinit var queueEmpty: TextView
    private lateinit var adapter: TaskAdapter
    private val cb: () -> Unit = { refreshQueue() }
    private val analyzeRun = Runnable { startAnalyze() }

    override fun onViewCreated(v: View, b: Bundle?) {
        url = v.findViewById(R.id.url); busy = v.findViewById(R.id.busy); error = v.findViewById(R.id.error)
        infoCard = v.findViewById(R.id.infoCard); thumb = v.findViewById(R.id.thumb)
        iTitle = v.findViewById(R.id.iTitle); iSite = v.findViewById(R.id.iSite)
        cbV = v.findViewById(R.id.cbVideo); cbA = v.findViewById(R.id.cbAudio)
        vGroup = v.findViewById(R.id.vGroup); aGroup = v.findViewById(R.id.aGroup)
        vHint = v.findViewById(R.id.vHint); aHint = v.findViewById(R.id.aHint)
        mode = v.findViewById(R.id.mode); queueEmpty = v.findViewById(R.id.queueEmpty)

        v.findViewById<MaterialButton>(R.id.btnPaste).setOnClickListener { paste() }
        v.findViewById<MaterialButton>(R.id.btnClear).setOnClickListener { url.setText("") }
        v.findViewById<MaterialButton>(R.id.btnAdd).setOnClickListener { addToQueue() }
        cbV.setOnCheckedChangeListener { _, _ -> updateMode() }
        cbA.setOnCheckedChangeListener { _, _ -> updateMode() }
        url.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { ui.removeCallbacks(analyzeRun); ui.postDelayed(analyzeRun, 700) }
        })
        adapter = TaskAdapter(false) { t, a -> Acts.handle(requireContext(), t, a, false) }
        v.findViewById<RecyclerView>(R.id.queue).apply {
            layoutManager = LinearLayoutManager(context); adapter = this@HomeFragment.adapter; isNestedScrollingEnabled = false
        }
        render()
    }

    override fun onStart() { super.onStart(); Engine.listeners.add(cb); refreshQueue() }
    override fun onStop() { Engine.listeners.remove(cb); super.onStop() }

    override fun onResume() {
        super.onResume()
        pending?.let { pending = null; url.setText(it) }
    }

    private fun paste() {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(requireContext())?.toString()?.trim()
        if (text.isNullOrEmpty()) { U.toast(requireContext(), getString(R.string.clipboard_empty)); return }
        url.setText(text)
    }

    /* ---------- analysis ---------- */
    private fun startAnalyze() {
        if (view == null) return
        val urls = U.urls(url.text.toString())
        error.visibility = View.GONE
        if (urls.size != 1) {
            token++; info = null; busy.visibility = View.GONE; render()
            if (urls.size > 1) { error.text = getString(R.string.multi_detected, urls.size); error.visibility = View.VISIBLE }
            return
        }
        val u = urls[0]
        if (info?.url == u) return
        token++; val my = token
        busy.visibility = View.VISIBLE
        net.execute {
            try {
                val i = Engine.analyze(u)
                ui.post { if (view != null && my == token) { info = i; busy.visibility = View.GONE; render() } }
            } catch (e: Exception) {
                val msg = Engine.friendly(e)
                ui.post { if (view != null && my == token) { info = null; busy.visibility = View.GONE; error.text = msg; error.visibility = View.VISIBLE; render() } }
            }
        }
    }

    /* ---------- rendering ---------- */
    private fun chip(text: String, value: Int, checked: Boolean): Chip = Chip(requireContext()).apply {
        id = View.generateViewId(); this.text = text; tag = value
        isCheckable = true; isChecked = checked; isCheckedIconVisible = true
    }

    private fun render() {
        val i = info
        infoCard.visibility = if (i != null) View.VISIBLE else View.GONE
        vGroup.removeAllViews(); aGroup.removeAllViews()
        vHint.visibility = if (i == null) View.VISIBLE else View.GONE
        aHint.visibility = if (i == null) View.VISIBLE else View.GONE
        if (i != null) {
            iTitle.text = i.title; iSite.text = i.site; Img.load(i.thumb, thumb)
            // video: only qualities the source really offers
            val hs = i.videos.map { it.first }
            val d = Prefs.defV
            val pick = if (d == 0 || hs.isEmpty()) 0 else (hs.filter { it <= d }.maxOrNull() ?: (hs.minOrNull() ?: 0))
            for ((h, s) in i.videos) vGroup.addView(chip(U.qLabel(requireContext(), h) + if (s > 0) " · ~" + U.size(s) else "", h, h == pick))
            vGroup.addView(chip(getString(R.string.best_quality_full), 0, pick == 0))
            // audio: MP3 bitrates up to what the source can give
            val std = listOf(64, 96, 128, 192, 256, 320)
            val av = if (i.maxAbr <= 0) std else std.filter { it <= i.maxAbr * 1.15 }.ifEmpty { listOf(64) }
            val da = Prefs.defA
            val ap = if (da == 0) 0 else (av.filter { it <= da }.maxOrNull() ?: av.first())
            for (k in av) aGroup.addView(chip("$k kbps" + if (i.duration > 0) " · ~" + U.size(k * 125L * i.duration) else "", k, k == ap))
            aGroup.addView(chip(getString(R.string.best_quality_full), 0, ap == 0))
        }
        updateMode()
    }

    private fun selV(): Int = (vGroup.findViewById<Chip>(vGroup.checkedChipId)?.tag as? Int) ?: Prefs.defV
    private fun selA(): Int = (aGroup.findViewById<Chip>(aGroup.checkedChipId)?.tag as? Int) ?: Prefs.defA

    private fun updateMode() {
        mode.setText(when {
            cbV.isChecked && cbA.isChecked -> R.string.mode_both
            cbV.isChecked -> R.string.mode_video
            cbA.isChecked -> R.string.mode_audio
            else -> R.string.mode_none
        })
    }

    private fun refreshQueue() {
        if (view == null) return
        val l = Store.tasks.filter { it.status != St.COMPLETED && it.status != St.FAILED && it.status != St.CANCELLED }.map { it.copy() }
        adapter.submitList(l)
        queueEmpty.visibility = if (l.isEmpty()) View.VISIBLE else View.GONE
    }

    /* ---------- add to queue ---------- */
    private fun make(u: String, audio: Boolean, h: Int, k: Int, i: Info?): Task {
        val c = requireContext()
        val t = Task(id = UUID.randomUUID().toString(), url = u, audioOnly = audio, height = h, kbps = k)
        if (i != null) { t.title = i.title; t.thumb = i.thumb; t.site = i.site }
        t.label = if (audio) "MP3 · " + (if (k == 0) c.getString(R.string.best_quality) else "$k kbps") else U.qLabel(c, h)
        t.format = if (audio) "mp3" else "mp4"
        if (i != null) t.total = if (audio) (if (k == 0) 0L else k * 125L * i.duration) else i.sizeFor(h)
        return t
    }

    private fun addToQueue() {
        val c = requireContext()
        val urls = U.urls(url.text.toString())
        if (urls.isEmpty()) { U.toast(c, getString(R.string.err_invalid_url)); return }
        if (!cbV.isChecked && !cbA.isChecked) { U.toast(c, getString(R.string.mode_none)); return }
        val i = info
        val single = urls.size == 1 && i != null && i.url == urls[0]
        var added = 0; var dup = 0
        for (u in urls) {
            val h = if (single) selV() else Prefs.defV
            val k = if (single) selA() else Prefs.defA
            if (cbV.isChecked) { if (Engine.add(make(u, false, h, k, if (single) i else null))) added++ else dup++ }
            if (cbA.isChecked) { if (Engine.add(make(u, true, h, k, if (single) i else null))) added++ else dup++ }
        }
        // the field is emptied so the next link can be pasted; tasks already queued stay untouched
        url.setText("")
        val msg = if (added > 0) getString(R.string.added_n, added) + if (dup > 0) " · " + getString(R.string.dup) else "" else getString(R.string.dup)
        view?.let { Snackbar.make(it, msg, Snackbar.LENGTH_SHORT).show() }
    }
}
