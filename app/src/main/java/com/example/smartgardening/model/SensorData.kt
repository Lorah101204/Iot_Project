import com.google.firebase.Timestamp

data class SensorData(
    val soilHumidity: Int,
    val temperature: Float,
    val waterLevel: Int,
    val pumpState: String,
    val timestamp: Timestamp = Timestamp.now()
)
