package com.chronowear.wear.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.chronowear.wear.data.WatchAutonomousRepository

class WearMainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repo = WatchAutonomousRepository.getInstance(this)
        val nowMs = System.currentTimeMillis()
        val upcomingThree = repo.getNextThreeUpcomingEvents(nowMs)

        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 48, 32, 48)
        }

        val header = TextView(this).apply {
            text = "ChronoWear (${repo.getHorizonDays()}d Cache)"
            textSize = 15f
            gravity = Gravity.CENTER
        }
        container.addView(header)

        val first = upcomingThree.firstOrNull()
        val countdownView = TextView(this).apply {
            text = if (first != null) {
                "Faltam ${first.formatRemaining(nowMs)} (Início ${first.formatStartHHMM()})\nBarra configurada: ${repo.getProgressWindowMinutes()}m antes"
            } else {
                "Nenhum evento futuro no cache autônomo"
            }
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 20)
        }
        container.addView(countdownView)

        upcomingThree.forEachIndexed { idx, ev ->
            val itemView = TextView(this).apply {
                text = "${idx + 1}. ${ev.formatStartHHMM()} — ${ev.title}"
                textSize = 13f
                setPadding(0, 6, 0, 6)
            }
            container.addView(itemView)
        }

        scrollView.addView(container)
        setContentView(scrollView)
    }
}
