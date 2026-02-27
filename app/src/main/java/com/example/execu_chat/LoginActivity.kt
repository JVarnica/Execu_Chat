package com.example.execu_chat


import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// hit login and register endpoints, on success stores jwt token in TokenManager and launches
// CloudMainActivity.

class LoginActivity : AppCompatActivity() {

    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginBtn: Button
    private lateinit var registerBtn: Button
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var healthDot: View

    private lateinit var gateway: GatewayClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (TokenManager.isLoggedIn(this)) {
            startActivity(Intent(this, CloudChatActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.login_menu)

        gateway = GatewayClient(this, ServerConfig.GATEWAY_URL)

        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        loginBtn      = findViewById(R.id.loginBtn)
        registerBtn   = findViewById(R.id.registerBtn)
        statusText    = findViewById(R.id.statusText)
        progress      = findViewById(R.id.loginProgress)
        healthDot     = findViewById(R.id.healthDot)

        // Check server health on load
        lifecycleScope.launch {
            healthDot.setBackgroundResource(
                if (gateway.isHealthy()) R.drawable.dot_green else R.drawable.dot_red
            )
        }

        findViewById<Button>(R.id.loginBtn).setOnClickListener {
            val user = usernameInput.text.toString().trim()
            val pass = passwordInput.text.toString().trim()
            if (user.isBlank() || pass.isBlank()) {
                statusText.text = "Enter username and password"
                return@setOnClickListener
            }

            progress.visibility = View.VISIBLE
            lifecycleScope.launch {
                gateway.login(user, pass)
                    .onSuccess { tokens ->
                        TokenManager.save(this@LoginActivity, tokens.accessToken, tokens.refreshToken)
                        startActivity(Intent(this@LoginActivity, CloudChatActivity::class.java))
                        finish()
                    }
                    .onFailure { statusText.text = it.message }
                progress.visibility = View.GONE
            }
        }

        findViewById<Button>(R.id.registerBtn).setOnClickListener {
            val user = usernameInput.text.toString().trim()
            val pass = passwordInput.text.toString().trim()
            if (user.isBlank() || pass.isBlank()) {
                statusText.text = "Enter username and password"
                return@setOnClickListener
            }

            progress.visibility = View.VISIBLE
            lifecycleScope.launch {
                gateway.register(user, pass)
                    .onSuccess { statusText.text = "Registered! Now log in." }
                    .onFailure { statusText.text = it.message }
                progress.visibility = View.GONE
            }
        }
    }
}
