import com.google.firebase.firestore.FirebaseFirestore

object FirestoreRepository {
    private val db = FirebaseFirestore.getInstance()

    fun saveSensorData(uid: String, data: SensorData) {
        db.collection("users")
            .document(uid)
            .collection("sensors")
            .add(data)
    }
}
