package com.master.allvideodownloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TaskAdapter(private val hist: Boolean, private val onAct: (Task, Int) -> Unit) :
    ListAdapter<Task, TaskAdapter.VH>(object : DiffUtil.ItemCallback<Task>() {
        override fun areItemsTheSame(a: Task, b: Task) = a.id == b.id
        override fun areContentsTheSame(a: Task, b: Task) = a == b
    }) {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val thumb: ImageView = v.findViewById(R.id.thumb)
        val title: TextView = v.findViewById(R.id.title)
        val meta: TextView = v.findViewById(R.id.meta)
        val status: TextView = v.findViewById(R.id.status)
        val sizes: TextView = v.findViewById(R.id.sizes)
        val bar: LinearProgressIndicator = v.findViewById(R.id.bar)
        val bPause: ImageButton = v.findViewById(R.id.bPause)
        val bCancel: ImageButton = v.findViewById(R.id.bCancel)
        val bRetry: ImageButton = v.findViewById(R.id.bRetry)
        val bOpen: ImageButton = v.findViewById(R.id.bOpen)
        val bShare: ImageButton = v.findViewById(R.id.bShare)
        val bFolder: ImageButton = v.findViewById(R.id.bFolder)
        val bDelete: ImageButton = v.findViewById(R.id.bDelete)
    }

    override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_task, p, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val t = getItem(pos); val c = h.itemView.context
        Img.load(t.thumb, h.thumb)
        h.title.text = t.title.ifEmpty { t.url }
        h.meta.text = listOf(t.site, t.format.uppercase(), t.label).filter { it.isNotEmpty() }.joinToString(" · ")

        val st = when (t.status) {
            St.WAITING -> t.message.ifEmpty { c.getString(R.string.status_waiting) }
            St.FETCHING -> c.getString(R.string.status_fetching)
            St.DOWNLOADING -> listOf(c.getString(R.string.status_downloading), "${t.progress}%", t.speed,
                if (t.eta.isNotEmpty()) "ETA " + t.eta else "").filter { it.isNotEmpty() }.joinToString(" · ")
            St.MERGING -> c.getString(R.string.status_merging)
            St.PAUSED -> c.getString(R.string.status_paused) + if (t.message.isNotEmpty()) " · " + t.message else ""
            St.COMPLETED -> c.getString(R.string.status_completed)
            St.CANCELLED -> c.getString(R.string.status_cancelled)
            St.FAILED -> c.getString(R.string.status_failed) + if (t.message.isNotEmpty()) ": " + t.message else ""
        }
        h.status.text = st

        val live = t.status == St.DOWNLOADING || t.status == St.MERGING || t.status == St.FETCHING
        val showBar = live || t.status == St.PAUSED || (t.status == St.WAITING && t.progress > 0)
        if (t.status == St.MERGING || t.status == St.FETCHING) {
            h.bar.isIndeterminate = true
        } else {
            h.bar.isIndeterminate = false
            h.bar.setProgressCompat(t.progress, false)
        }
        h.bar.visibility = if (showBar && !hist) View.VISIBLE else View.GONE

        h.sizes.text = if (hist || t.status == St.COMPLETED) {
            val d = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(t.finished))
            listOf(d, U.size(t.size)).filter { it.isNotEmpty() }.joinToString(" · ")
        } else if (t.total > 0) U.size(t.done) + " / ~" + U.size(t.total) else if (t.done > 0) U.size(t.done) else ""
        h.sizes.visibility = if (h.sizes.text.isEmpty()) View.GONE else View.VISIBLE

        val done = t.status == St.COMPLETED
        val bad = t.status == St.FAILED || t.status == St.CANCELLED
        val active = t.status == St.WAITING || live || t.status == St.PAUSED
        fun vis(b: View, on: Boolean) { b.visibility = if (on) View.VISIBLE else View.GONE }
        if (hist) {
            vis(h.bPause, false); vis(h.bCancel, false); vis(h.bFolder, false)
            vis(h.bOpen, done); vis(h.bShare, done); vis(h.bRetry, true); vis(h.bDelete, true)
        } else {
            vis(h.bPause, t.status == St.DOWNLOADING || t.status == St.PAUSED)
            h.bPause.setImageResource(if (t.status == St.PAUSED) R.drawable.ic_play else R.drawable.ic_pause)
            h.bPause.contentDescription = c.getString(if (t.status == St.PAUSED) R.string.btn_resume else R.string.btn_pause)
            vis(h.bCancel, active)
            vis(h.bRetry, bad)
            vis(h.bOpen, done); vis(h.bShare, done); vis(h.bFolder, done)
            vis(h.bDelete, done || bad)
        }
        h.bOpen.setImageResource(if (t.audioOnly) R.drawable.ic_play else R.drawable.ic_open)
        h.bOpen.contentDescription = c.getString(if (t.audioOnly) R.string.btn_play else R.string.btn_open)

        h.bPause.setOnClickListener { onAct(t, Acts.PAUSE) }
        h.bCancel.setOnClickListener { onAct(t, Acts.CANCEL) }
        h.bRetry.setOnClickListener { onAct(t, Acts.RETRY) }
        h.bOpen.setOnClickListener { onAct(t, Acts.OPEN) }
        h.bShare.setOnClickListener { onAct(t, Acts.SHARE) }
        h.bFolder.setOnClickListener { onAct(t, Acts.FOLDER) }
        h.bDelete.setOnClickListener { onAct(t, Acts.DELETE) }
        h.itemView.setOnClickListener { if (done) onAct(t, Acts.OPEN) }
    }
}
