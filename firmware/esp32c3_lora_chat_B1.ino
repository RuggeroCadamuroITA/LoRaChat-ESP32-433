/* Nodo B1 - ESP32-C3 + RA-02 433MHz + WiFi Web UI */
#include <SPI.h>
#include <LoRa.h>
#include <WiFi.h>
#include <WebServer.h>
#include <ElegantOTA.h>

#define NODE_ID "B1"
#define DEFAULT_PEER "A1"

// WiFi AP
const char* AP_SSID = "LoRa Communication";
const char* AP_PASS = "loracomm123";

WebServer server(80);

// LoRa config
static const long LORA_FREQ = 433775000;
static const int  LORA_TX_POWER = 17;
static const long LORA_BW = 125E3;
static const int  LORA_SF = 9;
static const int  LORA_CR = 5;
static const byte LORA_SYNCWORD = 0x12;

// Pins ESP32-C3
static const int PIN_SCK  = 4;
static const int PIN_MISO = 5;
static const int PIN_MOSI = 6;
static const int PIN_SS   = 7;
static const int PIN_RST  = 2;
static const int PIN_DIO0 = 3;

static const uint32_t ACK_TIMEOUT_MS = 800;
static const int MAX_RETRY = 3;

uint32_t localSeq = 1;
bool waitingAck = false;
String pendingTo = "";
String pendingPayload = "";
uint32_t pendingSeq = 0;
uint32_t ackDeadline = 0;
int retryCount = 0;
String serialLine;

// Bench state
bool benchRunning = false;
int benchIntervalMs = 1;
int benchWarnMs = 20;
int benchCriticalMs = 50;
int benchPacketCount = 0;
int benchWarnCount = 0;
int benchCriticalCount = 0;
int benchLastDtMs = -1;
int benchMaxDtMs = -1;

// Web buffers
String webLog = "";
String webChat = "";
String webBenchSummary = "Bench: inattivo";

void sendPacketRaw(const String& frame) {
  LoRa.beginPacket();
  LoRa.print(frame);
  LoRa.endPacket();
}

String esc(String s) {
  s.replace("|", "/");
  s.replace("\n", " ");
  s.replace("\r", " ");
  return s;
}

void sendMsg(const String& to, const String& payload, uint32_t seq, bool retry=false) {
  String frame = "MSG|" + String(NODE_ID) + "|" + to + "|" + String(seq) + "|" + esc(payload);
  sendPacketRaw(frame);
  Serial.println((retry?"[RETRY TX] ":"[TX] ") + frame);
  webLog += "[TX] " + frame + "\n";
}

void sendAck(const String& to, uint32_t seq) {
  String frame = "ACK|" + String(NODE_ID) + "|" + to + "|" + String(seq) + "|ok";
  sendPacketRaw(frame);
  Serial.println("[ACK TX] " + frame);
  webLog += "[ACK TX] " + frame + "\n";
}

void startPending(const String& to, const String& payload) {
  pendingTo = to; pendingPayload = payload; pendingSeq = localSeq++;
  retryCount = 0; waitingAck = true; ackDeadline = millis() + ACK_TIMEOUT_MS;
  sendMsg(pendingTo, pendingPayload, pendingSeq, false);
}

void handleRetryTimeout() {
  if(!waitingAck) return;
  if((int32_t)(millis() - ackDeadline) < 0) return;
  if(retryCount < MAX_RETRY) {
    retryCount++; ackDeadline = millis() + ACK_TIMEOUT_MS;
    sendMsg(pendingTo, pendingPayload, pendingSeq, true);
  } else {
    Serial.println("[FAIL] Nessun ACK");
    webLog += "[FAIL] Nessun ACK\n";
    waitingAck = false;
  }
}

void processIncoming(const String& frame, int rssi, float snr) {
  int p1=frame.indexOf('|'), p2=frame.indexOf('|',p1+1), p3=frame.indexOf('|',p2+1), p4=frame.indexOf('|',p3+1);
  if(p1<0||p2<0||p3<0||p4<0) { Serial.println("[RX INVALID] "+frame); webLog += "[RX INVALID] " + frame + "\n"; return; }

  String type=frame.substring(0,p1), from=frame.substring(p1+1,p2), to=frame.substring(p2+1,p3);
  uint32_t seq=(uint32_t)frame.substring(p3+1,p4).toInt();
  String payload=frame.substring(p4+1);

  if(to!=String(NODE_ID) && to!="ALL") return;

  if(type=="MSG") {
    Serial.print("[RX MSG] from="); Serial.print(from);
    Serial.print(" seq="); Serial.print(seq);
    Serial.print(" rssi="); Serial.print(rssi);
    Serial.print(" snr="); Serial.print(snr,1);
    Serial.print(" text="); Serial.println(payload);
    webLog += "[RX MSG] from=" + from + " text=" + payload + "\n";
    webChat += from + ": " + payload + "\n";
    sendAck(from, seq);
  } else if(type=="ACK") {
    Serial.print("[RX ACK] from="); Serial.print(from);
    Serial.print(" seq="); Serial.println(seq);
    webLog += "[RX ACK] from=" + from + " seq=" + String(seq) + "\n";
    if(waitingAck && seq==pendingSeq && from==pendingTo) { waitingAck=false; Serial.println("[OK] ACK ricevuto"); webLog += "[OK] ACK ricevuto\n"; }
  }
}

