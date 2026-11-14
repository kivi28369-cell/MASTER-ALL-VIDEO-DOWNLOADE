package com.master.allvideodownloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.appbar.MaterialToolbar

class MainActivity : AppCompatActivity() {
    private lateinit var nav: BottomNavigationView
    private val badgeCb: () -> Unit = { updateBadge() }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun frag(tag: String): Fragment = supportFragmentManager.findFragmentByTag(tag) ?: when (tag) {
        "home" -> HomeFragment()
        "dl" -> TasksFragment.make(false)
        else -> TasksFragment.make(true)
    }

    private fun show(tag: String) {
        val ft = supportFragmentManager.beginTransaction().setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
        for (t in listOf("home", "dl", "hist")) {
            val f = frag(t)
            if (!f.isAdded) ft.add(R.id.container, f, t)
            if (t == tag) ft.show(f) else ft.hide(f)
        }
        ft.commitNow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val tb = findViewById<MaterialToolbar>(R.id.toolbar)
        tb.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_settings) { startActivity(Intent(this, SettingsActivity::class.java)); true } else false
        }
        nav = findViewById(R.id.nav)
        nav.setOnItemSelectedListener {
            show(when (it.itemId) { R.id.nav_dl -> "dl"; R.id.nav_hist -> "hist"; else -> "home" }); true
        }
        if (savedInstanceState == null) show("home")
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleIntent(intent)
        Engine.schedule()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val link = U.urls(text).firstOrNull() ?: return
        HomeFragment.pending = link
        nav.selectedItemId = R.id.nav_home
    }

    override fun onStart() { super.onStart(); Engine.listeners.add(badgeCb); updateBadge() }
    override fun onStop() { Engine.listeners.remove(badgeCb); super.onStop() }

    private fun updateBadge() {
        val n = Store.tasks.count { it.status == St.WAITING || it.status == St.FETCHING || it.status == St.DOWNLOADING || it.status == St.MERGING }
        if (n > 0) nav.getOrCreateBadge(R.id.nav_dl).number = n else nav.removeBadge(R.id.nav_dl)
    }
}
