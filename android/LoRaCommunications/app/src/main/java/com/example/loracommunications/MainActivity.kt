package com.example.loracommunications

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvChat: TextView
    private lateinit var tvDebugLabel: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var scrollChat: ScrollView
    private lateinit var etMessage: EditText
    private lateinit var tvBenchSummary: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private var usbManager: UsbManager? = null
    private var serialPort: UsbSerialPort? = null
    private var connectedDriver: UsbSerialDriver? = null
    private var readerRunning = false

    private val actionUsbPermission = "com.example.loracommunications.USB_PERMISSION"

    // Bench state
    private var debugVisible = false
    private var benchRunning = false
    private var benchIntervalMs = 1
    private var benchWarnMs = 20
    private var benchCriticalMs = 50
    private val benchLines = mutableListOf<String>()
    private var benchPacketCount = 0
    private var benchWarnCount = 0
    private var benchCriticalCount = 0
    private var benchLastDtMs = -1
    private var benchMaxDtMs = -1
    private var lastAlertMs = 0L

    private val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)

    private var pendingExportText: String = ""
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) exportTextToUri(uri, pendingExportText)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                actionUsbPermission -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (granted && device != null) openSerial(device)
                    else {
                        appendLog("[USB] Permesso negato")
                        setStatus("Stato: permesso USB negato")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    appendLog("[USB] Dispositivo scollegato")
                    disconnectSerial()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        usbManager = getSystemService(USB_SERVICE) as UsbManager

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        tvChat = findViewById(R.id.tvChat)
        tvDebugLabel = findViewById(R.id.tvDebugLabel)
        scrollLog = findViewById(R.id.scrollLog)
        scrollChat = findViewById(R.id.scrollChat)
        etMessage = findViewById(R.id.etMessage)
        tvBenchSummary = findViewById(R.id.tvBenchSummary)

        applySafeInsets()

        findViewById<Button>(R.id.btnConnect).setOnClickListener { connectFirstUsbSerial() }
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener { disconnectSerial() }
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendTypedMessage() }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }

        val filter = IntentFilter().apply {
            addAction(actionUsbPermission)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }

        setStatus("Stato: non connesso")
        setDebugVisible(false)
        updateBenchSummary()
        appendLog("App pronta. Premi 'Connetti'.")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        disconnectSerial()
        tone.release()
        ioExecutor.shutdownNow()
    }

    private fun connectFirstUsbSerial() {
        val manager = usbManager ?: return
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)

        if (drivers.isEmpty()) {
            toast("Nessun device seriale USB trovato")
            appendLog("[USB] Nessun driver trovato")
            return
        }

        val driver = drivers.first()
        val device = driver.device
        connectedDriver = driver

        if (manager.hasPermission(device)) {
            openSerial(device)
            return
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(actionUsbPermission),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.requestPermission(device, pendingIntent)
        appendLog("[USB] Richiesta permesso inviata")
    }

    private fun openSerial(device: UsbDevice) {
        val manager = usbManager ?: return
        val driver = connectedDriver ?: UsbSerialProber.getDefaultProber().findAllDrivers(manager)
            .firstOrNull { it.device.deviceId == device.deviceId }

        if (driver == null) {
            appendLog("[USB] Driver non trovato")
            return
        }

        try {
            val connection = manager.openDevice(device) ?: throw IllegalStateException("Impossibile aprire USB")
            val port = driver.ports.firstOrNull() ?: throw IllegalStateException("Nessuna porta seriale")

            port.open(connection)
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true

            serialPort = port
            setStatus("Stato: connesso (${device.deviceName})")
            appendLog("[USB] Connesso @115200")
            startReaderLoop()
        } catch (t: Throwable) {
            appendLog("[ERR] Connessione fallita: ${t.message}")
            setStatus("Stato: errore connessione")
        }
    }

    private fun disconnectSerial() {
        readerRunning = false
        try { serialPort?.close() } catch (_: Throwable) {}
        serialPort = null
        connectedDriver = null
        benchRunning = false
        updateBenchSummary()
        setStatus("Stato: non connesso")
    }

    private fun startReaderLoop() {
        if (readerRunning) return
        readerRunning = true

        ioExecutor.execute {
            val buffer = ByteArray(2048)
            while (readerRunning) {
                try {
                    val p = serialPort ?: break
                    val n = p.read(buffer, 200)
                    if (n > 0) {
                        val text = String(buffer, 0, n, Charset.forName("UTF-8"))
                        val lines = text.split("\n")
                        for (line in lines) {
                            val clean = line.trim()
                            if (clean.isNotEmpty()) {
                                mainHandler.post {
                                    appendLog(clean)
                                    parseSerialLine(clean)
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    mainHandler.post {
                        appendLog("[ERR] Lettura seriale: ${t.message}")
                        disconnectSerial()
                    }
                    break
                }
            }
        }
    }

    private fun sendTypedMessage() {
        val msg = etMessage.text.toString().trim()
        if (msg.isEmpty()) return
        sendLine(msg)
        if (!msg.startsWith("/")) appendChat("TU", msg)
        etMessage.setText("")
    }

    private fun sendLine(line: String) {
        val p = serialPort
        if (p == null) {
            toast("Non connesso")
            return
        }

        ioExecutor.execute {
            try {
                val data = (line + "\n").toByteArray(Charset.forName("UTF-8"))
                p.write(data, 500)
                mainHandler.post { appendLog("[APP TX] $line") }
            } catch (t: Throwable) {
                mainHandler.post { appendLog("[ERR] Scrittura seriale: ${t.message}") }
            }
        }
    }

    private fun showSettingsDialog() {
        val options = arrayOf(
            "Bench / Stress Test",
            if (debugVisible) "Nascondi Debug" else "Mostra Debug"
        )

        AlertDialog.Builder(this)
            .setTitle("Impostazioni")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showBenchDialog()
                    1 -> setDebugVisible(!debugVisible)
                }
            }
            .show()
    }

    private fun setDebugVisible(show: Boolean) {
        debugVisible = show
        val visibility = if (show) View.VISIBLE else View.GONE
        tvDebugLabel.visibility = visibility
        scrollLog.visibility = visibility
    }

    private fun showBenchDialog() {
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val lblInterval = TextView(this).apply {
            text = "Intervallo invio (ms)   ●"
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        val intervalInput = EditText(this).apply {
            setText(benchIntervalMs.toString())
            hint = "es. 1, 5, 10"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF7A7A7A.toInt())
            setBackgroundColor(0xFF141414.toInt())
            setPadding(20, 20, 20, 20)
        }

        val lblWarn = TextView(this).apply {
            text = "Soglia warning (ms)   ●"
            setTextColor(Color.parseColor("#FFD166"))
            textSize = 13f
            setPadding(0, 14, 0, 0)
        }
        val warnInput = EditText(this).apply {
            setText(benchWarnMs.toString())
            hint = "se dt_ms >= warning -> alert giallo"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF7A7A7A.toInt())
            setBackgroundColor(0xFF141414.toInt())
            setPadding(20, 20, 20, 20)
        }

        val lblCritical = TextView(this).apply {
            text = "Soglia critical (ms)   ●"
            setTextColor(Color.parseColor("#FF5A5A"))
            textSize = 13f
            setPadding(0, 14, 0, 0)
        }
        val criticalInput = EditText(this).apply {
            setText(benchCriticalMs.toString())
            hint = "se dt_ms >= critical -> allarme rosso"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF7A7A7A.toInt())
            setBackgroundColor(0xFF141414.toInt())
            setPadding(20, 20, 20, 20)
        }

        container.addView(lblInterval)
        container.addView(intervalInput)
        container.addView(lblWarn)
        container.addView(warnInput)
        container.addView(lblCritical)
        container.addView(criticalInput)

        val statusText = "Stato bench: " + if (benchRunning) "ATTIVO" else "INATTIVO"

        AlertDialog.Builder(this)
            .setTitle("Bench / Stress Test")
            .setMessage(statusText)
            .setView(container)
            .setPositiveButton(if (benchRunning) "Riavvia" else "Avvia") { _, _ ->
                val ms = max(1, intervalInput.text.toString().trim().toIntOrNull() ?: 1)
                benchWarnMs = max(1, warnInput.text.toString().trim().toIntOrNull() ?: 20)
                benchCriticalMs = max(benchWarnMs + 1, criticalInput.text.toString().trim().toIntOrNull() ?: 50)
                startBench(ms)
            }
            .setNegativeButton("Stop") { _, _ -> stopBench() }
            .setNeutralButton("Esporta .txt") { _, _ -> exportBenchTxt() }
            .show()
    }

    private fun startBench(ms: Int) {
        benchIntervalMs = max(1, ms)
        benchRunning = true
        benchLines.clear()
        benchPacketCount = 0
        benchWarnCount = 0
        benchCriticalCount = 0
        benchLastDtMs = -1
        benchMaxDtMs = -1
        updateBenchSummary()
        sendLine("/bench on $benchIntervalMs")
        appendLog("[BENCH] Avviato con intervallo ${benchIntervalMs}ms")
    }

    private fun stopBench() {
        if (!benchRunning) {
            appendLog("[BENCH] Già fermo")
            return
        }
        benchRunning = false
        sendLine("/bench off")
        updateBenchSummary()
        appendLog("[BENCH] Fermato")
    }

    private fun parseSerialLine(line: String) {
        if (line.contains("[RX MSG]") && line.contains("text=")) {
            val from = Regex("from=([^\\s]+)").find(line)?.groupValues?.get(1) ?: "NODE"
            val text = line.substringAfter("text=", "").trim()
            if (text.isNotEmpty()) appendChat(from, text)
        }

        if (line.contains("[OK] ACK ricevuto")) {
            appendChat("SYS", "Messaggio consegnato")
        }

        handleBenchLine(line)
    }

    private fun handleBenchLine(line: String) {
        if (!line.contains("[BENCH TX]")) return
        benchLines.add(line)
        benchPacketCount++

        val dtMsRegex = Regex("dt_ms=(\\d+)")
        val match = dtMsRegex.find(line)
        if (match != null) {
            val value = match.groupValues[1].toIntOrNull() ?: -1
            if (value >= 0) {
                benchLastDtMs = value
                if (benchMaxDtMs < value) benchMaxDtMs = value

                when {
                    value >= benchCriticalMs -> {
                        benchCriticalCount++
                        triggerAlert(critical = true)
                    }
                    value >= benchWarnMs -> {
                        benchWarnCount++
                        triggerAlert(critical = false)
                    }
                }
            }
        }

        updateBenchSummary()
    }

    private fun triggerAlert(critical: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastAlertMs < 1200) return
        lastAlertMs = now

        if (critical) {
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 180)
            mainHandler.post { toast("Bench CRITICAL") }
        } else {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        }
    }

    private fun updateBenchSummary() {
        val state = if (benchRunning) "attivo" else "inattivo"
        val last = if (benchLastDtMs >= 0) "${benchLastDtMs}ms" else "-"
        val maxV = if (benchMaxDtMs >= 0) "${benchMaxDtMs}ms" else "-"
        tvBenchSummary.text = "Bench: $state | int=${benchIntervalMs}ms | packets=$benchPacketCount | W=$benchWarnCount C=$benchCriticalCount | last=$last | max=$maxV"

        val color = when {
            benchLastDtMs >= benchCriticalMs -> Color.parseColor("#FF5A5A")
            benchLastDtMs >= benchWarnMs -> Color.parseColor("#FFD166")
            else -> Color.parseColor("#9AD1FF")
        }
        tvBenchSummary.setTextColor(color)
    }

    private fun exportBenchTxt() {
        val now = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val header = buildString {
            appendLine("LoRa Bench Export")
            appendLine("Date: $now")
            appendLine("Interval ms: $benchIntervalMs")
            appendLine("Packets: $benchPacketCount")
            appendLine("Last dt_ms: $benchLastDtMs")
            appendLine("Max dt_ms: $benchMaxDtMs")
            appendLine("---")
        }

        pendingExportText = header + benchLines.joinToString("\n")
        exportLauncher.launch("bench_results_$now.txt")
    }

    private fun exportTextToUri(uri: Uri, content: String) {
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charset.forName("UTF-8")))
            }
            toast("Export completato")
            appendLog("[BENCH] Export salvato")
        } catch (t: Throwable) {
            appendLog("[ERR] Export fallito: ${t.message}")
        }
    }

    private fun appendChat(from: String, text: String) {
        val line = when (from) {
            "TU" -> "Tu: $text"
            "SYS" -> "Sistema: $text"
            else -> "$from: $text"
        }
        val current = tvChat.text.toString()
        val next = if (current.isEmpty()) line else "$current\n$line"
        tvChat.text = next.takeLast(20000)
        scrollChat.post { scrollChat.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun appendLog(text: String) {
        if (text.isBlank()) return
        val current = tvLog.text.toString()
        val next = if (current.isEmpty()) text else "$current\n$text"
        tvLog.text = next.takeLast(60000)
        scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun applySafeInsets() {
        val root = findViewById<android.view.View>(R.id.main)
        val initialLeft = root.paddingLeft
        val initialTop = root.paddingTop
        val initialRight = root.paddingRight
        val initialBottom = root.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = max(bars.bottom, ime.bottom)

            v.setPadding(
                initialLeft + bars.left,
                initialTop + bars.top,
                initialRight + bars.right,
                initialBottom + bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun setStatus(value: String) {
        tvStatus.text = value
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
