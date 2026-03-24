/* Nodo A1 - ESP32-C3 + RA-02 433MHz */
#include <SPI.h>
#include <LoRa.h>

#define NODE_ID "B1"
#define DEFAULT_PEER "A1"

static const long LORA_FREQ = 433775000;
static const int  LORA_TX_POWER = 17;
static const long LORA_BW = 125E3;
static const int  LORA_SF = 9;
static const int  LORA_CR = 5;
static const byte LORA_SYNCWORD = 0x12;

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

// ===== BENCH MODE =====
bool benchMode = false;
String benchTarget = DEFAULT_PEER;
uint32_t benchIntervalMs = 1;          // richiesta: ogni 1 ms
uint32_t benchLastTxMs = 0;
uint32_t benchCount = 0;
uint32_t benchLastDeltaMs = 0;
uint32_t benchLastDeltaUs = 0;
uint32_t benchLastTxUs = 0;

void sendPacketRaw(const String& frame){ LoRa.beginPacket(); LoRa.print(frame); LoRa.endPacket(); }
String esc(String s){ s.replace("|", "/"); s.replace("\n"," "); s.replace("\r"," "); return s; }

void sendMsg(const String& to, const String& payload, uint32_t seq, bool retry=false){
  String frame = "MSG|" + String(NODE_ID) + "|" + to + "|" + String(seq) + "|" + esc(payload);
  sendPacketRaw(frame);
  Serial.println((retry?"[RETRY TX] ":"[TX] ") + frame);
}

void sendBenchMsg(){
  uint32_t nowMs = millis();
  uint32_t nowUs = micros();

  if (benchLastTxMs == 0) {
    benchLastDeltaMs = 0;
    benchLastDeltaUs = 0;
  } else {
    benchLastDeltaMs = nowMs - benchLastTxMs;
    benchLastDeltaUs = nowUs - benchLastTxUs;
  }

  benchLastTxMs = nowMs;
  benchLastTxUs = nowUs;

  benchCount++;
  String payload = "BENCH#" + String(benchCount) + " dt_ms=" + String(benchLastDeltaMs) + " dt_us=" + String(benchLastDeltaUs);
  String frame = "MSG|" + String(NODE_ID) + "|" + benchTarget + "|" + String(localSeq++) + "|" + payload;
  sendPacketRaw(frame);

  Serial.print("[BENCH TX] n="); Serial.print(benchCount);
  Serial.print(" dt_ms="); Serial.print(benchLastDeltaMs);
  Serial.print(" dt_us="); Serial.println(benchLastDeltaUs);
}

void sendAck(const String& to, uint32_t seq){
  String frame = "ACK|" + String(NODE_ID) + "|" + to + "|" + String(seq) + "|ok";
  sendPacketRaw(frame);
  Serial.println("[ACK TX] " + frame);
}

void startPending(const String& to, const String& payload){
  pendingTo = to; pendingPayload = payload; pendingSeq = localSeq++;
  retryCount = 0; waitingAck = true; ackDeadline = millis() + ACK_TIMEOUT_MS;
  sendMsg(pendingTo, pendingPayload, pendingSeq, false);
}

void handleRetryTimeout(){
  if(!waitingAck) return;
  if((int32_t)(millis() - ackDeadline) < 0) return;
  if(retryCount < MAX_RETRY){ retryCount++; ackDeadline = millis() + ACK_TIMEOUT_MS; sendMsg(pendingTo, pendingPayload, pendingSeq, true); }
  else { Serial.println("[FAIL] Nessun ACK"); waitingAck = false; }
}

void processIncoming(const String& frame, int rssi, float snr){
  int p1=frame.indexOf('|'), p2=frame.indexOf('|',p1+1), p3=frame.indexOf('|',p2+1), p4=frame.indexOf('|',p3+1);
  if(p1<0||p2<0||p3<0||p4<0){ Serial.println("[RX INVALID] "+frame); return; }

  String type=frame.substring(0,p1), from=frame.substring(p1+1,p2), to=frame.substring(p2+1,p3);
  uint32_t seq=(uint32_t)frame.substring(p3+1,p4).toInt();
  String payload=frame.substring(p4+1);

  if(to!=String(NODE_ID) && to!="ALL") return;

  if(type=="MSG"){
    Serial.print("[RX MSG] from="); Serial.print(from);
    Serial.print(" seq="); Serial.print(seq);
    Serial.print(" rssi="); Serial.print(rssi);
    Serial.print(" snr="); Serial.print(snr,1);
    Serial.print(" text="); Serial.println(payload);
    sendAck(from, seq);
  } else if(type=="ACK"){
    Serial.print("[RX ACK] from="); Serial.print(from);
    Serial.print(" seq="); Serial.println(seq);
    if(waitingAck && seq==pendingSeq && from==pendingTo){ waitingAck=false; Serial.println("[OK] ACK ricevuto"); }
  }
}

