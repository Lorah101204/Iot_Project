#include <ESP8266WiFi.h>
#include <PubSubClient.h>
#include <DHT.h>
#include <WiFiClientSecure.h>
#include <WiFiManager.h>
#include <Firebase_ESP_Client.h>
#include <ArduinoJson.h>
#include <time.h>

// ===== FIREBASE CONFIG =====
#define FIREBASE_HOST "it4735-watersensor-default-rtdb.asia-southeast1.firebasedatabase.app"
#define FIREBASE_AUTH "JLOB3mrROzevv6JoTxoEduAFXh6f2IE6NQKRFpcR"


// ===== PIN CONFIG =====
#define AOUT_PIN A0
#define PUMP D7
#define DHTPIN D5
#define DHTTYPE DHT11
#define TRIG_PIN D2
#define ECHO_PIN D1

DHT dht(DHTPIN, DHTTYPE);

// ===== MQTT =====
WiFiClientSecure espClient;
PubSubClient client(espClient);

const char* mqttServer = "7882f49ec5a24abc9c49b6c8332f73e4.s1.eu.hivemq.cloud";
const int mqttPort = 8883;
const char* mqttClientID = "ESP8266_SmartGarden";
const char* mqttUser = "hayson";
const char* mqttPassword = "Alo123,./";

// ===== FIREBASE OBJECT =====
FirebaseData fbData;
FirebaseAuth fbAuth;
FirebaseConfig fbConfig;

unsigned long lastPublishTime = 0;
unsigned long lastFirebaseTime = 0;

int systemMode = 0; // 0: Manual, 1: Auto
bool isPumpRunning = false;
String warningMsg = "OK";

unsigned long scheduledStartTime = 0; // Thời điểm bắt đầu (Unix timestamp)
int scheduledDuration = 0;            // Thời lượng bơm (giây)
bool isScheduleEnabled = false;       // Có đang bật hẹn giờ không
bool isScheduleRunning = false;       // Máy bơm có đang chạy theo lịch không
bool isPumpTimeout = false;
unsigned long autoPumpStartTime = 0;
const unsigned long MAX_PUMP_RUNTIME = 60000;

int soilThreshold = 30;
int hysteresis = 10;
int tankHeight = 20;
int waterOffset = 8;
int minWaterPercent = 10;

int soilPercent = 0;
int waterPercent = 0;
float temp = 0;
float humi = 0;

float sumTemp = 0, sumHumi = 0, sumSoil = 0, sumWater = 0;
int countSamples = 0;
const unsigned long FIREBASE_INTERVAL = 60000;

#define TIMEZONE_OFFSET 7 * 3600
#define DST_OFFSET 0

void setup_wifi_and_time() {
    WiFi.mode(WIFI_STA);
    WiFiManager wm;
    if (!wm.autoConnect("IOT_GARDEN")) ESP.restart();
    Serial.println("WiFi Connected");

    configTime(TIMEZONE_OFFSET, DST_OFFSET, "pool.ntp.org", "time.nist.gov");
    Serial.println("Waiting for time...");
    while (!time(nullptr)) {
        Serial.print(".");
        delay(1000);
    }
    Serial.println("\nTime Synced!");
}

void callback(char* topic, byte* payload, unsigned int length) {
    String msg;
    for (int i = 0; i < length; i++) msg += (char)payload[i];
    String topicStr = String(topic);

    // Xử lý JSON hẹn giờ
    if (topicStr == "garden/schedule") {
        StaticJsonDocument<200> doc;
        DeserializationError error = deserializeJson(doc, msg);

        if (!error) {
            if (doc["enable"] == true) {
                scheduledStartTime = doc["start_time"]; // Lấy timestamp
                scheduledDuration = doc["duration"];    // Lấy số giây bơm
                isScheduleEnabled = true;
                isScheduleRunning = false; // Reset trạng thái
                Serial.printf("Scheduled at: %lu for %d seconds\n", scheduledStartTime, scheduledDuration);
            } else {
                isScheduleEnabled = false;
                setPumpState(false); // Tắt bơm ngay nếu hủy lịch
                Serial.println("Schedule Cancelled");
            }
        }
    }
        // Các lệnh cũ
    else if (topicStr == "pump/control" && systemMode == 0) {
        if (msg == "on") setPumpState(true);
        if (msg == "off") setPumpState(false);
    }
    else if (topicStr == "settings/mode") systemMode = msg.toInt();
    else if (topicStr == "settings/soil_threshold") soilThreshold = msg.toInt();
    else if (topicStr == "settings/min_water") minWaterPercent = msg.toInt();
    else if (topicStr == "settings/tank_height") tankHeight = msg.toInt();
    else if (topicStr == "settings/water_offset") waterOffset = msg.toInt();
}

