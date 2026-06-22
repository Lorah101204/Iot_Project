package com.example.smartgardening

import android.R.attr.button
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.example.smartgardening.firebase.FirebaseWateringManager
import com.example.smartgardening.mqtt.MqttManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class PumpModesActivity : AppCompatActivity() {

    private var isPumpOn = false
    private val TOPIC_MODE = "settings/mode"
    private val TOPIC_THRESHOLD = "settings/soil_threshold"

    private var pumpStartTime: Long = 0L
    private var currentMode = "MANUAL"

    private var calendar = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pump_modes)

        // View
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        val togglePumpManual = findViewById<MaterialCardView>(R.id.togglePumpManual)
        val ivPowerIcon = findViewById<ImageView>(R.id.ivPowerIcon)
        val tvPumpStatus = findViewById<TextView>(R.id.tvPumpStatus)
        val swScheduleMode = findViewById<SwitchCompat>(R.id.swScheduleMode)
        val swAutoMode = findViewById<SwitchCompat>(R.id.swAutoMode)
        val layoutScheduleSettings = findViewById<LinearLayout>(R.id.layoutScheduleSettings)
        val layoutAutoSettings = findViewById<LinearLayout>(R.id.layoutAutoSettings)
        val btnSelectDateTime = findViewById<Button>(R.id.btnSelectDateTime)
        val sbThreshold = findViewById<SeekBar>(R.id.sbThreshold)
        val tvThresholdValue = findViewById<TextView>(R.id.tvThresholdValue)
        val etDuration = findViewById<EditText>(R.id.etDuration)

        calendar = Calendar.getInstance()
        calendar.timeZone = TimeZone.getTimeZone("Asia/Bangkok")


        btnBack.setOnClickListener { finish() }

        // 🔥 KẾT NỐI MQTT 1 LẦN
        MqttManager.connect{
            MqttManager.subscribe("sensor/data") { message ->
                runOnUiThread {
                    try {
                        val json = JSONObject(message)

                        // 1. Lấy trạng thái bơm
                        val pumpState = json.optString("pumpState", "OFF")

                        // 2. Cập nhật UI Realtime
                        if (pumpState == "ON") {
                            isPumpOn = true
                        }
                        else { isPumpOn = false }

                    } catch (e: Exception) {
                        Log.e("HumidityActivity", "JSON Error: ${e.message}")
                    }
                }
            }
        }

        // ===== NÚT BẬT / TẮT MÁY BƠM =====
        togglePumpManual.setOnClickListener {
            if (!isPumpOn) {
                // ===== BẬT BƠM =====
                isPumpOn = true
                pumpStartTime = System.currentTimeMillis()
                currentMode = "MANUAL"
                MqttManager.publish(TOPIC_MODE, "0")
                MqttManager.publish("pump/control", "on")
            } else {
                // ===== TẮT BƠM =====
                isPumpOn = false
                MqttManager.publish("pump/control", message = "off")

                if (pumpStartTime > 0) {
                    FirebaseWateringManager.saveLastWatering(
                        startTime = pumpStartTime,
                        endTime = System.currentTimeMillis(),
                        mode = currentMode
                    )
                }

                pumpStartTime = 0L
            }

            updatePumpUI(togglePumpManual, ivPowerIcon,tvPumpStatus)
        }

        // ===== SCHEDULE MODE =====
        swScheduleMode.setOnCheckedChangeListener { _, isChecked ->
            // Cập nhật giao diện mờ/sáng
            layoutScheduleSettings.isEnabled = isChecked
            layoutScheduleSettings.alpha = if (isChecked) 1f else 0.4f

            if (isChecked) {
                // >>> KHI BẬT SCHEDULE <<<
                // 1. Tắt Auto nếu đang bật
                if (swAutoMode.isChecked) swAutoMode.isChecked = false

                // 2. Nếu đang Bật bơm thủ công -> Tắt ngay để giao quyền cho Schedule
                if (isPumpOn) {
                    isPumpOn = false
                    pumpStartTime = 0L // Reset thời gian đếm
                    updatePumpUI(togglePumpManual, ivPowerIcon, tvPumpStatus) // Cập nhật nút về màu xám
                    // Không gửi lệnh off bơm ở đây, để ESP tự quyết định dựa trên cảm biến
                }

                // 3. Đóng gói gói tin JSON
                val duration = etDuration.text.toString().toIntOrNull() ?: 0
                val startTime = calendar.timeInMillis / 1000

                val json = JSONObject().apply {
                    put("enable", true)
                    put("start_time", startTime)
                    put("duration", duration)
                }

                // 4. Gửi lệnh chuyển Mode schedule
                MqttManager.publish("garden/schedule", json.toString())

                // 5. Khóa nút bấm Manual
                togglePumpManual.isEnabled = false
                togglePumpManual.alpha = 0.5f

                Toast.makeText(this, "Đã BẬT Schedule Mode", Toast.LENGTH_SHORT).show()

            } else {
                // >>> KHI TẮT SCHEDULE (VỀ MANUAL) <<<

                // 1. Gửi lệnh hủy mode Schedule
                val json = JSONObject().apply {
                    put("enable", false)
                }
                MqttManager.publish("garden/schedule", json.toString())

                // 2. [QUAN TRỌNG] Gửi lệnh TẮT BƠM NGAY để tránh bơm bị treo nếu đang chạy dở
                MqttManager.publish(TOPIC_MODE, "0")
                MqttManager.publish("pump/control", "off")

                // 3. Đảm bảo trạng thái biến App đồng bộ
                isPumpOn = false
                updatePumpUI(togglePumpManual, ivPowerIcon, tvPumpStatus)

                // 4. Mở khóa nút bấm Manual
                togglePumpManual.isEnabled = true
                togglePumpManual.alpha = 1.0f

                Toast.makeText(this, "Đã về Manual Mode", Toast.LENGTH_SHORT).show()
            }
        }

        btnSelectDateTime.setOnClickListener {
            DatePickerDialog(
                this,
                { _, year, month, day ->
                    calendar.set(Calendar.YEAR, year)
                    calendar.set(Calendar.MONTH, month)
                    calendar.set(Calendar.DAY_OF_MONTH, day)

                    // Sau khi chọn ngày → chọn GIỜ
                    TimePickerDialog(
                        this,
                        { _, hour, minute ->
                            calendar.set(Calendar.HOUR_OF_DAY, hour)
                            calendar.set(Calendar.MINUTE, minute)
                            calendar.set(Calendar.SECOND, 0)

                            // ✅ CHẶN thời gian quá khứ
                            if (calendar.timeInMillis < System.currentTimeMillis()) {
                                Toast.makeText(
                                    this,
                                    "Thời gian không hợp lệ",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@TimePickerDialog
                            }

                            val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                            btnSelectDateTime.text = "📅 ${sdf.format(calendar.time)}"
                        },
                        calendar.get(Calendar.HOUR_OF_DAY),
                        calendar.get(Calendar.MINUTE),
                        true
                    ).show()

                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        // ===== AUTO MODE =====
        swAutoMode.setOnCheckedChangeListener { _, isChecked ->
            // Cập nhật giao diện mờ/sáng
            layoutAutoSettings.isEnabled = isChecked
            layoutAutoSettings.alpha = if (isChecked) 1f else 0.4f

            if (isChecked) {
                // >>> KHI BẬT AUTO <<<
                // 1. Tắt Schedule nếu đang bật
                if (swScheduleMode.isChecked) swScheduleMode.isChecked = false

                // 2. Nếu đang Bật bơm thủ công -> Tắt ngay để giao quyền cho Auto
                if (isPumpOn) {
                    isPumpOn = false
                    pumpStartTime = 0L // Reset thời gian đếm
                    updatePumpUI(togglePumpManual, ivPowerIcon, tvPumpStatus) // Cập nhật nút về màu xám
                    // Không gửi lệnh off bơm ở đây, để ESP tự quyết định dựa trên cảm biến
                }

                // 3. Gửi lệnh chuyển Mode 1
                MqttManager.publish(TOPIC_MODE, "1")

                // 4. Đồng bộ lại Threshold
                val currentThreshold = sbThreshold.progress
                MqttManager.publish(TOPIC_THRESHOLD, currentThreshold.toString())

                // 5. Khóa nút bấm Manual
                togglePumpManual.isEnabled = false
                togglePumpManual.alpha = 0.5f

                Toast.makeText(this, "Đã BẬT Auto Mode", Toast.LENGTH_SHORT).show()

            } else {
                // >>> KHI TẮT AUTO (VỀ MANUAL) <<<

                // 1. Gửi lệnh chuyển Mode 0
                MqttManager.publish(TOPIC_MODE, "0")

                // 2. [QUAN TRỌNG] Gửi lệnh TẮT BƠM NGAY để tránh bơm bị treo nếu đang chạy dở
                MqttManager.publish("pump/control", "off")

                // 3. Đảm bảo trạng thái biến App đồng bộ
                isPumpOn = false
                updatePumpUI(togglePumpManual, ivPowerIcon, tvPumpStatus)

                // 4. Mở khóa nút bấm Manual
                togglePumpManual.isEnabled = true
                togglePumpManual.alpha = 1.0f

                Toast.makeText(this, "Đã về Manual Mode", Toast.LENGTH_SHORT).show()
            }
        }
        //== THANH KÉO NGƯỠNG ĐỘ ẨM ====
        sbThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Cập nhật số hiển thị realtime khi kéo
                tvThresholdValue.text = "$progress%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Không làm gì khi bắt đầu chạm
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // QUAN TRỌNG: Chỉ gửi MQTT khi người dùng THẢ TAY ra khỏi thanh trượt
                // Để tránh gửi hàng trăm tin nhắn liên tục khi đang kéo gây lag ESP

                val value = seekBar?.progress ?: 30

                // Chỉ gửi nếu đang bật chế độ Auto hoặc muốn cập nhật trước
                MqttManager.publish(TOPIC_THRESHOLD, value.toString())

                Toast.makeText(applicationContext, "Đã cập nhật ngưỡng tưới: $value%", Toast.LENGTH_SHORT).show()
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        MqttManager.disconnect()
    }

    private fun updatePumpUI(card: MaterialCardView, icon: ImageView, statusText: TextView) {
        val targetX = if (isPumpOn) 120f else 0f

        icon.animate()
            .translationX(targetX)
            .setDuration(250)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        if (isPumpOn) {
            card.setCardBackgroundColor(Color.parseColor("#2ECC71"))
            card.setStrokeColor(Color.parseColor("#2ECC71"))

            statusText.text = "MÁY BƠM ĐANG CHẠY"
            statusText.setTextColor(Color.parseColor("#2ECC71"))
        } else {
            card.setCardBackgroundColor(Color.parseColor("#989898"))
            card.setStrokeColor(Color.WHITE)

            statusText.text = "MÁY BƠM ĐANG TẮT"
            statusText.setTextColor(Color.parseColor("#1D431F"))
        }
    }
}