void handleBenchLine(const String& line) {
  if (!line.contains("[BENCH TX]")) return;
  benchPacketCount++;

  int idx = line.indexOf("dt_ms=");
  if (idx >= 0) {
    int end = line.indexOf(' ', idx);
    if (end < 0) end = line.length();
    String valStr = line.substring(idx+6, end);
    int value = valStr.toInt();
    if (value >= 0) {
      benchLastDtMs = value;
      if (benchMaxDtMs < value) benchMaxDtMs = value;
      if (value >= benchCriticalMs) benchCriticalCount++;
      else if (value >= benchWarnMs) benchWarnCount++;
    }
  }
  webBenchSummary = "Bench: " + String(benchRunning?"attivo":"inattivo") +
                    " | int=" + String(benchIntervalMs) + "ms" +
                    " | packets=" + String(benchPacketCount) +
                    " | W=" + String(benchWarnCount) + " C=" + String(benchCriticalCount) +
                    " | last=" + String(benchLastDtMs) + "ms" +
                    " | max=" + String(benchMaxDtMs) + "ms";
}

void sendBenchMsg() {
  uint32_t nowMs = millis();
  static uint32_t benchLastTxMs = 0;
  static uint32_t benchLastDeltaMs = 0;

  if (benchLastTxMs == 0) benchLastDeltaMs = 0;
  else benchLastDeltaMs = nowMs - benchLastTxMs;

  benchLastTxMs = nowMs;
  benchPacketCount++;

  String payload = "BENCH#" + String(benchPacketCount) + " dt_ms=" + String(benchLastDeltaMs);
  String frame = "MSG|" + String(NODE_ID) + "|" + DEFAULT_PEER + "|" + String(localSeq++) + "|" + payload;
  sendPacketRaw(frame);

  Serial.print("[BENCH TX] n="); Serial.print(benchPacketCount);
  Serial.print(" dt_ms="); Serial.println(benchLastDeltaMs);
  webLog += "[BENCH TX] n=" + String(benchPacketCount) + " dt_ms=" + String(benchLastDeltaMs) + "\n";
}

void setupLoRa() {
  SPI.begin(PIN_SCK, PIN_MISO, PIN_MOSI, PIN_SS);
  LoRa.setPins(PIN_SS, PIN_RST, PIN_DIO0);
  if (!LoRa.begin(LORA_FREQ)) {
    Serial.println("[FATAL] LoRa begin fallito");
    while (true) delay(1000);
  }
  LoRa.setTxPower(LORA_TX_POWER);
  LoRa.setSignalBandwidth(LORA_BW);
  LoRa.setSpreadingFactor(LORA_SF);
  LoRa.setCodingRate4(LORA_CR);
  LoRa.setSyncWord(LORA_SYNCWORD);
  LoRa.enableCrc();
  Serial.println("[OK] LoRa inizializzato");
}