void reconnect_mqtt() {
    while (!client.connected()) {
        if (client.connect(mqttClientID, mqttUser, mqttPassword)) {
            client.subscribe("pump/control");
            client.subscribe("settings/#");
            client.subscribe("garden/schedule"); // Đăng ký topic mới
        } else delay(3000);
    }
}

void measureWaterLevel() {
    digitalWrite(TRIG_PIN, LOW); delayMicroseconds(2);
    digitalWrite(TRIG_PIN, HIGH); delayMicroseconds(10);
    digitalWrite(TRIG_PIN, LOW);
    long duration = pulseIn(ECHO_PIN, HIGH, 30000);
    if (duration == 0) { waterPercent = -1; return; }
    int distance = duration * 0.034 / 2;
    if (distance >= tankHeight) waterPercent = 0;
    else if (distance <= waterOffset) waterPercent = 100;
    else waterPercent = map(distance, tankHeight, waterOffset, 0, 100);
}

void setPumpState(bool on) {
    if (on) {
        // Chỉ bơm nếu có nước VÀ không bị lỗi quá thời gian
        if (waterPercent >= 0 && waterPercent < minWaterPercent) {
            digitalWrite(PUMP, HIGH); // Tắt bơm (Relay mức cao)
            isPumpRunning = false;
            warningMsg = "LOW_WATER";
        }
        else if (isPumpTimeout) {
            // Nếu đang bị lỗi quá giờ -> Không cho bơm chạy lại
            digitalWrite(PUMP, HIGH);
            isPumpRunning = false;
            warningMsg = "PUMP_TIMEOUT";
        }
        else {
            // Nếu bơm đang tắt mà chuyển sang bật -> Ghi lại thời gian
            if (!isPumpRunning) {
                autoPumpStartTime = millis();
            }
            digitalWrite(PUMP, LOW); // BẬT BƠM (Relay mức thấp)
            isPumpRunning = true;
            warningMsg = "OK";
        }
    } else {
        digitalWrite(PUMP, HIGH); // TẮT BƠM
        isPumpRunning = false;
        warningMsg = "OK";
        // Reset thời gian đếm khi tắt bơm bình thường
        autoPumpStartTime = 0;
    }
}

void handleSchedule() {
    if (!isScheduleEnabled) return;

    time_t now = time(nullptr); // Lấy giờ hiện tại (Unix timestamp)

    // 1. Đến giờ hẹn -> Bật bơm
    if (!isScheduleRunning && now >= scheduledStartTime && now < (scheduledStartTime + scheduledDuration)) {
        Serial.println("Schedule Start!");
        setPumpState(true);
        isScheduleRunning = true;
    }

    // 2. Hết giờ hẹn -> Tắt bơm
    if (isScheduleRunning && now >= (scheduledStartTime + scheduledDuration)) {
        Serial.println("Schedule Finished!");
        setPumpState(false);
        isScheduleRunning = false;
        isScheduleEnabled = false; // Hủy lịch sau khi chạy xong (One-shot)
        // Nếu muốn lặp lại hàng ngày, cần logic cộng thêm 86400 giây vào scheduledStartTime tại đây
    }
}

// ... (Giữ nguyên hàm handleFirebase) ...
void handleFirebase(unsigned long now) {
    if (!isnan(temp) && !isnan(humi) && waterPercent >= 0) {
        countSamples++;
    }

    if (now - lastFirebaseTime >= FIREBASE_INTERVAL && countSamples > 0) {
        lastFirebaseTime = now;
        FirebaseJson json;
        float temp1Decimal = round(temp * 10) / 10.0;
        json.set("value", temp1Decimal);

        if(Firebase.RTDB.pushJSON(&fbData, "/history/temperature", &json)) {
            Serial.println("Pushed Temp OK");
        } else {
            Serial.printf("Firebase Error: %s\n", fbData.errorReason().c_str());
        }
        float humi1Decimal = round(humi * 10) / 10.0;
        json.set("value", humi1Decimal);
        Firebase.RTDB.pushJSON(&fbData, "/history/humidity", &json);
        json.set("value", soilPercent);
        Firebase.RTDB.pushJSON(&fbData, "/history/soil", &json);

        json.set("value", waterPercent);
        Firebase.RTDB.pushJSON(&fbData, "/history/water", &json);

        countSamples = 0;
    }
}

