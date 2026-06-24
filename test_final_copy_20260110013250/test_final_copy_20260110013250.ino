#include <ESP8266WiFi.h>
#include <PubSubClient.h>
#include <DHT.h>
#include <WiFiClientSecure.h>
#include <WiFiManager.h>
#include <Firebase_ESP_Client.h>
#include <ArduinoJson.h>
#include <time.h>

// ===== FIREBASE CONFIG =====
#define FIREBASE_HOST "roject-5e33a-default-rtdb.asia-southeast1.firebasedatabase.app"
#define FIREBASE_AUTH "aiE6xPHT0IqQGesppchM0T1b3LJuaKXAQqttDCmD"

// ===== PIN CONFIG =====
#define AOUT_PIN A0
#define PUMP D2
#define DHTPIN D4
#define DHTTYPE DHT11

DHT dht(DHTPIN, DHTTYPE);

// ===== MQTT =====
WiFiClientSecure espClient;
PubSubClient client(espClient);

const char* mqttServer = "29a054459fb4440ba2312230bca71782.s1.eu.hivemq.cloud";
const int mqttPort = 8883;
const char* mqttClientID = "ESP8266_SmartGarden";
const char* mqttUser = "huybui1012";
const char* mqttPassword = "Huybui123";

// ===== FIREBASE OBJECT =====
FirebaseData fbData;
FirebaseAuth fbAuth;
FirebaseConfig fbConfig;

unsigned long lastPublishTime = 0;
unsigned long lastFirebaseTime = 0;

int systemMode = 0; // 0: Manual, 1: Auto
bool isPumpRunning = false;
String warningMsg = "OK";

unsigned long scheduledStartTime = 0;
int scheduledDuration = 0;
bool isScheduleEnabled = false;
bool isScheduleRunning = false;

bool isPumpTimeout = false;
unsigned long autoPumpStartTime = 0;
const unsigned long MAX_PUMP_RUNTIME = 60000;

int soilThreshold = 30;
int hysteresis = 10;

int soilPercent = 0;
float temp = 0;
float humi = 0;

float sumTemp = 0, sumHumi = 0, sumSoil = 0;
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

    for (int i = 0; i < length; i++) {
        msg += (char)payload[i];
    }

    String topicStr = String(topic);

    Serial.print("MQTT Topic: ");
    Serial.println(topicStr);
    Serial.print("MQTT Message: ");
    Serial.println(msg);

    if (topicStr == "garden/schedule") {
        StaticJsonDocument<200> doc;
        DeserializationError error = deserializeJson(doc, msg);

        if (!error) {
            if (doc["enable"] == true) {
                scheduledStartTime = doc["start_time"];
                scheduledDuration = doc["duration"];
                isScheduleEnabled = true;
                isScheduleRunning = false;

                Serial.printf(
                    "Scheduled at: %lu for %d seconds\n",
                    scheduledStartTime,
                    scheduledDuration
                );
            } else {
                isScheduleEnabled = false;
                setPumpState(false);
                Serial.println("Schedule Cancelled");
            }
        } else {
            Serial.print("Schedule JSON error: ");
            Serial.println(error.c_str());
        }
    }
    else if (topicStr == "pump/control" && systemMode == 0) {
        if (msg == "on") setPumpState(true);
        if (msg == "off") setPumpState(false);
    }
    else if (topicStr == "settings/mode") {
        systemMode = msg.toInt();

        Serial.print("System mode changed to: ");
        Serial.println(systemMode == 0 ? "MANUAL" : "AUTO");
    }
    else if (topicStr == "settings/soil_threshold") {
        soilThreshold = msg.toInt();

        Serial.print("Soil threshold changed to: ");
        Serial.println(soilThreshold);
    }
}

