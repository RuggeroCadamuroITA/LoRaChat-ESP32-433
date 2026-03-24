# Collegamenti dettagliati: ESP32-C3 Super Mini <-> RA-02 (SX1278 433 MHz)

## Pin mapping consigliato

| RA-02 | Funzione | ESP32-C3 Super Mini |
|---|---|---|
| 3.3V | Alimentazione | 3V3 |
| GND (uno qualsiasi) | Massa | GND |
| SCK | SPI Clock | GPIO4 |
| MISO | SPI MISO | GPIO5 |
| MOSI | SPI MOSI | GPIO6 |
| NSS | SPI CS/SS | GPIO7 |
| DIO0 | IRQ LoRa principale | GPIO3 |
| RST | Reset modulo LoRa | GPIO2 |

## Pin RA-02 NON necessari all'inizio
- DIO1, DIO2, DIO3, DIO4, DIO5 -> lasciali scollegati per ora

## Note hardware importanti
1. **NON usare 5V sul RA-02**: solo 3.3V.
2. Metti GND comune solido tra ESP32 e RA-02.
3. Tieni i cavi SPI corti (meglio < 10-15 cm).
4. Collega l'antenna prima di trasmettere.
5. Usa antenna **433 MHz** (non 868/915).

## Schema logico (singolo nodo)

- ESP32-C3 riceve testo da USB seriale
- ESP32 invia pacchetto via LoRa RA-02
- RA-02 riceve pacchetti e li riporta all'ESP32
- ESP32 stampa su seriale

## Due nodi
- Nodo 1: ESP32-C3 + RA-02 (NODE_ID = A1)
- Nodo 2: ESP32-C3 + RA-02 (NODE_ID = B1)

I collegamenti elettrici sono identici su entrambi i nodi.
