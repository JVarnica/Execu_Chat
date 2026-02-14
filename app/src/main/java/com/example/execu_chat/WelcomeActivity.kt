package com.example.execu_chat

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class WelcomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.welcome_menu)

        findViewById<Button>(R.id.LocalChatBtn).setOnClickListener {
            startActivity(Intent(this, LocalChatActivity::class.java))
        }

        findViewById<Button>(R.id.CloudChatBtn).setOnClickListener {
            startActivity(Intent(this, CloudChatActivity::class.java))
        }
    }
}