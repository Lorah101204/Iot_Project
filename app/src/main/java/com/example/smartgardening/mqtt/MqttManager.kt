package com.example.smartgardening.mqtt

import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import org.json.JSONObject
import java.util.UUID

object MqttManager {

    private const val TAG = "MQTT"

    // 🔐 THAY BẰNG THÔNG TIN CỦA BẠN
    private const val HOST = "7882f49ec5a24abc9c49b6c8332f73e4.s1.eu.hivemq.cloud"
    private const val PORT = 8883
    private const val USERNAME = "hayson"
    private const val PASSWORD = "Alo123,./"

    private lateinit var client: Mqtt5AsyncClient

    // 🔥 1. Biến chứa hàm Callback (Cầu nối đến Activity)
    // Activity nào đang mở sẽ gán code vào biến này để nhận dữ liệu
    var onSensorDataReceived: ((temp: Float, humi: Float, soil: Int, water: Int) -> Unit)? = null

    /**
     * Kết nối MQTT
     */
    // Thêm callback onSuccess để Activity biết khi nào kết nối xong
    fun connect(onSuccess: (() -> Unit)? = null) {
        // 1. Nếu đã kết nối rồi thì không connect lại, gọi callback luôn
        if (::client.isInitialized && client.state.isConnected) {
            Log.d(TAG, "⚡ Đã kết nối sẵn, bỏ qua connect lại.")
            onSuccess?.invoke()
            return
        }

        client = MqttClient.builder()
            .useMqttVersion5()
            .serverHost(HOST)
            .serverPort(PORT)
            .sslWithDefaultConfig()
            .identifier(UUID.randomUUID().toString())
            .buildAsync()

        client.connectWith()
            .simpleAuth()
            .username(USERNAME)
            .password(PASSWORD.toByteArray())
            .applySimpleAuth()
            .send()
            .whenComplete { _, throwable ->
                if (throwable != null) {
                    Log.e(TAG, "❌ MQTT connect failed", throwable)
                } else {
                    Log.d(TAG, "✅ MQTT connected")
                    // 2. Gọi callback khi kết nối thành công
                    onSuccess?.invoke()
                }
            }
    }
    /**
     * Ngắt kết nối MQTT
     */
    fun disconnect() {
        if (::client.isInitialized && client.state.isConnected) {
            client.disconnect()
            Log.d(TAG, "🔌 MQTT disconnected")
        }
    }
    /**
     * Hàm gửi tin nhắn tổng quát (Sửa lỗi quan trọng ở đây)
     * Dùng cho cả Settings (Auto Mode, Threshold) và Pump
     */
    fun publish(topic: String, message: String) {
        // 1. Kiểm tra kết nối trước
        if (!::client.isInitialized || !client.state.isConnected) {
            Log.e(TAG, "⚠️ Chưa kết nối MQTT, không thể gửi lệnh!")
            return
        }

        // 2. Dùng cú pháp chuẩn của HiveMQ Client (publishWith)
        client.publishWith()
            .topic(topic)
            .payload(message.toByteArray())
            .qos(MqttQos.AT_LEAST_ONCE) // Tương đương QoS 1
            .retain(false) // Mặc định không retain lệnh điều khiển
            .send()
            .whenComplete { _, throwable ->
                if (throwable != null) {
                    Log.e(TAG, "❌ Gửi thất bại: $topic", throwable)
                } else {
                    Log.d(TAG, "📤 Đã gửi: $topic -> $message")
                }
            }
    }

    fun subscribe(topic: String, callback: (String) -> Unit) {
        // Kiểm tra kết nối trước
        if (!::client.isInitialized || !client.state.isConnected) {
            Log.e(TAG, "⚠️ Chưa kết nối, không thể subscribe $topic")
            return
        }

        client.subscribeWith()
            .topicFilter(topic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .callback { publish ->
                // Khi có tin nhắn mới -> Chuyển thành String
                val message = String(publish.payloadAsBytes)
                Log.d(TAG, "📩 Nhận tin từ $topic: $message")

                // Trả về cho Activity xử lý (qua callback)
                callback(message)
            }
            .send()
            .whenComplete { _, throwable ->
                if (throwable != null) {
                    Log.e(TAG, "❌ Subscribe lỗi: $topic", throwable)
                } else {
                    Log.d(TAG, "✅ Đã đăng ký lắng nghe: $topic")
                }
            }
    }
}