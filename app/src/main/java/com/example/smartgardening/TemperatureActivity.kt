package com.example.smartgardening

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.roundToInt
import androidx.core.graphics.toColorInt

class TemperatureActivity : AppCompatActivity() {

    // View Components
    private lateinit var tvMainValue: TextView
    private lateinit var pbTempColumn: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvMinValue: TextView
    private lateinit var tvMaxValue: TextView
    private lateinit var tempChart: LineChart

    // Firebase
    private val dbRef = FirebaseDatabase.getInstance().reference

    // Chart Labels (12 points)
    private val relativeLabels = arrayListOf(
        "-11m", "-10m", "-9m", "-8m", "-7m", "-6m",
        "-5h", "-4m", "-3m", "-2m", "-1m", "Now"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_temp)

        // 2. Bind Views (Khớp ID trong XML của bạn)
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        tvMainValue = findViewById<TextView>(R.id.tvMainValue)
        pbTempColumn = findViewById<ProgressBar>(R.id.pbTempColumn)
        tvStatus = findViewById(R.id.tvStatus)
        tvMinValue = findViewById(R.id.tvMinValue)
        tvMaxValue = findViewById(R.id.tvMaxValue)
        tempChart = findViewById<LineChart>(R.id.tempChart)

        btnBack.setOnClickListener { finish() }

        setupTempChart(tempChart)

        val passedTempStr = intent.getStringExtra("PASS_TEMP")
        if (!passedTempStr.isNullOrEmpty() && passedTempStr != "--") {
            try {
                // Chuyển string sang float
                val initialTemp = passedTempStr.toFloat()

                // Cập nhật giao diện NGAY LẬP TỨC
                updateRealtimeUI(initialTemp)
                updateChartNowPoint(initialTemp)

            } catch (e: Exception) {
                Log.e("TempInit", "Lỗi parse dữ liệu ban đầu: ${e.message}")
            }
        } else {
            // Nếu không có dữ liệu truyền sang, set về trạng thái chờ
            tvMainValue.text = "--°C"
            tvStatus.text = "Loading..."
            pbTempColumn.progress = 0
        }

        loadHistoryFromFirebase()
        startListeningMqtt()
    }

    private fun setupTempChart(chart: LineChart) {
        val entries = ArrayList<Entry>()
        for (i in 0..11) {
            entries.add(Entry(i.toFloat(), 0f))
        }

        val dataSet = LineDataSet(entries, "Temperature (°C)")

        // --- MÀU ĐỎ CHO NHIỆT ĐỘ ---
        dataSet.apply {
            color = Color.parseColor("#E53935") // Đỏ đậm
            setCircleColor(Color.parseColor("#B71C1C"))
            lineWidth = 2.5f
            circleRadius = 3f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillColor = Color.parseColor("#FFCDD2") // Hồng nhạt
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
            xAxis.valueFormatter = IndexAxisValueFormatter(relativeLabels)
            xAxis.axisMinimum = 0f
            xAxis.axisMaximum = 11f
            axisRight.isEnabled = false
            axisLeft.axisMinimum = 0f
            axisLeft.axisMaximum = 50f // Max nhiệt độ khoảng 50 là vừa
            invalidate()
        }
    }

    private fun loadHistoryFromFirebase() {
        // Path: history/temperature
        dbRef.child("history").child("temperature").limitToLast(12)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val data = tempChart.data ?: return
                    val set = data.getDataSetByIndex(0) as LineDataSet

                    set.clear()
                    val totalPoints = snapshot.childrenCount.toInt()
                    var currentIndex = 12 - totalPoints
                    if (currentIndex < 0) currentIndex = 0

                    for (child in snapshot.children) {
                        val value = child.child("value").getValue(Float::class.java) ?: 0f
                        set.addEntry(Entry(currentIndex.toFloat(), value))
                        currentIndex++
                    }

                    if (set.entryCount == 0) set.addEntry(Entry(11f, 0f))

                    data.notifyDataChanged()
                    tempChart.notifyDataSetChanged()
                    tempChart.invalidate()
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun startListeningMqtt() {
        MqttManager.connect {
            Log.d("TempActivity", "Connected! Subscribing...")
            MqttManager.subscribe("sensor/data") { message ->
                runOnUiThread {
                    try {
                        val json = JSONObject(message)

                        // 1. Lấy nhiệt độ hiện tại
                        val temp = json.optDouble("temp", 0.0).toFloat()

                        // 2. Lấy Min/Max từ ESP gửi lên (Mặc định là temp nếu chưa có)
                        val minTemp = json.optDouble("min_temp", temp.toDouble()).toFloat()
                        val maxTemp = json.optDouble("max_temp", temp.toDouble()).toFloat()

                        // 3. Cập nhật UI
                        updateRealtimeUI(temp)
                        updateChartNowPoint(temp)

                        // 4. Hiển thị Min/Max trực tiếp (Không cần tính toán nữa)
                        tvMinValue.text = "${minTemp}°C"
                        tvMaxValue.text = "${maxTemp}°C"

                    } catch (e: Exception) {
                        Log.e("TempActivity", "JSON Error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun updateRealtimeUI(value: Float) {
        val roundedValue = value
        tvMainValue.text = "${roundedValue}°C" // 23.8 sẽ thành 24
        pbTempColumn.progress = value.roundToInt()
        // Logic trạng thái Nhiệt độ
        if (value < 18) {
            tvStatus.text = "LOW"
            tvStatus.setTextColor("#2196F3".toColorInt()) // Xanh dương
        } else if (value > 35) {
            tvStatus.text = "HIGH"
            tvStatus.setTextColor("#D32F2F".toColorInt()) // Đỏ đậm
        } else {
            tvStatus.text = "NORMAL"
            tvStatus.setTextColor("#4CAF50".toColorInt()) // Xanh lá
        }
    }

    private fun updateChartNowPoint(value: Float) {
        val data = tempChart.data ?: return
        val set = data.getDataSetByIndex(0) as LineDataSet

        val entries = set.values
        var found = false
        for (e in entries) {
            if (e.x == 11f) {
                e.y = value
                found = true
                break
            }
        }

        if (!found) set.addEntry(Entry(11f, value))

        data.notifyDataChanged()
        tempChart.notifyDataSetChanged()
        tempChart.invalidate()
    }

}