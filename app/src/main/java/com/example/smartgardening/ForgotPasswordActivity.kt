package com.example.smartgardening

import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth

class ForgotPasswordActivity : AppCompatActivity() {

    private lateinit var firebaseAuth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forgot_password)

        // Firebase
        firebaseAuth = FirebaseAuth.getInstance()

        // Ánh xạ view
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        val etEmailReset = findViewById<EditText>(R.id.etEmailReset)
        val btnResetPassword = findViewById<MaterialButton>(R.id.btnResetPassword)

        // Quay lại
        btnBack.setOnClickListener {
            finish()
        }

        // Gửi email reset mật khẩu
        btnResetPassword.setOnClickListener {
            val email = etEmailReset.text.toString().trim()

            if (email.isEmpty()) {
                Toast.makeText(this, "Vui lòng nhập email", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            firebaseAuth.sendPasswordResetEmail(email)
                .addOnSuccessListener {
                    Toast.makeText(
                        this,
                        "Đã gửi email khôi phục mật khẩu",
                        Toast.LENGTH_LONG
                    ).show()
                }
                .addOnFailureListener { e ->
                    Toast.makeText(
                        this,
                        "Lỗi: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }
}