void reconnect_mqtt() {
    while (!client.connected()) {
        Serial.println("Connecting to MQTT...");

        if (client.connect(mqttClientID, mqttUser, mqttPassword)) {
            Serial.println("MQTT Connected");

            client.subscribe("pump/control");
            client.subscribe("settings/#");
            client.subscribe("garden/schedule");

            Serial.println("Subscribed:");
            Serial.println("- pump/control");
            Serial.println("- settings/#");
            Serial.println("- garden/schedule");
        } else {
            Serial.print("MQTT failed, state = ");
            Serial.println(client.state());
            delay(3000);
        }
    }
}

void setPumpState(bool on) {
    if (on) {
        if (isPumpTimeout) {
            // Relay logic kich muc cao:
            // LOW = tat bom
            digitalWrite(PUMP, LOW);
            isPumpRunning = false;
            warningMsg = "PUMP_TIMEOUT";

            Serial.println("MESSAGE: Khong the bat bom vi PUMP_TIMEOUT");
        }
        else {
            if (!isPumpRunning) {
                autoPumpStartTime = millis();
            }

            // Relay logic kich muc cao:
            // HIGH = bat bom
            digitalWrite(PUMP, HIGH);
            isPumpRunning = true;
            warningMsg = "OK";

            Serial.println("MESSAGE: MAY BOM DA BAT");
        }
    } else {
        // Relay logic kich muc cao:
        // LOW = tat bom
        digitalWrite(PUMP, LOW);
        isPumpRunning = false;
        warningMsg = "OK";
        autoPumpStartTime = 0;

        Serial.println("MESSAGE: MAY BOM DA TAT");
    }
}

void handleSchedule() {
    if (!isScheduleEnabled) return;

    time_t now = time(nullptr);

    if (!isScheduleRunning &&
        now >= scheduledStartTime &&
        now < scheduledStartTime + scheduledDuration) {

        Serial.println("Schedule Start!");
        setPumpState(true);
        isScheduleRunning = true;
    }

    if (isScheduleRunning &&
        now >= scheduledStartTime + scheduledDuration) {

        Serial.println("Schedule Finished!");
        setPumpState(false);
        isScheduleRunning = false;
        isScheduleEnabled = false;
    }
}

void handleFirebase(unsigned long now) {
    if (!isnan(temp) && !isnan(humi)) {
        countSamples++;
    }

    if (now - lastFirebaseTime >= FIREBASE_INTERVAL && countSamples > 0) {
        lastFirebaseTime = now;

        FirebaseJson json;

        float temp1Decimal = round(temp * 10) / 10.0;
        json.set("value", temp1Decimal);

        if (Firebase.RTDB.pushJSON(&fbData, "/history/temperature", &json)) {
            Serial.println("Pushed Temp OK");
        } else {
            Serial.printf("Firebase Temp Error: %s\n", fbData.errorReason().c_str());
        }

        float humi1Decimal = round(humi * 10) / 10.0;
        json.set("value", humi1Decimal);

        if (Firebase.RTDB.pushJSON(&fbData, "/history/humidity", &json)) {
            Serial.println("Pushed Humi OK");
        } else {
            Serial.printf("Firebase Humi Error: %s\n", fbData.errorReason().c_str());
        }

        json.set("value", soilPercent);

        if (Firebase.RTDB.pushJSON(&fbData, "/history/soil", &json)) {
            Serial.println("Pushed Soil OK");
        } else {
            Serial.printf("Firebase Soil Error: %s\n", fbData.errorReason().c_str());
        }

        countSamples = 0;
    }
}

void setup() {
    Serial.begin(115200);
    delay(1000);

    Serial.println();
    Serial.println("ESP8266 SMART GARDEN START");

    pinMode(PUMP, OUTPUT);

    // Relay logic kich muc cao:
    // LOW = tat bom
    digitalWrite(PUMP, LOW);
    isPumpRunning = false;

    Serial.println("Pump default: OFF");
    Serial.println("Relay logic: HIGH = ON, LOW = OFF");

    dht.begin();

    setup_wifi_and_time();

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

    Serial.println("Setup completed");
}

