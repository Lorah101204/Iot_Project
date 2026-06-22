package com.example.smartgardening

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import com.example.smartgardening.mqtt.MqttManager
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.json.JSONObject
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.roundToInt

class HumidityActivity : AppCompatActivity() {

    // View Components
    private lateinit var tvMainValue: TextView
    private lateinit var pbHumidity: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvMinValue: TextView
    private lateinit var tvMaxValue: TextView
    private lateinit var humidityChart: LineChart

    // Firebase
    private val dbRef = FirebaseDatabase.getInstance().reference

    // Chart Labels: Đổi thành 'm' (minute) vì dữ liệu gửi mỗi phút
    private val relativeLabels = arrayListOf(
        "-11m", "-10m", "-9m", "-8m", "-7m", "-6m",
        "-5m", "-4m", "-3m", "-2m", "-1m", "Now"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_humid)

        // 1. Bind Views
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        tvMainValue = findViewById<TextView>(R.id.tvMainValue)
        pbHumidity = findViewById<ProgressBar>(R.id.pbHumidity)
        tvStatus = findViewById(R.id.tvStatus)
        tvMinValue = findViewById(R.id.tvMinValue)
        tvMaxValue = findViewById(R.id.tvMaxValue)
        humidityChart = findViewById<LineChart>(R.id.humidityChart)

        btnBack.setOnClickListener { finish() }

        // 2. Setup Chart
        setupHumidityChart(humidityChart)

        val passedHumidStr = intent.getStringExtra("PASS_HUMID")
        if (!passedHumidStr.isNullOrEmpty() && passedHumidStr != "--") {
            try {
                val initialHumid = passedHumidStr.toFloat()
                updateRealtimeUI(initialHumid)
                updateChartNowPoint(initialHumid)
            } catch (e: Exception) {
                Log.e("HumidityInit", "Lỗi parse dữ liệu ban đầu: ${e.message}")
            }
        } else {
            tvMainValue.text = "--%"
            tvStatus.text = "Loading..."
            pbHumidity.progress = 0
        }

        loadHistoryFromFirebase()
        startListeningMqtt()
    }

    private fun setupHumidityChart(chart: LineChart) {
        val entries = ArrayList<Entry>()
        // Tạo dữ liệu giả ban đầu để khung biểu đồ hiện đúng 12 cột
        for (i in 0..11) {
            entries.add(Entry(i.toFloat(), 0f))
        }

        val dataSet = LineDataSet(entries, "Humidity (%)")

        // --- MÀU XANH DƯƠNG CHO ĐỘ ẨM ---
        dataSet.apply {
            color = Color.parseColor("#2196F3") // Xanh dương
            setCircleColor(Color.parseColor("#1565C0"))
            lineWidth = 2.5f
            circleRadius = 3f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillColor = Color.parseColor("#BBDEFB") // Xanh nhạt
            fillAlpha = 60
            setDrawValues(false)
        }

        chart.apply {
            data = LineData(dataSet)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.setDrawGridLines(false)
            xAxis.granularity = 1f
            // Set Labels mới (phút)
            xAxis.valueFormatter = IndexAxisValueFormatter(relativeLabels)
            xAxis.axisMinimum = 0f
            xAxis.axisMaximum = 11f
            axisRight.isEnabled = false
            axisLeft.axisMinimum = 0f
            axisLeft.axisMaximum = 100f // Độ ẩm max là 100%
            invalidate()
        }
    }

    private fun loadHistoryFromFirebase() {
        // Path: history/humidity
        dbRef.child("history").child("humidity").limitToLast(12)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val data = humidityChart.data ?: return
                    val set = data.getDataSetByIndex(0) as LineDataSet

                    set.clear()
                    val totalPoints = snapshot.childrenCount.toInt()
                    // Tính toán để các điểm dữ liệu nằm về phía bên phải (gần "Now")
                    var currentIndex = 12 - totalPoints
                    if (currentIndex < 0) currentIndex = 0

                    for (child in snapshot.children) {
                        val value = child.child("value").getValue(Float::class.java) ?: 0f
                        set.addEntry(Entry(currentIndex.toFloat(), value))
                        currentIndex++
                    }

                    // Nếu không có dữ liệu, thêm 1 điểm 0 ở cuối để chart không crash
                    if (set.entryCount == 0) set.addEntry(Entry(11f, 0f))

                    data.notifyDataChanged()
                    humidityChart.notifyDataSetChanged()
                    humidityChart.invalidate()
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun startListeningMqtt() {
        MqttManager.connect {
            Log.d("HumidityActivity", "Connected! Subscribing...")
            MqttManager.subscribe("sensor/data") { message ->
                runOnUiThread {
                    try {
                        val json = JSONObject(message)

                        // 1. Lấy độ ẩm hiện tại
                        val humi = json.optDouble("humi", 0.0).toFloat()

                        // 2. Lấy Min/Max từ ESP gửi lên (giống TempActivity)
                        // Nếu ESP chưa gửi min_humi/max_humi thì tạm dùng giá trị hiện tại
                        val minHumi = json.optDouble("min_humi", humi.toDouble()).toFloat()
                        val maxHumi = json.optDouble("max_humi", humi.toDouble()).toFloat()

                        // 3. Cập nhật UI Realtime
                        updateRealtimeUI(humi)
                        updateChartNowPoint(humi)

                        // 4. Hiển thị Min/Max trực tiếp
                        tvMinValue.text = "${minHumi.toInt()}%"
                        tvMaxValue.text = "${maxHumi.toInt()}%"

                    } catch (e: Exception) {
                        Log.e("HumidityActivity", "JSON Error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun updateRealtimeUI(value: Float) {
        val roundedValue = value.roundToInt()
        tvMainValue.text = "$roundedValue%"
        pbHumidity.progress = roundedValue

        // Logic trạng thái Độ ẩm
        if (value < 40) {
            tvStatus.text = "DRY" // Khô
            tvStatus.setTextColor("#FF9800".toColorInt()) // Cam
            pbHumidity.progressDrawable.setTint("#78cbc0".toColorInt())
        } else if (value > 75) {
            tvStatus.text = "WET" // Ẩm ướt
            tvStatus.setTextColor("#1565C0".toColorInt()) // Xanh đậm
            pbHumidity.progressDrawable.setTint("#78cbc0".toColorInt())
        } else {
            tvStatus.text = "COMFORT" // Thoải mái
            tvStatus.setTextColor("#4CAF50".toColorInt()) // Xanh lá
            pbHumidity.progressDrawable.setTint("#78cbc0".toColorInt())
        }
    }

    private fun updateChartNowPoint(value: Float) {
        val data = humidityChart.data ?: return
        val set = data.getDataSetByIndex(0) as LineDataSet

        val entries = set.values
        var found = false
        // Tìm xem điểm tại x=11 (Now) đã có chưa để cập nhật
        for (e in entries) {
            if (e.x == 11f) {
                e.y = value
                found = true
                break
            }
        }

        // Nếu chưa có (lần đầu nhận tin), thêm mới vào
        if (!found) set.addEntry(Entry(11f, value))

        data.notifyDataChanged()
        humidityChart.notifyDataSetChanged()
        humidityChart.invalidate()
    }
}