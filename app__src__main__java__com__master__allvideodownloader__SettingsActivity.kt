package com.master.allvideodownloader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar

class SettingsActivity : AppCompatActivity() {
    private val vqs = intArrayOf(0, 2160, 1440, 1080, 720, 480, 360, 240, 144)
    private val aqs = intArrayOf(0, 320, 256, 192, 128, 96, 64)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        build()
    }

    private fun row(title: String, value: String?, click: (() -> Unit)?): View {
        val v = LayoutInflater.from(this).inflate(R.layout.item_setting, null)
        v.findViewById<TextView>(R.id.sTitle).text = title
        val sv = v.findViewById<TextView>(R.id.sValue)
        if (value.isNullOrEmpty()) sv.visibility = View.GONE else sv.text = value
        v.findViewById<View>(R.id.sSwitch).visibility = View.GONE
        if (click != null) v.setOnClickListener { click() }
        return v
    }

    private fun switchRow(title: String, on: Boolean, set: (Boolean) -> Unit): View {
        val v = row(title, null, null)
        val s = v.findViewById<MaterialSwitch>(R.id.sSwitch)
        s.visibility = View.VISIBLE; s.isChecked = on
        s.setOnCheckedChangeListener { _, c -> set(c) }
        v.setOnClickListener { s.toggle() }
        return v
    }

    private fun choose(title: String, items: Array<String>, sel: Int, pick: (Int) -> Unit) {
        MaterialAlertDialogBuilder(this).setTitle(title)
            .setSingleChoiceItems(items, sel) { d, w -> pick(w); d.dismiss() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun build() {
        val l = findViewById<LinearLayout>(R.id.list)
        l.removeAllViews()
        val themes = arrayOf(getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark))
        val langs = arrayOf(getString(R.string.lang_system), "English", "বাংলা")
        val vItems = vqs.map { U.qLabel(this, it) }.toTypedArray()
        val aItems = aqs.map { if (it == 0) getString(R.string.best_quality) else "$it kbps" }.toTypedArray()

        l.addView(row(getString(R.string.set_theme), themes[Prefs.theme]) {
            choose(getString(R.string.set_theme), themes, Prefs.theme) { Prefs.theme = it; Prefs.applyTheme(); build() }
        })
        l.addView(row(getString(R.string.set_language), langs[Prefs.lang]) {
            choose(getString(R.string.set_language), langs, Prefs.lang) {
                Prefs.lang = it
                AppCompatDelegate.setApplicationLocales(when (it) {
                    1 -> LocaleListCompat.forLanguageTags("en"); 2 -> LocaleListCompat.forLanguageTags("bn")
                    else -> LocaleListCompat.getEmptyLocaleList()
                })
            }
        })
        l.addView(row(getString(R.string.set_vq), U.qLabel(this, Prefs.defV)) {
            choose(getString(R.string.set_vq), vItems, vqs.indexOf(Prefs.defV).coerceAtLeast(0)) { Prefs.defV = vqs[it]; build() }
        })
        l.addView(row(getString(R.string.set_aq), aItems[aqs.indexOf(Prefs.defA).coerceAtLeast(0)]) {
            choose(getString(R.string.set_aq), aItems, aqs.indexOf(Prefs.defA).coerceAtLeast(0)) { Prefs.defA = aqs[it]; build() }
        })
        l.addView(row(getString(R.string.set_folder), "Download/" + Prefs.folder) {
            val box = EditText(this).apply { setText(Prefs.folder); setSingleLine() }
            val wrap = FrameLayout(this).apply { setPadding(56, 16, 56, 0); addView(box) }
            MaterialAlertDialogBuilder(this).setTitle(R.string.set_folder).setView(wrap)
                .setPositiveButton(R.string.ok) { _, _ -> Prefs.folder = box.text.toString(); build() }
                .setNegativeButton(R.string.cancel, null).show()
        })
        l.addView(switchRow(getString(R.string.set_wifi), Prefs.wifiOnly) { Prefs.wifiOnly = it; Engine.schedule() })
        l.addView(row(getString(R.string.set_limit), Prefs.maxPar.toString()) {
            choose(getString(R.string.set_limit), arrayOf("1", "2", "3", "4", "5"), Prefs.maxPar - 1) { Prefs.maxPar = it + 1; Engine.schedule(); build() }
        })
        l.addView(switchRow(getString(R.string.set_retry), Prefs.autoRetry) { Prefs.autoRetry = it })
        l.addView(row(getString(R.string.set_cache), null) {
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
            Snackbar.make(l, R.string.cache_cleared, Snackbar.LENGTH_SHORT).show()
        })
        l.addView(row(getString(R.string.set_storage), null) { storage() })
        l.addView(row(getString(R.string.set_about), null) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.app_name).setMessage(R.string.about_text)
                .setPositiveButton(R.string.ok, null).show()
        })
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        l.addView(row(getString(R.string.set_version), ver, null))
    }

    private fun storage() {
        val tmp = U.dirSize(java.io.File(getExternalFilesDir(null), "dl"))
        val cache = U.dirSize(cacheDir) + (externalCacheDir?.let { U.dirSize(it) } ?: 0L)
        MaterialAlertDialogBuilder(this).setTitle(R.string.set_storage)
            .setMessage(getString(R.string.storage_info, U.size(tmp).ifEmpty { "0 B" }, U.size(cache).ifEmpty { "0 B" }, "Download/" + Prefs.folder))
            .setPositiveButton(R.string.delete_incomplete) { _, _ ->
                val n = Engine.cleanTemp()
                Snackbar.make(findViewById(R.id.list), getString(R.string.freed, U.size(n).ifEmpty { "0 B" }), Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }
}