float dailyMinTemp = 100.0;
float dailyMaxTemp = -100.0;

float dailyMinHumi = 100.0;
float dailyMaxHumi = -100.0;

int dailyMinSoil = 100;
int dailyMaxSoil = 0;

int currentDay = -1;

void loop() {
    if (!client.connected()) {
        reconnect_mqtt();
    }

    client.loop();

    unsigned long now = millis();

    if (isPumpRunning && millis() - autoPumpStartTime > MAX_PUMP_RUNTIME) {
        Serial.println("NGUY HIEM: Bom chay qua lau!");
        isPumpTimeout = true;
        setPumpState(false);
    }

    if (systemMode == 0) {
        isPumpTimeout = false;
    }

    if (now - lastPublishTime > 2000) {
        lastPublishTime = now;

        temp = dht.readTemperature();
        humi = dht.readHumidity();

        int soilRaw = analogRead(AOUT_PIN);
        soilPercent = map(soilRaw, 1024, 0, 0, 100);

        if (soilPercent < 0) soilPercent = 0;
        if (soilPercent > 100) soilPercent = 100;

        time_t t_now = time(nullptr);
        struct tm* timeinfo = localtime(&t_now);

        if (currentDay != timeinfo->tm_mday) {
            currentDay = timeinfo->tm_mday;

            dailyMinTemp = dailyMaxTemp = isnan(temp) ? 0 : temp;
            dailyMinHumi = dailyMaxHumi = isnan(humi) ? 0 : humi;
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
                if (!isPumpTimeout) {
                    Serial.println("AUTO: Dat kho -> BAT BOM");
                    setPumpState(true);
                }
            }
            else if (soilPercent >= soilThreshold) {
                Serial.println("AUTO: Dat du am -> TAT BOM");
                setPumpState(false);
                isPumpTimeout = false;
            }
        }

        String json = "{";
        json += "\"temp\":" + String(isnan(temp) ? 0 : temp, 1) + ",";
        json += "\"humi\":" + String(isnan(humi) ? 0 : humi, 1) + ",";
        json += "\"soil\":" + String(soilPercent) + ",";
        json += "\"min_temp\":" + String(dailyMinTemp, 1) + ",";
        json += "\"max_temp\":" + String(dailyMaxTemp, 1) + ",";
        json += "\"min_humi\":" + String(dailyMinHumi, 1) + ",";
        json += "\"max_humi\":" + String(dailyMaxHumi, 1) + ",";
        json += "\"min_soil\":" + String(dailyMinSoil) + ",";
        json += "\"max_soil\":" + String(dailyMaxSoil) + ",";
        json += "\"pumpState\":\"" + String(isPumpRunning ? "ON" : "OFF") + "\",";
        json += "\"warning\":\"" + warningMsg + "\"";
        json += "}";

        if (client.publish("sensor/data", json.c_str())) {
            Serial.println("MQTT publish OK");
        } else {
            Serial.println("MQTT publish FAILED");
        }

        Serial.println("========== SENSOR DATA ==========");
        Serial.print("Temp: ");
        Serial.println(isnan(temp) ? 0 : temp);

        Serial.print("Humi: ");
        Serial.println(isnan(humi) ? 0 : humi);

        Serial.print("Soil RAW: ");
        Serial.println(soilRaw);

        Serial.print("Soil Percent: ");
        Serial.println(soilPercent);

        Serial.print("Pump State: ");
        Serial.println(isPumpRunning ? "ON" : "OFF");

        Serial.print("System Mode: ");
        Serial.println(systemMode == 0 ? "MANUAL" : "AUTO");

        Serial.print("Warning: ");
        Serial.println(warningMsg);

        Serial.print("JSON: ");
        Serial.println(json);

        Serial.println("=================================");
    }

    handleSchedule();
    handleFirebase(now);
}
