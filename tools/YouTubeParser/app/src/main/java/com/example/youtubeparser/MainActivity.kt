package com.example.youtubeparser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 60)
        }

        val textView = TextView(this).apply {
            text = "YouTube Parser\n\n업로드 서버 주소와 접근성 설정을 관리합니다."
            textSize = 18f
        }

        val endpointInput = EditText(this).apply {
            hint = "예: 100.95.209.72"
            setText(UploadEndpointStore.getRawInput(this@MainActivity))
        }

        val saveButton = Button(this).apply {
            text = "서버 주소 저장"
            setOnClickListener {
                UploadEndpointStore.saveRawInput(
                    this@MainActivity,
                    endpointInput.text?.toString().orEmpty()
                )
                Toast.makeText(this@MainActivity, "서버 주소 저장 완료", Toast.LENGTH_SHORT).show()
            }
        }

        val button = Button(this).apply {
            text = "접근성 설정 열기"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        layout.addView(textView)
        layout.addView(endpointInput)
        layout.addView(saveButton)
        layout.addView(button)

        setContentView(layout)
    }
}
