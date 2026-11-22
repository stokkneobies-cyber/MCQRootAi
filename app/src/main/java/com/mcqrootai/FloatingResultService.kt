
package com.mcqrootai

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.Typeface
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView

class FloatingResultService : Service() {

    companion object {
        const val ACTION_UPDATE =
            "com.mcqrootai.ACTION_UPDATE_RESULT"

        const val ACTION_CAPTURE =
            "com.mcqrootai.ACTION_CAPTURE_FROM_FLOATING"

        const val EXTRA_RESULT = "result"
    }

    private var windowManager: WindowManager? = null
    private var floatingView: LinearLayout? = null
    private var resultText: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val text = intent?.getStringExtra(EXTRA_RESULT)
            ?: "Siap. Tekan BERI JAWABAN untuk menganalisis layar."

        if (floatingView != null) {
            resultText?.text = text
            return START_NOT_STICKY
        }

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 12, 12)
            setBackgroundColor(Color.rgb(35, 90, 180))
        }

        val title = TextView(this).apply {
            this.text = "MCQ ROOT AI"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }

        val close = TextView(this).apply {
            this.text = "  ✕  "
            setTextColor(Color.WHITE)
            textSize = 18f
            setOnClickListener { stopSelf() }
        }

        header.addView(
            title,
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        header.addView(close)

        val captureButton = TextView(this).apply {
            text = "BERI JAWABAN"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(12, 16, 12, 16)
            setBackgroundColor(Color.rgb(35, 120, 75))

            setOnClickListener {
                resultText?.text = "Mengambil layar dan membaca soal..."

                sendBroadcast(
                    Intent(ACTION_CAPTURE).setPackage(packageName)
                )
            }
        }

        resultText = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(16, 16, 16, 16)
            setTextIsSelectable(true)
        }

        val scroll = ScrollView(this).apply {
            addView(resultText)
        }

        floatingView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(37, 37, 37))
            addView(header)
            addView(captureButton)
            addView(
                scroll,
                LinearLayout.LayoutParams(-1, 0, 1f)
            )
        }

        params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            380,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params?.x =
                        initialX + (event.rawX - initialTouchX).toInt()
                    params?.y =
                        initialY + (event.rawY - initialTouchY).toInt()

                    try {
                        windowManager?.updateViewLayout(
                            floatingView,
                            params
                        )
                    } catch (_: Exception) {
                    }
                    true
                }

                else -> false
            }
        }

        windowManager?.addView(floatingView, params)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        floatingView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }

        floatingView = null
        resultText = null
        windowManager = null
        params = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
