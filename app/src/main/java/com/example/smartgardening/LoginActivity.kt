package com.example.smartgardening

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import com.example.smartgardening.firebase.FirebaseAuthManager
import com.google.android.material.button.MaterialButton
import android.widget.ImageView
import android.text.InputType

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // Ánh xạ view
        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etPassword = findViewById<EditText>(R.id.etPassword)

        val btnSignIn = findViewById<MaterialButton>(R.id.btnSignIn)
        val btnGoogle = findViewById<MaterialButton>(R.id.btnGoogle)
        val tvSignUp = findViewById<TextView>(R.id.tvSignUp)
        val tvRecovery = findViewById<TextView>(R.id.tvRecovery)
        val btnBack = findViewById<ImageButton>(R.id.btnBack)

        var isPasswordVisible = false
        val ivTogglePassword = findViewById<ImageView>(R.id.ivTogglePassword)

        ivTogglePassword.setOnClickListener {
            if (isPasswordVisible) {
                etPassword.inputType =
                    InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_VARIATION_PASSWORD
                ivTogglePassword.setImageResource(R.drawable.ic_eye_off)
            } else {
                etPassword.inputType =
                    InputType.TYPE_CLASS_TEXT or
                            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                ivTogglePassword.setImageResource(R.drawable.ic_eye_on)
            }

            etPassword.setSelection(etPassword.text.length)
            isPasswordVisible = !isPasswordVisible
        }

        // Đăng nhập
        btnSignIn.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val pass = etPassword.text.toString().trim()

            if (email.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "Vui lòng nhập đầy đủ Email và Mật khẩu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnSignIn.isEnabled = false
            Toast.makeText(this, "Đang đăng nhập...", Toast.LENGTH_SHORT).show()

            FirebaseAuthManager.login(email, pass) { isSuccess, message ->
                btnSignIn.isEnabled = true

                if (isSuccess) {
                    Toast.makeText(this, "Đăng nhập thành công!", Toast.LENGTH_SHORT).show()
                    goToMainActivity()
                } else {
                    Toast.makeText(this, "Lỗi: $message", Toast.LENGTH_LONG).show()
                }
            }
        }

        // 👉 QUÊN MẬT KHẨU
        tvRecovery.setOnClickListener {
            startActivity(Intent(this, ForgotPasswordActivity::class.java))
        }

        // Đăng ký
        tvSignUp.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        // Quay lại
        btnBack.setOnClickListener {
            finish()
        }

        // Google login (để sau)
        btnGoogle.setOnClickListener {
            Toast.makeText(this, "Tính năng đang phát triển", Toast.LENGTH_SHORT).show()
        }

    }

    private fun goToMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