void setup() {
    Serial.begin(115200);
    pinMode(PUMP, OUTPUT); digitalWrite(PUMP, HIGH); // Mặc định tắt
    pinMode(TRIG_PIN, OUTPUT); pinMode(ECHO_PIN, INPUT);
    dht.begin();

    // Gọi hàm setup Wifi và Time mới
    setup_wifi_and_time();

    // SSL config
    espClient.setInsecure();
    espClient.setBufferSizes(512, 512);
    client.setServer(mqttServer, mqttPort);
    client.setCallback(callback);

    fbConfig.database_url = FIREBASE_HOST;
    fbConfig.signer.tokens.legacy_token = FIREBASE_AUTH;
    fbConfig.timeout.wifiReconnect = 10000;
    fbConfig.timeout.socketConnection = 20000;
    fbConfig.timeout.sslHandshake = 20000;

    Firebase.begin(&fbConfig, &fbAuth);
    Firebase.reconnectWiFi(true);
    fbData.setBSSLBufferSize(512, 512);
}

float dailyMinTemp = 100.0;
float dailyMaxTemp = -100.0;
float dailyMinHumi = 100.0, dailyMaxHumi = -100.0; // Thêm cho Độ ẩm không khí
int dailyMinSoil = 100, dailyMaxSoil = 0;          // Thêm cho Độ ẩm đất
int currentDay = -1; // Để theo dõi ngày hiện tại

void loop() {
    if (!client.connected()) reconnect_mqtt();
    client.loop();

    unsigned long now = millis();

    // Nếu bơm đang chạy, kiểm tra xem đã chạy quá lâu chưa
    if (isPumpRunning && (millis() - autoPumpStartTime > MAX_PUMP_RUNTIME)) {
        Serial.println("NGUY HIỂM: Bơm chạy quá lâu!");
        isPumpTimeout = true; // Bật cờ lỗi
        setPumpState(false);  // Tắt bơm ngay lập tức
    }

    // Khi chuyển sang Manual (Mode 0), reset lỗi để dùng lại được
    if (systemMode == 0) {
        isPumpTimeout = false;
    }

    if (now - lastPublishTime > 2000) {
        lastPublishTime = now;
        temp = dht.readTemperature();
        humi = dht.readHumidity();
        measureWaterLevel();
        soilPercent = map(analogRead(AOUT_PIN), 1024, 0, 0, 100);

        time_t t_now = time(nullptr);
        struct tm* timeinfo = localtime(&t_now);

        if (currentDay != timeinfo->tm_mday) {
            currentDay = timeinfo->tm_mday;
            dailyMinTemp = dailyMaxTemp = (isnan(temp) ? 0 : temp);
            dailyMinHumi = dailyMaxHumi = (isnan(humi) ? 0 : humi);
            dailyMinSoil = dailyMaxSoil = soilPercent;
        } else {
            if (!isnan(temp)) {
                if (temp < dailyMinTemp) dailyMinTemp = temp;
                if (temp > dailyMaxTemp) dailyMaxTemp = temp;
            }
            if (!isnan(humi)) {
                if (humi < dailyMinHumi) dailyMinHumi = humi;
                if (humi > dailyMaxHumi) dailyMaxHumi = humi;
            }
            if (soilPercent < dailyMinSoil) dailyMinSoil = soilPercent;
            if (soilPercent > dailyMaxSoil) dailyMaxSoil = soilPercent;
        }

        if (systemMode == 1 && !isScheduleRunning) {
            int startPoint = soilThreshold - hysteresis;
            if (startPoint < 0) startPoint = 0;
            if (soilPercent <= startPoint) {
                // Chỉ bật nếu chưa bị lỗi Timeout
                if (!isPumpTimeout) {
                    setPumpState(true);
                }
            }
            else if (soilPercent >= soilThreshold) {
                setPumpState(false);
                // Nếu tắt thành công do đủ ẩm -> Xóa lỗi Timeout (nếu có) để lần sau chạy tiếp
                isPumpTimeout = false;
            }

        }
        String json = "{";
        json += "\"temp\":" + String(isnan(temp) ? 0 : temp, 1) + ",";
        json += "\"humi\":" + String(isnan(humi) ? 0 : humi, 1) + ",";
        json += "\"soil\":" + String(soilPercent) + ",";
        json += "\"water\":" + String(waterPercent) + ",";
        json += "\"min_temp\":" + String(dailyMinTemp, 1) + ",";
        json += "\"max_temp\":" + String(dailyMaxTemp, 1) + ",";
        json += "\"min_humi\":" + String(dailyMinHumi, 1) + ",";
        json += "\"max_humi\":" + String(dailyMaxHumi, 1) + ",";
        json += "\"min_soil\":" + String(dailyMinSoil) + ",";
        json += "\"max_soil\":" + String(dailyMaxSoil) + ",";
        json += "\"pumpState\":\"" + String(isPumpRunning ? "ON" : "OFF") + "\",";
        json += "\"warning\":\"" + warningMsg + "\"";
        json += "}";
        client.publish("sensor/data", json.c_str());
    }

    handleSchedule();
    handleFirebase(now);
}