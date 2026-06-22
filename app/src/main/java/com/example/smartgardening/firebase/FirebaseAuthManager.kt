package com.example.smartgardening.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
object FirebaseAuthManager {
    // Biến auth của bạn đã có sẵn
    private val auth = FirebaseAuth.getInstance()

    // Lấy ID người dùng hiện tại
    fun getUid(): String? = auth.currentUser?.uid

    // Kiểm tra xem người dùng đã đăng nhập chưa
    fun isUserLoggedIn(): Boolean = auth.currentUser != null

    // 1. Hàm Đăng Ký
    fun register(email: String, pass: String, name: String, onResult: (Boolean, String?) -> Unit) {
        auth.createUserWithEmailAndPassword(email, pass)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    // Tạo tài khoản thành công -> Cập nhật tên hiển thị ngay lập tức
                    val user = auth.currentUser
                    val profileUpdates = UserProfileChangeRequest.Builder()
                        .setDisplayName(name) // Lưu tên vào Profile Firebase
                        .build()

                    user?.updateProfile(profileUpdates)
                        ?.addOnCompleteListener { updateTask ->
                            if (updateTask.isSuccessful) {
                                onResult(true, null) // Thành công trọn vẹn
                            } else {
                                // Tài khoản tạo được nhưng cập nhật tên lỗi (hiếm gặp)
                                onResult(true, "Đăng ký thành công (Lỗi lưu tên)")
                            }
                        }
                } else {
                    onResult(false, task.exception?.message) // Thất bại
                }
            }
    }

    // 2. Hàm Đăng Nhập
    fun login(email: String, pass: String, onResult: (Boolean, String?) -> Unit) {
        auth.signInWithEmailAndPassword(email, pass)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    onResult(true, null)
                } else {
                    onResult(false, task.exception?.message)
                }
            }
    }

    // 3. Hàm Đăng Xuất
    fun logout() {
        auth.signOut()
    }
}