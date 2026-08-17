package com.gkeyes.uclone.crossuserprobe

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        setContentView(ScrollView(this).apply { addView(content) })
        render()
    }

    private fun render() {
        content.removeAllViews()
        content.addView(title("UClone CrossUser Probe"))
        content.addView(button("写入 CE marker") {
            ProbeIdentity.writeMarker(this, StorageKind.CE)
            render()
        })
        content.addView(button("写入 DE marker") {
            ProbeIdentity.writeMarker(this, StorageKind.DE)
            render()
        })
        ProbeIdentity.collect(this).forEach { (key, value) ->
            content.addView(row(key, value))
        }
    }

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 22f
        gravity = Gravity.START
        setPadding(0, 0, 0, 24)
    }

    private fun button(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { action() }
    }

    private fun row(key: String, value: String): TextView = TextView(this).apply {
        text = "$key: $value"
        textSize = 14f
        setPadding(0, 8, 0, 8)
    }
}
