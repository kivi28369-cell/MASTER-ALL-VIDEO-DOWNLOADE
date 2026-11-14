package com.master.allvideodownloader

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Used for both the Downloads screen and the History screen. */
class TasksFragment : Fragment(R.layout.fragment_tasks) {
    companion object {
        fun make(hist: Boolean) = TasksFragment().apply { arguments = Bundle().apply { putBoolean("hist", hist) } }
    }

    private val hist get() = arguments?.getBoolean("hist") ?: false
    private lateinit var adapter: TaskAdapter
    private lateinit var empty: TextView
    private val cb: () -> Unit = { refresh() }

    private fun rank(s: St) = when (s) {
        St.DOWNLOADING, St.MERGING, St.FETCHING -> 0; St.WAITING -> 1; St.PAUSED -> 2
        St.FAILED, St.CANCELLED -> 3; St.COMPLETED -> 4
    }

    override fun onViewCreated(v: View, b: Bundle?) {
        v.findViewById<TextView>(R.id.heading).text = getString(if (hist) R.string.tab_history else R.string.tab_downloads)
        empty = v.findViewById(R.id.empty)
        empty.text = getString(if (hist) R.string.history_empty else R.string.downloads_empty)
        adapter = TaskAdapter(hist) { t, a -> Acts.handle(requireContext(), t, a, hist) }
        v.findViewById<RecyclerView>(R.id.list).apply { layoutManager = LinearLayoutManager(context); adapter = this@TasksFragment.adapter }
        val clear = v.findViewById<MaterialButton>(R.id.btnClearAll)
        clear.visibility = if (hist) View.VISIBLE else View.GONE
        clear.setOnClickListener { confirmClear() }
    }

    private fun confirmClear() {
        if (Store.history.isEmpty()) return
        var alsoFiles = false
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.clear_confirm_title)
            .setMultiChoiceItems(arrayOf(getString(R.string.clear_also_files)), booleanArrayOf(false)) { _, _, on -> alsoFiles = on }
            .setPositiveButton(R.string.history_clear) { _, _ -> Engine.clearHistory(alsoFiles) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    override fun onStart() { super.onStart(); Engine.listeners.add(cb); refresh() }
    override fun onStop() { Engine.listeners.remove(cb); super.onStop() }

    private fun refresh() {
        if (view == null) return
        val l = if (hist) Store.history.sortedByDescending { it.finished }.map { it.copy() }
        else Store.tasks.sortedWith(compareBy<Task>({ rank(it.status) }, { if (it.status == St.COMPLETED) -it.finished else it.created })).map { it.copy() }
        adapter.submitList(l)
        empty.visibility = if (l.isEmpty()) View.VISIBLE else View.GONE
    }
}
