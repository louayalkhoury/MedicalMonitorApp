package com.medicalmonitor.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // UUID لبروتوكول SPP (Serial Port Profile) - HC-05
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // اسم جهاز HC-05 الافتراضي - يمكن تغييره إذا كان مختلفاً
    private val HC05_DEVICE_NAME = "HC-05"

    // أو استخدم MAC Address مباشرة (غيّر القيمة حسب جهازك)
    // مثال: private val HC05_MAC = "00:11:22:33:44:55"
    private var hc05Mac: String? = null

    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var readThread: Thread? = null
    @Volatile private var isRunning = false

    private lateinit var tvHeartRate: TextView
    private lateinit var tvSpO2: TextView
    private lateinit var tvTemperature: TextView
    private lateinit var tvStatus: TextView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvHeartRate = findViewById(R.id.tvHeartRate)
        tvSpO2 = findViewById(R.id.tvSpO2)
        tvTemperature = findViewById(R.id.tvTemperature)
        tvStatus = findViewById(R.id.tvStatus)

        updateStatus("جاري الاتصال...")
        connectBluetooth()
    }

    @SuppressLint("MissingPermission")
    private fun connectBluetooth() {
        executor.execute {
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter == null) {
                    showError("الجهاز لا يدعم Bluetooth")
                    return@execute
                }
                if (!adapter.isEnabled) {
                    showError("يرجى تفعيل Bluetooth")
                    return@execute
                }

                val device = findHC05Device(adapter)
                if (device == null) {
                    showError("لم يتم العثور على HC-05. قم بمزامنته أولاً من إعدادات Bluetooth")
                    return@execute
                }

                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothSocket?.connect()

                inputStream = bluetoothSocket?.inputStream
                isRunning = true

                mainHandler.post {
                    updateStatus("متصل ✓")
                    Toast.makeText(this@MainActivity, "متصل بنجاح", Toast.LENGTH_SHORT).show()
                }

                startReadingData()

            } catch (e: Exception) {
                if (e is SecurityException) {
                    showError("يحتاج التطبيق إذن Bluetooth. راجع إعدادات التطبيق.")
                    return@execute
                }
                // محاولة الطريقة البديلة للاتصال (لبعض أجهزة HC-05)
                try {
                    val device = BluetoothAdapter.getDefaultAdapter()?.bondedDevices
                        ?.firstOrNull { (it.name ?: "").equals(HC05_DEVICE_NAME, ignoreCase = true) || it.address == hc05Mac }
                    if (device != null) {
                        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        bluetoothSocket = method.invoke(device, 1) as BluetoothSocket
                        bluetoothSocket?.connect()
                        inputStream = bluetoothSocket?.inputStream
                        isRunning = true
                        mainHandler.post {
                            updateStatus("متصل ✓")
                            Toast.makeText(this@MainActivity, "متصل بنجاح", Toast.LENGTH_SHORT).show()
                        }
                        startReadingData()
                    } else {
                        showError("فشل الاتصال: ${e.message}")
                    }
                } catch (e2: Exception) {
                    if (e2 is SecurityException) {
                        showError("يحتاج التطبيق إذن Bluetooth. راجع إعدادات التطبيق.")
                    } else {
                        showError("فشل الاتصال: ${e.message}")
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun findHC05Device(adapter: BluetoothAdapter): BluetoothDevice? {
        return try {
            val bondedDevices = adapter.bondedDevices ?: return null
            bondedDevices.firstOrNull { device ->
                (device.name ?: "").equals(HC05_DEVICE_NAME, ignoreCase = true) ||
                        (hc05Mac != null && device.address == hc05Mac)
            }
        } catch (e: SecurityException) {
            null
        }
    }

    private fun startReadingData() {
        readThread = Thread {
            val buffer = ByteArray(1024)
            var bufferString = ""

            while (isRunning && inputStream != null) {
                try {
                    val bytesRead = inputStream!!.read(buffer)
                    if (bytesRead > 0) {
                        bufferString += String(buffer, 0, bytesRead)
                        val lines = bufferString.split("\n", "\r")
                        bufferString = lines.last()
                        for (i in 0 until lines.size - 1) {
                            val line = lines[i].trim()
                            if (line.isNotEmpty()) {
                                parseAndUpdate(line)
                            }
                        }
                        if (bufferString.contains("\n") || bufferString.contains("\r")) {
                            val line = bufferString.trim()
                            if (line.isNotEmpty()) parseAndUpdate(line)
                            bufferString = ""
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning) {
                        mainHandler.post {
                            updateStatus("انقطع الاتصال")
                            resetValues()
                        }
                        break
                    }
                }
            }
        }
        readThread?.start()
    }

    private fun parseAndUpdate(data: String) {
        var hr: String? = null
        var spo2: String? = null
        var temp: String? = null

        val parts = data.split(",")
        for (part in parts) {
            val kv = part.split(":")
            if (kv.size == 2) {
                when (kv[0].trim().uppercase().replace(" ", "")) {
                    "HR" -> hr = kv[1].trim()
                    "SPO2" -> spo2 = kv[1].trim()
                    "TEMP" -> temp = kv[1].trim()
                }
            }
        }

        if (hr != null || spo2 != null || temp != null) {
            mainHandler.post {
                if (hr != null) tvHeartRate.text = hr
                if (spo2 != null) tvSpO2.text = spo2
                if (temp != null) tvTemperature.text = temp
            }
        }
    }

    private fun resetValues() {
        tvHeartRate.text = "--"
        tvSpO2.text = "--"
        tvTemperature.text = "--"
    }

    private fun updateStatus(text: String) {
        tvStatus.text = text
    }

    private fun showError(message: String) {
        mainHandler.post {
            updateStatus("خطأ")
            resetValues()
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            inputStream?.close()
            bluetoothSocket?.close()
        } catch (_: Exception) { }
        readThread?.interrupt()
        executor.shutdown()
    }
}
