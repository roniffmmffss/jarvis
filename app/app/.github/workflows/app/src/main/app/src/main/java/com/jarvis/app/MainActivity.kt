package com.jarvis.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var btn: Button
    private lateinit var keyRow: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("jarvis", Context.MODE_PRIVATE) }
    private var alive = false

    private val tick = object : Runnable {
        override fun run() {
            if (!alive) return
            btn.text = if (JarvisService.running) "⏹ Jarvis বন্ধ করুন" else "▶ Jarvis চালু করুন"
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 60, 24, 24)
        }
        val keyBox = EditText(this).apply {
            hint = "Gemini API key (একবারই দিতে হবে)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val save = Button(this).apply {
            text = "Key সেভ করুন"
            setOnClickListener {
                prefs.edit().putString("key", keyBox.text.toString().trim()).apply()
                keyRow.visibility = View.GONE
                Toast.makeText(this@MainActivity, "সেভ হয়েছে", Toast.LENGTH_SHORT).show()
            }
        }
        keyRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(keyBox)
            addView(save)
        }
        if ((prefs.getString("key", "") ?: "").isNotEmpty()) keyRow.visibility = View.GONE
        btn = Button(this).apply {
            textSize = 18f
            setOnClickListener { toggle() }
        }
        val overlay = Button(this).apply {
            text = "অন্য অ্যাপ খোলার অনুমতি (একবার)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            }
        }
        log = TextView(this).apply { textSize = 16f; setPadding(8, 8, 8, 8) }
        scroll = ScrollView(this).apply { addView(log) }
        root.addView(keyRow)
        root.addView(btn)
        root.addView(overlay)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun toggle() {
        if (JarvisService.running) {
            stopService(Intent(this, JarvisService::class.java))
            return
        }
        if ((prefs.getString("key", "") ?: "").isEmpty()) {
            Toast.makeText(this, "আগে Gemini key সেভ করুন", Toast.LENGTH_LONG).show(); return
        }
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 5); return }
        startForegroundService(Intent(this, JarvisService::class.java))
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            toggle()
        else Toast.makeText(this, "মাইকের অনুমতি লাগবে", Toast.LENGTH_LONG).show()
    }

    override fun onResume() {
        super.onResume()
        alive = true
        synchronized(JarvisService.logText) { log.text = JarvisService.logText.toString() }
        JarvisService.onLog = { s ->
            runOnUiThread {
                log.append("\n$s\n")
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        alive = false
        JarvisService.onLog = null
    }
}
