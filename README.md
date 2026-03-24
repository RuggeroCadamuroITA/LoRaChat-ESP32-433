# LoRaChat-ESP32-433

Long-range peer-to-peer text chat using **2x ESP32-C3 + 2x RA-02 (SX1278 433MHz)**.

This project is focused on a minimal and reliable stack:
- LoRa data exchange (no display, no keypad)
- USB serial console for testing/chat
- ACK + retry logic for better reliability
- Two dedicated firmware files (Node A1 / Node B1)

## Hardware

- 2x ESP32-C3 Super Mini
- 2x RA-02 SX1278 433MHz
- 2x 433MHz SMA antennas
- 2x u.FL/IPEX -> SMA female pigtails

## Wiring (ESP32-C3 -> RA-02)

| RA-02 | ESP32-C3 |
|---|---|
| 3.3V | 3V3 |
| GND | GND |
| SCK | GPIO4 |
| MISO | GPIO5 |
| MOSI | GPIO6 |
| NSS | GPIO7 |
| DIO0 | GPIO3 |
| RST | GPIO2 |

Detailed docs:
- `wiring/collegamenti_esp32c3_ra02.md`
- `wiring/collegamenti_ascii.txt`

## Firmware files

- `firmware/esp32c3_lora_chat_A1.ino` -> flash on node A1
- `firmware/esp32c3_lora_chat_B1.ino` -> flash on node B1

Both firmwares are functionally the same but already preconfigured with opposite node IDs.

## Serial usage

Open Serial Monitor at `115200` and send:

- `/to B1 ciao` from node A1
- `/to A1 ciao` from node B1

If no `/to` is used, message is sent to the default peer of that node.

## LoRa settings

- Frequency: `433.775 MHz`
- Bandwidth: `125 kHz`
- Spreading Factor: `9`
- Coding Rate: `4/5`
- TX Power: `17 dBm`
- Sync Word: `0x12`

## Amazon parts links (selected)

- ESP32-C3 Super Mini (2pcs): https://www.amazon.it/dp/B0DMNBWTFD
- RA-02 SX1278 433MHz (2pcs): https://www.amazon.it/dp/B07RD2JV7Y
- u.FL/IPEX -> SMA Female pigtails (5pcs): https://www.amazon.it/dp/B0DZ5WM37M
- 433MHz SMA male antennas (2pcs): https://www.amazon.it/dp/B0FT4JBN1Y

## Notes

- RA-02 is **3.3V only** (never use 5V).
- Always connect antenna before transmitting.
- Use 433MHz antenna with 433MHz module.

## Roadmap

- [x] Two-node LoRa chat over serial
- [x] ACK + retry
- [ ] Android app (USB-C serial UI)
- [ ] Packet encryption/authentication layer
- [ ] Optional GPS sharing mode

---

Built by RuggeroCadamuroITA projects workflow.
