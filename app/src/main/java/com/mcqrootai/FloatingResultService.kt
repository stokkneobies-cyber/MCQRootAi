
package com.mcqrootai

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class FloatingResultService : Service() {

    companion object {
        const val ACTION_UPDATE =
            "com.mcqrootai.ACTION_UPDATE_RESULT"

        const val EXTRA_RESULT = "result"
    }

    private var windowManager: WindowManager? = null
    private var view: TextView? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val text = intent?.getStringExtra(EXTRA_RESULT)
            ?: "Belum ada hasil."

        if (view != null) {
            view?.text = text
            return START_NOT_STICKY
        }

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        val label = TextView(this).apply {
            text = text
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(24, 20, 24, 20)
            setBackgroundColor(0xEE252525.toInt())
            setOnClickListener {
                stopSelf()
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = 100
        }

        view = label
        windowManager?.addView(label, params)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        view?.let {
            windowManager?.removeView(it)
        }

        view = null
        windowManager = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
