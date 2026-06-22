package com.example.smartgardening.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

object FirebaseWateringManager {

    fun saveLastWatering(
        startTime: Long,
        endTime: Long,
        mode: String
    ) {
        val user = FirebaseAuth.getInstance().currentUser
        val uid = user?.uid ?: "unknown"
        val email = user?.email ?: "unknown"

        val duration = (endTime - startTime) / 1000

        val data = mapOf(
            "startTime" to startTime,
            "endTime" to endTime,
            "duration" to duration,
            "mode" to mode,
            "userId" to uid,
            "userEmail" to email
        )

        FirebaseDatabase.getInstance()
            .getReference("lastWatering")
            .setValue(data)
    }
}
