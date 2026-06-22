package com.example.smartgardening

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
import java.util.*
import kotlin.collections.ArrayList
import kotlin.math.roundToInt

class MoistureActivity : AppCompatActivity() {

    // View Components
    private lateinit var tvMainValue: TextView
    private lateinit var pbMoisture: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvMinValue: TextView
    private lateinit var tvMaxValue: TextView
    private lateinit var moistureChart: LineChart

    // Firebase Reference
    private val dbRef = FirebaseDatabase.getInstance().reference

    private val relativeLabels = arrayListOf(
        "-11m", "-10m", "-9m", "-8m", "-7m", "-6m",
        "-5m", "-4m", "-3m", "-2m", "-1m", "Now"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_moisture)

        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        tvMainValue = findViewById<TextView>(R.id.tvMainValue)
        pbMoisture = findViewById<ProgressBar>(R.id.pbMoisture)
        tvStatus = findViewById(R.id.tvStatus)
        tvMinValue = findViewById(R.id.tvMinValue)
        tvMaxValue = findViewById(R.id.tvMaxValue)
        moistureChart = findViewById<LineChart>(R.id.moistureChart)

        btnBack.setOnClickListener { finish() }
        setupChart(moistureChart)
        val passedSoilStr = intent.getStringExtra("PASS_SOIL")

        if (!passedSoilStr.isNullOrEmpty() && passedSoilStr != "--") {
            try {
                val initialSoil = passedSoilStr.toFloat()
                updateRealtimeUI(initialSoil)
                updateChartNowPoint(initialSoil)
            } catch (e: Exception) {
                Log.e("MoistureInit", "Lỗi parse dữ liệu ban đầu: ${e.message}")
            }
        } else {
            // Nếu không có dữ liệu, set trạng thái Loading
            tvMainValue.text = "--%"
            tvStatus.text = "Loading..."
            pbMoisture.progress = 0
        }

        loadHistoryFromFirebase()
        startListeningMqtt()
    }

    private fun setupChart(chart: LineChart) {
        val entries = ArrayList<Entry>()
        for (i in 0..11) {
            entries.add(Entry(i.toFloat(), 0f))
        }

        val dataSet = LineDataSet(entries, "Soil Moisture (%)")

        dataSet.apply {
            color = Color.parseColor("#4CAF50") // Xanh lá đậm
            setCircleColor(Color.parseColor("#1B5E20"))
            lineWidth = 2.5f
            circleRadius = 3f
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawFilled(true)
            fillColor = Color.parseColor("#C8E6C9") // Xanh lá nhạt
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
            // Sử dụng labels phút
            xAxis.valueFormatter = IndexAxisValueFormatter(relativeLabels)
            xAxis.axisMinimum = 0f
            xAxis.axisMaximum = 11f
            axisRight.isEnabled = false
            axisLeft.axisMinimum = 0f
            axisLeft.axisMaximum = 100f // Độ ẩm đất 0-100%
            invalidate()
        }
    }

    private fun loadHistoryFromFirebase() {
        dbRef.child("history").child("soil").limitToLast(12)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val data = moistureChart.data ?: return
                    val set = data.getDataSetByIndex(0) as LineDataSet

                    set.clear()
                    val totalPoints = snapshot.childrenCount.toInt()
                    var currentIndex = 12 - totalPoints
                    if (currentIndex < 0) currentIndex = 0

                    for (child in snapshot.children) {
                        // Soil thường là Int, nhưng Chart cần Float
                        val value = child.child("value").getValue(Float::class.java) ?: 0f
                        set.addEntry(Entry(currentIndex.toFloat(), value))
                        currentIndex++
                    }

                    if (set.entryCount == 0) set.addEntry(Entry(11f, 0f))

                    data.notifyDataChanged()
                    moistureChart.notifyDataSetChanged()
                    moistureChart.invalidate()
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun startListeningMqtt() {
        MqttManager.connect {
            Log.d("MoistureActivity", "Connected! Subscribing...")
            MqttManager.subscribe("sensor/data") { message ->
                runOnUiThread {
                    try {
                        val json = JSONObject(message)

                        // 1. Lấy giá trị Soil hiện tại
                        val soil = json.optDouble("soil", 0.0).toFloat()

                        // 2. Lấy Min/Max Soil từ JSON (Dựa trên mẫu bạn gửi: min_soil, max_soil)
                        val minSoil = json.optDouble("min_soil", soil.toDouble()).toFloat()
                        val maxSoil = json.optDouble("max_soil", soil.toDouble()).toFloat()

                        // 3. Cập nhật UI
                        updateRealtimeUI(soil)
                        updateChartNowPoint(soil)

                        // 4. Hiển thị Min/Max trực tiếp
                        tvMinValue.text = "${minSoil.toInt()}%"
                        tvMaxValue.text = "${maxSoil.toInt()}%"

                    } catch (e: Exception) {
                        Log.e("Moisture", "JSON Error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun updateRealtimeUI(value: Float) {
        val intValue = value.roundToInt()
        tvMainValue.text = "$intValue%"
        pbMoisture.progress = intValue

        if (intValue < 30) {
            tvStatus.text = "DRY" // Khô
            tvStatus.setTextColor(Color.parseColor("#FF5722")) // Cam đậm
            pbMoisture.progressDrawable.setTint(Color.parseColor("#FF5722"))

        } else if (intValue > 70) {
            tvStatus.text = "WET" // Ẩm ướt
            tvStatus.setTextColor(Color.parseColor("#2196F3")) // Xanh dương
            pbMoisture.progressDrawable.setTint(Color.parseColor("#2196F3"))

        } else {
            tvStatus.text = "IDEAL" // Lý tưởng
            tvStatus.setTextColor(Color.parseColor("#4CAF50")) // Xanh lá
            pbMoisture.progressDrawable.setTint(Color.parseColor("#4CAF50"))
        }
    }

    private fun updateChartNowPoint(value: Float) {
        val data = moistureChart.data ?: return
        val set = data.getDataSetByIndex(0) as LineDataSet

        val entries = set.values
        var found = false
        // Tìm điểm tại vị trí x=11 (Now)
        for (e in entries) {
            if (e.x == 11f) {
                e.y = value
                found = true
                break
            }
        }

        if (!found) set.addEntry(Entry(11f, value))

        data.notifyDataChanged()
        moistureChart.notifyDataSetChanged()
        moistureChart.invalidate()
    }
}