void setupWiFi() {
  WiFi.softAP(AP_SSID, AP_PASS);
  Serial.print("AP IP: ");
  Serial.println(WiFi.softAPIP());

  server.on("/", HTTP_GET, []() {
    String html = R"rawliteral(
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>LoRa Communication</title>
<style>
body{margin:0;background:#000;color:#eee;font-family:system-ui, sans-serif}
.header{padding:10px 12px;background:#111;border-bottom:1px solid #222}
.title{font-size:18px;font-weight:bold}
.status{color:#aaa;font-size:13px;margin-top:4px}
.section{padding:10px 12px;border-bottom:1px solid #222}
.section-title{font-weight:bold;margin-bottom:8px;color:#ccc}
.btn{background:#1a1a1a;color:#fff;border:1px solid #333;padding:8px 12px;cursor:pointer}
.btn:hover{background:#222}
.row{display:flex;gap:8px;flex-wrap:wrap}
.row .btn{flex:1}
.chat{height:220px;overflow:auto;background:#0d0d0d;padding:10px;font-family:monospace}
.log{height:120px;overflow:auto;background:#080808;padding:10px;font-family:monospace;font-size:12px}
.input-row{display:flex;gap:8px;margin-top:8px}
.input-row input{flex:1;padding:8px;background:#121212;color:#fff;border:1px solid #333}
.summary{color:#9ad1ff;font-size:12px;margin-top:6px}
</style>
</head>
<body>
<div class="header">
  <div class="title">LoRa Communications</div>
  <div class="status" id="status">Stato: non connesso</div>
</div>

<div class="section">
  <div class="section-title">Connessione</div>
  <div class="row">
    <button class="btn" onclick="send('/connect')">Connetti</button>
    <button class="btn" onclick="send('/disconnect')">Disconnetti</button>
  </div>
</div>

<div class="section">
  <div class="section-title">Chat</div>
  <div class="chat" id="chat"></div>
  <div class="input-row">
    <input id="msg" placeholder="Messaggio">
    <button class="btn" onclick="sendMsg()">Invia</button>
  </div>
</div>

<div class="section">
  <div class="section-title">Bench</div>
  <div class="summary" id="benchSummary">Bench: inattivo</div>
  <div class="row">
    <button class="btn" onclick="send('/bench?ms=1')">Bench 1ms</button>
    <button class="btn" onclick="send('/bench?ms=10')">Bench 10ms</button>
    <button class="btn" onclick="send('/bench?ms=50')">Bench 50ms</button>
    <button class="btn" onclick="send('/benchstop')">Stop</button>
  </div>
</div>

<div class="section">
  <div class="section-title">Debug</div>
  <div class="log" id="log"></div>
</div>

<script>
function send(path){fetch(path).then(r=>r.text()).then(t=>{});}
function sendMsg(){
  var m=document.getElementById('msg').value;
  if(!m)return;
  fetch('/send?msg='+encodeURIComponent(m)).then(r=>r.text()).then(t=>{});
  document.getElementById('msg').value='';
}
function poll(){
  fetch('/state').then(r=>r.json()).then(s=>{
    document.getElementById('status').innerText='Stato: '+(s.connected?'connesso':'non connesso');
    document.getElementById('chat').innerText=s.chat;
    document.getElementById('log').innerText=s.log;
    document.getElementById('benchSummary').innerText=s.bench;
  });
}
setInterval(poll, 500);
</script>
</body>
</html>
)rawliteral";
    server.send(200, "text/html", html);
  });

  server.on("/send", HTTP_GET, []() {
    String msg = server.arg("msg");
    if (msg.length() > 0) {
      if (!waitingAck) startPending(DEFAULT_PEER, msg);
      webChat += "TU: " + msg + "\n";
    }
    server.send(200, "text/plain", "ok");
  });

  server.on("/bench", HTTP_GET, []() {
    String msStr = server.arg("ms");
    int ms = msStr.toInt();
    if (ms < 1) ms = 1;
    benchIntervalMs = ms;
    benchRunning = true;
    benchPacketCount = 0;
    benchWarnCount = 0;
    benchCriticalCount = 0;
    benchLastDtMs = -1;
    benchMaxDtMs = -1;
    server.send(200, "text/plain", "bench started");
  });

  server.on("/benchstop", HTTP_GET, []() {
    benchRunning = false;
    server.send(200, "text/plain", "bench stopped");
  });

  server.on("/connect", HTTP_GET, []() {
    server.send(200, "text/plain", "connect");
  });

  server.on("/disconnect", HTTP_GET, []() {
    server.send(200, "text/plain", "disconnect");
  });

  server.on("/state", HTTP_GET, []() {
    String json = "{";
    json += "\"connected\":true,";
    json += "\"chat\":" + quote(webChat) + ",";
    json += "\"log\":" + quote(webLog) + ",";
    json += "\"bench\":" + quote(webBenchSummary);
    json += "}";
    server.send(200, "application/json", json);
  });

  ElegantOTA.begin(&server);
  server.begin();
}

String quote(const String& s) {
  String out = "\"";
  for (char c : s) {
    if (c == '"') out += "\\\"";
    else if (c == '\\') out += "\\\\";
    else if (c == '\n') out += "\\n";
    else if (c == '\r') continue;
    else out += c;
  }
  out += "\"";
  return out;
}

void setup() {
  Serial.begin(115200);
  delay(1500);
  Serial.println("Nodo B1 pronto (WiFi + LoRa)");
  setupLoRa();
  setupWiFi();
}

void loop() {
  server.handleClient();
  ElegantOTA.loop();

  int packetSize = LoRa.parsePacket();
  if (packetSize) {
    String frame;
    while (LoRa.available()) frame += (char)LoRa.read();
    int rssi = LoRa.packetRssi();
    float snr = LoRa.packetSnr();
    processIncoming(frame, rssi, snr);
  }

  if (benchRunning) {
    static uint32_t benchLastTxMs = 0;
    uint32_t now = millis();
    if ((uint32_t)(now - benchLastTxMs) >= (uint32_t)benchIntervalMs) {
      benchLastTxMs = now;
      sendBenchMsg();
    }
  }

  handleRetryTimeout();
}