void handleBench(){
  if (!benchMode) return;
  if (benchIntervalMs == 0) benchIntervalMs = 1;

  uint32_t now = millis();
  if ((uint32_t)(now - benchLastTxMs) >= benchIntervalMs) {
    sendBenchMsg();
  }
}

void readSerialCommands(){
  while(Serial.available()){
    char c=(char)Serial.read();
    if(c=='\n'){
      serialLine.trim();
      if(serialLine.length()>0){
        // bench commands
        // /bench on
        // /bench on 1
        // /bench on 1 B1
        // /bench off
        if (serialLine.startsWith("/bench ")) {
          if (serialLine == "/bench off") {
            benchMode = false;
            Serial.println("[BENCH] OFF");
          } else if (serialLine.startsWith("/bench on")) {
            benchIntervalMs = 1;
            benchTarget = DEFAULT_PEER;

            // parsing opzionale
            // /bench on <ms> <target>
            int firstSpace = serialLine.indexOf(' ', 10); // dopo '/bench on '
            String tail = "";
            if (serialLine.length() > 10) tail = serialLine.substring(10);
            tail.trim();

            if (tail.length() > 0) {
              int sp = tail.indexOf(' ');
              if (sp < 0) {
                benchIntervalMs = (uint32_t)max(1, tail.toInt());
              } else {
                String msStr = tail.substring(0, sp);
                String tStr = tail.substring(sp + 1);
                msStr.trim(); tStr.trim();
                benchIntervalMs = (uint32_t)max(1, msStr.toInt());
                if (tStr.length() > 0) benchTarget = tStr;
              }
            }

            benchMode = true;
            benchCount = 0;
            benchLastTxMs = 0;
            benchLastTxUs = 0;

            Serial.print("[BENCH] ON interval_ms="); Serial.print(benchIntervalMs);
            Serial.print(" target="); Serial.println(benchTarget);
          } else {
            Serial.println("[ERR] uso bench: /bench on [ms] [target]  oppure  /bench off");
          }
        }
        // normal chat
        else if(serialLine.startsWith("/to ")){
          int sp1=serialLine.indexOf(' ',4);
          if(sp1>0){
            String to=serialLine.substring(4,sp1);
            String msg=serialLine.substring(sp1+1);
            if(!waitingAck) startPending(to,msg); else Serial.println("[BUSY] Attendi ACK");
          } else Serial.println("[ERR] uso: /to <ID> <msg>");
        } else {
          if(!waitingAck) startPending(String(DEFAULT_PEER), serialLine); else Serial.println("[BUSY] Attendi ACK");
        }
      }
      serialLine="";
    } else if(c!='\r') serialLine += c;
  }
}

void setup(){
  Serial.begin(115200); delay(1500);
  Serial.println("Nodo B1 pronto");
  Serial.println("Comandi: /to <ID> <msg> | /bench on [ms] [target] | /bench off");

  SPI.begin(PIN_SCK, PIN_MISO, PIN_MOSI, PIN_SS);
  LoRa.setPins(PIN_SS, PIN_RST, PIN_DIO0);
  if(!LoRa.begin(LORA_FREQ)){ Serial.println("[FATAL] LoRa begin fallito"); while(true) delay(1000); }
  LoRa.setTxPower(LORA_TX_POWER);
  LoRa.setSignalBandwidth(LORA_BW);
  LoRa.setSpreadingFactor(LORA_SF);
  LoRa.setCodingRate4(LORA_CR);
  LoRa.setSyncWord(LORA_SYNCWORD);
  LoRa.enableCrc();
  Serial.println("[OK] LoRa inizializzato");
}

void loop(){
  readSerialCommands();
  int packetSize=LoRa.parsePacket();
  if(packetSize){
    String frame=""; while(LoRa.available()) frame += (char)LoRa.read();
    processIncoming(frame, LoRa.packetRssi(), LoRa.packetSnr());
  }

  handleRetryTimeout();
  handleBench();
}

