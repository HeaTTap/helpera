package com.helpera.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var isCameraPermissionGranted = mutableStateOf(false)
    private var isStoragePermissionGranted = mutableStateOf(false)

    // Server and streaming state
    private lateinit var mjpegServer: MjpegServer
    private var isStreaming = mutableStateOf(false)
    private var localIpAddress = mutableStateOf<String?>(null)

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: isCameraPermissionGranted.value
        val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            true
        } else {
            permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: isStoragePermissionGranted.value
        }

        isCameraPermissionGranted.value = cameraGranted
        isStoragePermissionGranted.value = storageGranted

        if (cameraGranted && storageGranted) {
            Toast.makeText(this, "Required permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Required permissions denied", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Keep screen on while taking photos / streaming
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Initialize MJPEG server on port 8080
        mjpegServer = MjpegServer(8080)

        checkPermission()

        setContent {
            MaterialTheme {
                CameraTimeLapseScreen(
                    isPermissionGranted = isCameraPermissionGranted.value && isStoragePermissionGranted.value,
                    onRequestPermission = { requestPermission() },
                    imageCaptureProvider = { imageCapture },
                    setImageCapture = { imageCapture = it },
                    cameraExecutor = cameraExecutor,
                    isStreaming = isStreaming.value,
                    onToggleStreaming = { toggle -> toggleStreaming(toggle) },
                    localIpAddress = localIpAddress.value,
                    mjpegServer = mjpegServer
                )
            }
        }
    }

    private fun checkPermission() {
        isCameraPermissionGranted.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        isStoragePermissionGranted.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermission() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        requestPermissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun toggleStreaming(start: Boolean) {
        if (start) {
            val ip = getLocalIpAddress()
            if (ip == null) {
                Toast.makeText(this, "Failed to get local IP. Connect to Wi-Fi.", Toast.LENGTH_LONG).show()
                return
            }
            localIpAddress.value = ip
            try {
                mjpegServer.start()
                isStreaming.value = true
                Toast.makeText(this, "Streaming started on http://$ip:8080", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e("CameraApp", "Failed to start server", e)
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            mjpegServer.stop()
            isStreaming.value = false
            localIpAddress.value = null
            Toast.makeText(this, "Streaming stopped", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (networkInterface in Collections.list(interfaces)) {
                val addresses = networkInterface.inetAddresses
                for (address in Collections.list(addresses)) {
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress
                        if (!ip.isNullOrEmpty()) {
                            return ip
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e("CameraApp", "Error getting IP Address", ex)
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        mjpegServer.stop()
        cameraExecutor.shutdown()
    }
}

@Composable
fun CameraTimeLapseScreen(
    isPermissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    imageCaptureProvider: () -> ImageCapture?,
    setImageCapture: (ImageCapture?) -> Unit,
    cameraExecutor: ExecutorService,
    isStreaming: Boolean,
    onToggleStreaming: (Boolean) -> Unit,
    localIpAddress: String?,
    mjpegServer: MjpegServer
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isCapturing by remember { mutableStateOf(false) }
    var photoCount by remember { mutableStateOf(0) }
    var lastPhotoPath by remember { mutableStateOf<String?>(null) }
    var captureJob by remember { mutableStateOf<Job?>(null) }
    var streamJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    // Camera state - created once with COMPATIBLE mode to guarantee TextureView and getBitmap() support
    val previewView = remember { 
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    LaunchedEffect(isPermissionGranted) {
        if (isPermissionGranted) {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                setImageCapture(imageCapture)

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture
                    )
                } catch (exc: Exception) {
                    Log.e("CameraApp", "Use case binding failed", exc)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }

    // Handle streaming loop by pulling bitmaps directly from the working PreviewView
    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            streamJob = scope.launch(Dispatchers.Main) {
                while (isActive) {
                    val bitmap = previewView.bitmap
                    if (bitmap != null) {
                        withContext(Dispatchers.IO) {
                            try {
                                val out = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
                                val jpegBytes = out.toByteArray()
                                mjpegServer.broadcastFrame(jpegBytes)
                            } catch (e: Exception) {
                                Log.e("CameraApp", "Error encoding/broadcasting frame", e)
                            }
                        }
                        bitmap.recycle() // Prevent native memory leaks!
                    }
                    delay(100) // ~10 FPS
                }
            }
        } else {
            streamJob?.cancel()
            streamJob = null
        }
    }

    // Function to take a photo
    val takePhoto = {
        val imageCapture = imageCaptureProvider()
        if (imageCapture != null) {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val displayName = "IMG_${timeStamp}"

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
                }
            }

            val outputOptions = ImageCapture.OutputFileOptions.Builder(
                context.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ).build()

            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        Log.e("CameraApp", "Photo capture failed: ${exc.message}", exc)
                        Toast.makeText(context, "Error: ${exc.message}", Toast.LENGTH_SHORT).show()
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        photoCount++
                        lastPhotoPath = output.savedUri?.toString() ?: "DCIM/Camera/$displayName.jpg"
                        Log.d("CameraApp", "Photo captured successfully to MediaStore: $lastPhotoPath")
                    }
                }
            )
        } else {
            Log.e("CameraApp", "ImageCapture use case is not bound")
        }
    }

    // Handle timer
    LaunchedEffect(isCapturing) {
        if (isCapturing) {
            captureJob = scope.launch(Dispatchers.Main) {
                while (isActive) {
                    takePhoto()
                    delay(5000)
                }
            }
        } else {
            captureJob?.cancel()
            captureJob = null
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212)
    ) {
        if (!isPermissionGranted) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Permissions Required",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "This app needs camera and storage access to take photos and stream video.",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onRequestPermission) {
                    Text("Grant Permissions")
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                // Camera Preview
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )

                // Overlay UI controls
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color(0xAA000000))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Status indicators Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "TIME-LAPSE",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isCapturing) "ACTIVE" else "IDLE",
                                color = if (isCapturing) Color.Red else Color.Green,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "VIDEO STREAM",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (isStreaming) "LIVE" else "OFFLINE",
                                color = if (isStreaming) Color.Red else Color(0xFF00B0FF),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (isStreaming && localIpAddress != null) {
                        Text(
                            text = "Stream URL: http://$localIpAddress:8080/",
                            color = Color(0xFF00E676),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    Text(
                        text = "Photos Taken: $photoCount",
                        color = Color.White,
                        fontSize = 14.sp
                    )

                    lastPhotoPath?.let { path ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Last: ${path.substringAfterLast("/")}",
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Buttons Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { isCapturing = !isCapturing },
                            enabled = !isStreaming,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isCapturing) Color.Red else Color.Green,
                                contentColor = Color.White,
                                disabledContainerColor = Color.DarkGray
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (isCapturing) "STOP TIMELAPSE" else "START TIMELAPSE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = { onToggleStreaming(!isStreaming) },
                            enabled = !isCapturing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isStreaming) Color.Red else Color(0xFF00B0FF),
                                contentColor = Color.White,
                                disabledContainerColor = Color.DarkGray
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (isStreaming) "STOP STREAM" else "START STREAM",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

class MjpegServer(private val port: Int, private val defaultRotation: Int = 90) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val clients = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private var thread: Thread? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        serverSocket = ServerSocket(port)
        thread = Thread {
            while (isRunning) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    Thread {
                        handleClient(socket)
                    }.start()
                } catch (e: IOException) {
                    if (isRunning) {
                        Log.e("MjpegServer", "Error accepting client", e)
                    }
                }
            }
        }.apply { start() }
        Log.i("MjpegServer", "Server started on port $port")
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            Log.e("MjpegServer", "Error closing server socket", e)
        }
        serverSocket = null
        for (client in clients) {
            try {
                client.close()
            } catch (e: IOException) {
                // Ignore
            }
        }
        clients.clear()
        thread?.interrupt()
        thread = null
        Log.i("MjpegServer", "Server stopped")
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = socket.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            
            // Basic HTTP router
            if (requestLine.startsWith("GET /stream")) {
                val outputStream = socket.getOutputStream()
                outputStream.write(
                    ("HTTP/1.1 200 OK\r\n" +
                     "Content-Type: multipart/x-mixed-replace; boundary=--frame\r\n" +
                     "Cache-Control: no-cache, private\r\n" +
                     "Pragma: no-cache\r\n" +
                     "Connection: keep-alive\r\n\r\n").toByteArray()
                )
                outputStream.flush()
                clients.add(socket)
                
                // Keep reading from socket to detect client disconnects
                while (isRunning && !socket.isClosed) {
                    if (reader.read() == -1) {
                        break
                    }
                }
            } else if (requestLine.startsWith("GET / ")) {
                val outputStream = socket.getOutputStream()
                val html = getHtmlPage()
                outputStream.write(
                    ("HTTP/1.1 200 OK\r\n" +
                     "Content-Type: text/html\r\n" +
                     "Content-Length: ${html.length}\r\n" +
                     "Connection: close\r\n\r\n" +
                     html).toByteArray()
                )
                outputStream.flush()
                socket.close()
            } else {
                val outputStream = socket.getOutputStream()
                outputStream.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".toByteArray())
                outputStream.flush()
                socket.close()
            }
        } catch (e: IOException) {
            // Client probably disconnected early
        } finally {
            clients.remove(socket)
            try {
                socket.close()
            } catch (e: Exception) {}
        }
    }

    fun broadcastFrame(jpegBytes: ByteArray) {
        if (clients.isEmpty()) return
        
        val boundary = "\r\n--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpegBytes.size}\r\n\r\n".toByteArray()
        val endBoundary = "\r\n".toByteArray()
        
        val iterator = clients.iterator()
        while (iterator.hasNext()) {
            val client = iterator.next()
            try {
                val out = client.getOutputStream()
                out.write(boundary)
                out.write(jpegBytes)
                out.write(endBoundary)
                out.flush()
            } catch (e: IOException) {
                // Client disconnected
                iterator.remove()
                try {
                    client.close()
                } catch (ex: Exception) {}
            }
        }
    }

    private fun getHtmlPage(): String {
        return """
        <!DOCTYPE html>
        <html>
        <head>
            <title>Helpera Live Stream</title>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <style>
                body {
                    margin: 0;
                    background: #121212;
                    color: #ffffff;
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    justify-content: center;
                    min-height: 100vh;
                }
                .container {
                    text-align: center;
                    padding: 20px;
                    max-width: 100%;
                }
                h1 {
                    font-size: 24px;
                    margin-bottom: 5px;
                    font-weight: 600;
                    color: #00e676;
                    letter-spacing: 0.5px;
                }
                p {
                    color: #888;
                    font-size: 14px;
                    margin-top: 0;
                    margin-bottom: 20px;
                }
                .stream-wrapper {
                    position: relative;
                    background: #000;
                    border-radius: 12px;
                    overflow: hidden;
                    box-shadow: 0 8px 32px rgba(0, 0, 0, 0.5);
                    display: inline-block;
                    line-height: 0;
                    padding: 10px;
                }
                .stream-img {
                    max-width: 100%;
                    max-height: 70vh;
                    border-radius: 8px;
                    transform: rotate(${defaultRotation}deg);
                    transition: transform 0.3s ease;
                }
                .controls {
                    margin-top: 20px;
                    display: flex;
                    gap: 10px;
                    justify-content: center;
                    flex-wrap: wrap;
                }
                button {
                    background: #222;
                    color: #fff;
                    border: 1px solid #444;
                    padding: 10px 20px;
                    border-radius: 6px;
                    cursor: pointer;
                    font-size: 14px;
                    font-weight: 500;
                    transition: all 0.2s;
                }
                button:hover {
                    background: #333;
                    border-color: #00e676;
                }
            </style>
        </head>
        <body>
            <div class="container">
                <h1>Helpera IP Camera</h1>
                <p>Live Video Stream</p>
                <div class="stream-wrapper">
                    <img src="/stream" class="stream-img" alt="Live Feed">
                </div>
                <div class="controls">
                    <button onclick="rotate(0)">Rotate 0&deg;</button>
                    <button onclick="rotate(90)">Rotate 90&deg;</button>
                    <button onclick="rotate(180)">Rotate 180&deg;</button>
                    <button onclick="rotate(270)">Rotate 270&deg;</button>
                </div>
            </div>
            <script>
                const img = document.querySelector('.stream-img');
                function rotate(deg) {
                    img.style.transform = 'rotate(' + deg + 'deg)';
                }
            </script>
        </body>
        </html>
        """.trimIndent()
    }
}
