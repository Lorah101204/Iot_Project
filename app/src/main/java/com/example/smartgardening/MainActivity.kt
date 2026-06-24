package com.example.smartgardening

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.example.smartgardening.mqtt.MqttManager
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var tvHomeTemp: TextView
    private lateinit var tvHomeHumid: TextView
    private lateinit var tvHomeSoil: TextView
    private lateinit var tvHomePumpStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // 1. Hiển thị tên User (Giữ nguyên code cũ của bạn)
        val tvHomeTitle = findViewById<TextView>(R.id.tvHomeTitle)
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null && !user.displayName.isNullOrEmpty()) {
            tvHomeTitle.text = "${user.displayName}'s Garden"
        } else {
            tvHomeTitle.text = "My Garden"
        }

        // 2. Ánh xạ các CardView (Giữ nguyên)
        val cardTemp = findViewById<CardView>(R.id.cardTemp)
        val cardHumid = findViewById<CardView>(R.id.cardHumid)
        val cardMoisture = findViewById<CardView>(R.id.cardMoisture)
        val cardPump = findViewById<CardView>(R.id.cardPump)

        tvHomeTemp = findViewById(R.id.tvHomeTemp)
        tvHomeHumid = findViewById(R.id.tvHomeHumid)
        tvHomeSoil = findViewById(R.id.tvHomeSoil)
        tvHomePumpStatus = findViewById(R.id.tvHomePumpStatus)

        cardTemp.setOnClickListener {
            val intent = Intent(this, TemperatureActivity::class.java)
            // 1. Lấy chuỗi text hiện tại (ví dụ: "24.5°C")
            val currentTempText = tvHomeTemp.text.toString()
            // 2. Xử lý chuỗi: Xóa chữ "°C" để chỉ lấy số (ví dụ: "24.5")
            // Lưu ý: Nếu text đang là "--°C" hoặc chưa có dữ liệu thì gửi chuỗi rỗng
            val cleanTemp = currentTempText.replace("°C", "").trim()
            // 3. Gửi sang màn hình kia
            intent.putExtra("PASS_TEMP", cleanTemp)
            startActivity(intent)
        }

        cardHumid.setOnClickListener {
            val intent = Intent(this, HumidityActivity::class.java)
            val currentHumidText = tvHomeHumid.text.toString()
            val cleanHumid = currentHumidText.replace("%", "").trim()
            intent.putExtra("PASS_HUMID", cleanHumid)
            startActivity(intent)
        }

        cardMoisture.setOnClickListener {
            val intent = Intent(this, MoistureActivity::class.java)
            val currentMoistureText = tvHomeSoil.text.toString()
            val cleanMoisture = currentMoistureText.replace("%", "").trim()
            intent.putExtra("PASS_SOIL", cleanMoisture)
            startActivity(intent)
        }

        cardPump.setOnClickListener {
            val intent = Intent(this, PumpModesActivity::class.java)
            val currentPumpText = tvHomePumpStatus.text.toString()
            val cleanPump = currentPumpText.replace("%", "").trim()
            intent.putExtra("PASS_PUMP", cleanPump)
            startActivity(intent)
        }

        startLiveUpdates()
    }

    private fun startLiveUpdates() {
        // Kết nối MQTT
        MqttManager.connect {
            Log.d("MainActivity", "Connected MQTT")

            // Đăng ký nhận tin từ topic "sensor/data"
            MqttManager.subscribe("sensor/data") { message ->
                runOnUiThread {
                    try {
                        // Parse JSON: {"temp":24.1,"humi":49.0,"soil":36,"pumpState":"OFF", ...}
                        val json = JSONObject(message)

                        // Lấy dữ liệu
                        val temp = json.optDouble("temp", 0.0)
                        val humi = json.optDouble("humi", 0.0)
                        val soil = json.optInt("soil", 0)
                        val pumpState = json.optString("pumpState", "OFF")

                        // Cập nhật lên màn hình chính
                        tvHomeTemp.text = "${temp}°C"
                        tvHomeHumid.text = "${humi.toInt()}%"  // Humi thường để int cho gọn
                        tvHomeSoil.text = "$soil%"
                        tvHomePumpStatus.text = pumpState // Hiện ON hoặc OFF

                    } catch (e: Exception) {
                        Log.e("MainActivity", "Lỗi đọc JSON: ${e.message}")
                    }
                }
            }
        }
    }
}
