package com.example.borescopeview

import android.content.ContentValues
import android.graphics.*
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        const val SERVER_IP = "192.168.10.123"
        const val SERVER_PORT = 8030
        const val HEARTBEAT_INTERVAL = 500L

        const val EVENT_PORT = 50000
        const val EVENT_INTERVAL = 100L // ms

        @JvmStatic
        private fun hexToBytes(hex: String): ByteArray {
            val cleanHex = hex.replace(" ", "")
            val len = cleanHex.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) +
                        Character.digit(cleanHex[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }

        val HEARTBEAT = hexToBytes("999901000000000000000000000000000000000000000000")
        val STOP_MSG  = hexToBytes("999902000000000000000000000000000000000000000000")

        private val EVENT_REQUEST_PREFIX = "SETCMD".toByteArray()
        private val EVENT_REQUEST_TAIL = hexToBytes("00 00 90 00 04 00 00 00 00 00")
        private val EVENT_RESPONSE_PREFIX = "RETCMD".toByteArray()
        private val EVENT_RESPONSE_MIDDLE = hexToBytes("00 00 90 00 04 00")
    }

    private lateinit var surfaceView: SurfaceView
    private var surfaceHolder: SurfaceHolder? = null
    private lateinit var btnSaveFrame: ImageButton
    private lateinit var btnExit: ImageButton
    private lateinit var btnRotate: ImageButton

    private var udpSocket: DatagramSocket? = null
    private val running = AtomicBoolean(false)

    private data class FrameState(
        var id: Int? = null,
        var size: Int? = null,
        var buffer: ByteArrayOutputStream = ByteArrayOutputStream(),
        var expectedFrag: Int = 0,
        var startTime: Long = 0
    )

    private val currentFrame = FrameState()
    private var lastJpegFrame: ByteArray? = null
    private var rotationDegrees = 90f  // 90°, 270°

    // Event listener
    private val eventSignal = AtomicBoolean(false)
    private var eventRequestCounter = 0
    private var lastServerEventCounter: Int? = null
    private lateinit var flashView: View

    override fun onCreate(savedInstanceState: Bundle?) {


        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        flashView = findViewById(R.id.flashView)


        surfaceView = findViewById(R.id.surfaceView)
        surfaceHolder = surfaceView.holder
        surfaceHolder?.addCallback(this)

        btnSaveFrame = findViewById(R.id.btnSaveFrame)
        btnExit = findViewById(R.id.btnExit)
        btnRotate = findViewById(R.id.btnRotate)

        btnSaveFrame.setOnClickListener {
            lastJpegFrame?.let { jpeg -> saveFrame(jpeg) }
        }

        btnExit.setOnClickListener {
            running.set(false)
            closeSocket()
            finish()
        }

        btnRotate.setOnClickListener {
            rotationDegrees = if (rotationDegrees == 90f) 270f else 90f
        }

    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceHolder = holder
        running.set(true)

        udpSocket = DatagramSocket()
        udpSocket?.soTimeout = 1000
        udpSocket?.broadcast = true

        Log.d("UDP", "Socket creato. Porta locale: ${udpSocket?.localPort}")

        Thread { heartbeatThread() }.start()
        Thread { mjpegReceiveThread() }.start()
        Thread { eventListenerThread() }.start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running.set(false)
        closeSocket()
    }

    // -------------------------
    // Heartbeat
    // -------------------------
    private fun heartbeatThread() {
        val socket = udpSocket ?: return
        val address = InetSocketAddress(SERVER_IP, SERVER_PORT)

        while (running.get()) {
            try {
                socket.send(DatagramPacket(HEARTBEAT, HEARTBEAT.size, address))
                Log.d("HEARTBEAT", "Inviato heartbeat (${HEARTBEAT.size} bytes)")
            } catch (e: Exception) {
                Log.e("HEARTBEAT", "Errore invio heartbeat: ${e.message}")
            }
            Thread.sleep(HEARTBEAT_INTERVAL)
        }

        try {
            socket.send(DatagramPacket(STOP_MSG, STOP_MSG.size, address))
            Log.d("HEARTBEAT", "Inviato STOP_MSG")
        } catch (_: Exception) {}
    }

    // -------------------------
    // MJPEG receive
    // -------------------------
    private fun mjpegReceiveThread() {
        val socket = udpSocket ?: return
        val buf = ByteArray(65535)

        while (running.get()) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)

                val frag = parseMjpegPacket(packet.data, packet.length)
                if (frag != null) {
                    val jpeg = processFragment(currentFrame, frag)
                    if (jpeg != null) {
                        lastJpegFrame = jpeg

                        // Event remoto: salva frame se richiesto
                        if (eventSignal.getAndSet(false)) {
                            saveFrame(jpeg)
                            Log.d("EVENT", "Frame salvato per richiesta remota")
                        }

                        drawFrame(jpeg)
                    }
                }

            } catch (_: SocketTimeoutException) {}
            catch (e: Exception) { Log.e("MJPEG", "Errore ricezione: ${e.message}") }
        }
    }

    // -------------------------
    // Parsing MJPEG
    // -------------------------
    private fun parseMjpegPacket(data: ByteArray, len: Int): Fragment? {
        if (len < 24) return null
        val header = data.copyOfRange(0, 24)
        val payload = data.copyOfRange(24, len)

        if (header[0] != 0x66.toByte() || header[2] != 0x01.toByte()) return null

        val flag = header[1].toInt()
        val frameId = header[3].toInt()
        val frameSize = byteToInt(header, 4)
        val fragIndex = ((header[12].toInt() and 0xFF) or ((header[13].toInt() and 0xFF) shl 8))
        val fragSize = ((header[14].toInt() and 0xFF) or ((header[15].toInt() and 0xFF) shl 8))

        if (payload.size != fragSize) return null
        return Fragment(flag, frameId, frameSize, fragIndex, payload)
    }

    private data class Fragment(
        val flag: Int,
        val id: Int,
        val size: Int,
        val fragIndex: Int,
        val data: ByteArray
    )

    private fun processFragment(state: FrameState, frag: Fragment): ByteArray? {
        if (frag.flag == 1) {
            state.id = frag.id
            state.size = frag.size
            state.buffer = ByteArrayOutputStream()
            state.expectedFrag = 0
            state.startTime = System.currentTimeMillis()
        }

        if (frag.fragIndex != state.expectedFrag) {
            state.expectedFrag = 0
            state.buffer.reset()
            return null
        }

        state.buffer.write(frag.data)
        state.expectedFrag++

        if (frag.flag == 2) {
            val full = state.buffer.toByteArray()
            if (full.size == state.size) {
                state.expectedFrag = 0
                return full
            }
            state.expectedFrag = 0
        }
        return null
    }

    private fun rotateBitmap(bmp: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }

    private fun drawFrame(jpeg: ByteArray) {
        val bmpOrig = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        val bmp = rotateBitmap(bmpOrig, rotationDegrees)

        val holder = surfaceHolder ?: return
        val canvas = holder.lockCanvas() ?: return

        val canvasWidth = canvas.width.toFloat()
        val canvasHeight = canvas.height.toFloat()

        // CENTER_CROP
        val scale = maxOf(canvasWidth / bmp.width, canvasHeight / bmp.height)
        val scaledWidth = bmp.width * scale
        val scaledHeight = bmp.height * scale
        val dx = (canvasWidth - scaledWidth) / 2
        val dy = (canvasHeight - scaledHeight) / 2
        val destRect = RectF(dx, dy, dx + scaledWidth, dy + scaledHeight)

        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bmp, null, destRect, null)

        holder.unlockCanvasAndPost(canvas)
    }

    // -------------------------
    // Event listener
    // -------------------------
    private fun buildEventRequest(): Pair<ByteArray, Int> {
        val cnt = eventRequestCounter
        eventRequestCounter = (eventRequestCounter + 1) and 0xFFFF
        val packet = ByteArrayOutputStream()
        packet.write(EVENT_REQUEST_PREFIX)
        packet.write(byteArrayOf((cnt and 0xFF).toByte(), (cnt shr 8).toByte()))
        packet.write(EVENT_REQUEST_TAIL)
        return Pair(packet.toByteArray(), cnt)
    }

    private fun parseEventPacket(data: ByteArray, len: Int, expectedCounter: Int): Boolean {
        if (len != 20) return false
        if (!data.sliceArray(0..5).contentEquals(EVENT_RESPONSE_PREFIX)) return false

        val respCounter = (data[6].toInt() and 0xFF) or ((data[7].toInt() and 0xFF) shl 8)
        if (respCounter != expectedCounter) return false

        if (!data.sliceArray(8..13).contentEquals(EVENT_RESPONSE_MIDDLE)) return false

        val serverEventCounter = ((data[18].toInt() and 0xFF) or ((data[19].toInt() and 0xFF) shl 8))

        if (lastServerEventCounter == null) {
            lastServerEventCounter = serverEventCounter
            return false
        }

        if (serverEventCounter == lastServerEventCounter) return false

        lastServerEventCounter = serverEventCounter
        return true
    }

    private fun eventListenerThread() {
        val sock = DatagramSocket()
        sock.soTimeout = 30
        val serverAddr = InetSocketAddress(SERVER_IP, EVENT_PORT)

        while (running.get()) {
            try {
                val (reqPacket, reqCounter) = buildEventRequest()
                try { sock.send(DatagramPacket(reqPacket, reqPacket.size, serverAddr)) } catch (_: Exception) {}

                try {
                    val buf = ByteArray(256)
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    if (parseEventPacket(pkt.data, pkt.length, reqCounter)) {
                        eventSignal.set(true)
                    }
                } catch (_: SocketTimeoutException) {}
            } catch (_: Exception) {}
            Thread.sleep(EVENT_INTERVAL)
        }
        sock.close()
    }

    private fun saveFrame(jpeg: ByteArray) {
        val ts = SimpleDateFormat("ddMMyyyy-HHmmss", Locale.US).format(Date())
        val filename = "frame_$ts.jpg"

        val output: OutputStream? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/UDP_MJPEG_Stream")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let { contentResolver.openOutputStream(it) }
        } else null

        try {
            output?.use { it.write(jpeg) }
            showFlash()
            Log.d("SAVE", "Frame salvato: $filename")
        } catch (e: Exception) {
            Log.e("SAVE", "Errore salvataggio frame: ${e.message}")
        }
    }

    private fun closeSocket() {
        try { udpSocket?.close() } catch (_: Exception) {}
    }

    private fun byteToInt(b: ByteArray, offset: Int): Int {
        return (b[offset].toInt() and 0xFF) or
                ((b[offset + 1].toInt() and 0xFF) shl 8) or
                ((b[offset + 2].toInt() and 0xFF) shl 16) or
                ((b[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun showFlash() {
        runOnUiThread {
            flashView.alpha = 0.8f
            flashView.animate()
                .alpha(0f)
                .setDuration(300)
                .start()
        }
    }

}
