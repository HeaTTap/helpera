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
import android.view.View
import android.view.ViewGroup
import android.view.TextureView
import android.view.WindowManager
import android.widget.Toast
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.ChannelSftp
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

    // 1. Create camera use case instances once using remember
    val preview = remember { Preview.Builder().build() }
    val imageCapture = remember { 
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    // Camera view - forced to COMPATIBLE mode to guarantee TextureView availability
    val previewView = remember { 
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // 2. Retrieve cameraProvider once
    LaunchedEffect(isPermissionGranted) {
        if (isPermissionGranted) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                cameraProvider = future.get()
            }, ContextCompat.getMainExecutor(context))
        }
    }

    // 3. Set ImageCapture instance in parent once
    LaunchedEffect(imageCapture) {
        setImageCapture(imageCapture)
    }

    // 4. Bind Preview and ImageCapture concurrently (never unbind/rebind during streaming to avoid flashing)
    LaunchedEffect(cameraProvider) {
        val provider = cameraProvider
        if (provider != null) {
            try {
                provider.unbindAll()
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                
                preview.setSurfaceProvider(previewView.surfaceProvider)

                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (exc: Exception) {
                Log.e("CameraApp", "Use case binding failed", exc)
            }
        }
    }

    // 5. Handle streaming loop by capturing frames directly from the TextureView in PreviewView
    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            streamJob = scope.launch(Dispatchers.Main) {
                // Find TextureView in the PreviewView hierarchy (polled until ready)
                var textureView: TextureView? = null
                while (textureView == null && isActive) {
                    textureView = findTextureView(previewView)
                    if (textureView == null) {
                        delay(100)
                    }
                }

                if (textureView != null) {
                    // Cache of reusable bitmaps for each active resolution to avoid memory churn
                    val bitmapCache = mutableMapOf<Pair<Int, Int>, Bitmap>()
                    val isCompressing = ConcurrentHashMap<Pair<Int, Int>, Boolean>()

                    try {
                        while (isActive) {
                            val startTime = System.currentTimeMillis()

                            // Get native TextureView dimensions safely (fallback to 640x480 if not laid out yet)
                            val nativeWidth = if (textureView.width > 0) textureView.width else 640
                            val nativeHeight = if (textureView.height > 0) textureView.height else 480

                            val activeResolutions = mjpegServer.getUniqueClientResolutions(nativeWidth, nativeHeight)

                            if (activeResolutions.isNotEmpty() && textureView.isAvailable) {
                                // Recycle and remove bitmaps for resolutions that are no longer active
                                val iterator = bitmapCache.iterator()
                                while (iterator.hasNext()) {
                                    val entry = iterator.next()
                                    if (entry.key !in activeResolutions) {
                                        // Wait until active background compression completes to avoid recycling busy bitmaps
                                        if (isCompressing[entry.key] != true) {
                                            entry.value.recycle()
                                            isCompressing.remove(entry.key)
                                            iterator.remove()
                                        }
                                    }
                                }

                                // Create bitmaps for new active resolutions
                                for (res in activeResolutions) {
                                    if (res !in bitmapCache) {
                                        try {
                                            bitmapCache[res] = Bitmap.createBitmap(res.first, res.second, Bitmap.Config.ARGB_8888)
                                        } catch (e: OutOfMemoryError) {
                                            Log.e("CameraApp", "OOM creating bitmap for ${res.first}x${res.second}", e)
                                        }
                                    }
                                }

                                // Capture and broadcast for each active resolution
                                for (res in activeResolutions) {
                                    val bitmap = bitmapCache[res] ?: continue
                                    
                                    // Skip capturing this frame if background thread is still compressing previous one (non-blocking drop frame)
                                    if (isCompressing[res] == true) {
                                        continue
                                    }

                                    try {
                                        // Scale frame into cached bitmap on UI thread (extremely fast due to GPU scaling)
                                        textureView.getBitmap(bitmap)

                                        isCompressing[res] = true

                                        // Compress to JPEG and broadcast on background thread (non-blocking)
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                val out = ByteArrayOutputStream()
                                                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
                                                val jpegBytes = out.toByteArray()
                                                mjpegServer.broadcastFrame(nativeWidth, nativeHeight, res.first, res.second, jpegBytes)
                                            } catch (e: Exception) {
                                                Log.e("CameraApp", "Error compressing frame for resolution ${res.first}x${res.second}", e)
                                            } finally {
                                                isCompressing[res] = false
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e("CameraApp", "Streaming loop frame capture error", e)
                                        isCompressing[res] = false
                                    }
                                }
                            }

                            // Regulate FPS to ~30 (33 ms target)
                            val elapsed = System.currentTimeMillis() - startTime
                            val delayMs = (33 - elapsed).coerceAtLeast(1)
                            delay(delayMs)
                        }
                    } finally {
                        for (bitmap in bitmapCache.values) {
                            bitmap.recycle()
                        }
                        bitmapCache.clear()
                        isCompressing.clear()
                    }
                }
            }
        } else {
            streamJob?.cancel()
            streamJob = null
        }
    }

    // Function to take a photo
    val takePhoto = {
        val boundImageCapture = imageCaptureProvider()
        if (boundImageCapture != null) {
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

            boundImageCapture.takePicture(
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
                        output.savedUri?.let { uri ->
                            uploadPhotoToSsh(context, uri, "${displayName}.jpg")
                        }
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

private fun findTextureView(view: View): TextureView? {
    if (view is TextureView) {
        return view
    }
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            val result = findTextureView(child)
            if (result != null) {
                return result
            }
        }
    }
    return null
}

private fun uploadPhotoToSsh(context: Context, uri: android.net.Uri, fileName: String) {
    CoroutineScope(Dispatchers.IO).launch {
        var session: Session? = null
        var channel: ChannelSftp? = null
        try {
            val jsch = JSch()
            session = jsch.getSession("heatlub", "192.168.0.139", 22)
            session.setPassword("prosperloves3!")
            
            val config = java.util.Properties()
            config["StrictHostKeyChecking"] = "no"
            session.setConfig(config)
            
            session.connect(10000)
            
            val channelSftp = session.openChannel("sftp") as ChannelSftp
            channelSftp.connect(5000)
            channel = channelSftp
            
            try {
                channelSftp.cd("/mnt/S512GB/fotoauto")
            } catch (e: Exception) {
                Log.w("CameraApp", "Could not cd to /mnt/S512GB/fotoauto, trying to create it", e)
                createDirectoryRecursive(channelSftp, "/mnt/S512GB/fotoauto")
                channelSftp.cd("/mnt/S512GB/fotoauto")
            }
            
            val inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream != null) {
                inputStream.use { stream ->
                    channelSftp.put(stream, fileName)
                }
                Log.i("CameraApp", "Successfully uploaded $fileName to SSH")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Uploaded $fileName to SSH", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("CameraApp", "SSH Upload failed", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "SSH Upload failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        } finally {
            channel?.disconnect()
            session?.disconnect()
        }
    }
}

private fun createDirectoryRecursive(sftp: ChannelSftp, path: String) {
    val dirs = path.split("/").filter { it.isNotEmpty() }
    var currentPath = ""
    for (dir in dirs) {
        currentPath += "/$dir"
        try {
            sftp.mkdir(currentPath)
        } catch (e: Exception) {
            // Already exists or parent permission issue
        }
    }
}

data class ClientConfig(val width: Int, val height: Int)

private fun parseQueryParam(requestLine: String, key: String): String? {
    val queryStart = requestLine.indexOf('?')
    if (queryStart == -1) return null
    val spaceEnd = requestLine.indexOf(' ', queryStart)
    if (spaceEnd == -1) return null
    val queryString = requestLine.substring(queryStart + 1, spaceEnd)
    val pairs = queryString.split('&')
    for (pair in pairs) {
        val parts = pair.split('=')
        if (parts.size == 2 && parts[0] == key) {
            return parts[1]
        }
    }
    return null
}

class MjpegServer(private val port: Int, private val defaultRotation: Int = 90) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val clients = ConcurrentHashMap<Socket, ClientConfig>()
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
        for (client in clients.keys) {
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
                val width = parseQueryParam(requestLine, "width")?.toIntOrNull() ?: 1920
                val height = parseQueryParam(requestLine, "height")?.toIntOrNull() ?: 1080
                
                val outputStream = socket.getOutputStream()
                outputStream.write(
                    ("HTTP/1.1 200 OK\r\n" +
                     "Content-Type: multipart/x-mixed-replace; boundary=--frame\r\n" +
                     "Cache-Control: no-cache, private\r\n" +
                     "Pragma: no-cache\r\n" +
                     "Connection: keep-alive\r\n\r\n").toByteArray()
                )
                outputStream.flush()
                clients[socket] = ClientConfig(width, height)
                
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

    fun getUniqueClientResolutions(nativeWidth: Int, nativeHeight: Int): List<Pair<Int, Int>> {
        val resolutions = mutableSetOf<Pair<Int, Int>>()
        for (config in clients.values) {
            val w = if (config.width <= 0) nativeWidth else config.width
            val h = if (config.height <= 0) nativeHeight else config.height
            if (w > 0 && h > 0) {
                resolutions.add(Pair(w, h))
            }
        }
        return resolutions.toList()
    }

    fun broadcastFrame(nativeWidth: Int, nativeHeight: Int, width: Int, height: Int, jpegBytes: ByteArray) {
        if (clients.isEmpty()) return
        
        val boundary = "\r\n--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpegBytes.size}\r\n\r\n".toByteArray()
        val endBoundary = "\r\n".toByteArray()
        
        val iterator = clients.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val socket = entry.key
            val config = entry.value
            val cw = if (config.width <= 0) nativeWidth else config.width
            val ch = if (config.height <= 0) nativeHeight else config.height
            if (cw == width && ch == height) {
                try {
                    val out = socket.getOutputStream()
                    out.write(boundary)
                    out.write(jpegBytes)
                    out.write(endBoundary)
                    out.flush()
                } catch (e: IOException) {
                    iterator.remove()
                    try {
                        socket.close()
                    } catch (ex: Exception) {}
                }
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
                    background: #0f0f11;
                    color: #f3f4f6;
                    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", sans-serif;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    justify-content: center;
                    min-height: 100vh;
                }
                .container {
                    text-align: center;
                    padding: 24px;
                    max-width: 680px;
                    width: 100%;
                    box-sizing: border-box;
                }
                h1 {
                    font-size: 26px;
                    margin: 0 0 4px 0;
                    font-weight: 700;
                    color: #00e676;
                    letter-spacing: -0.5px;
                }
                p.subtitle {
                    color: #9ca3af;
                    font-size: 14px;
                    margin: 0 0 24px 0;
                    font-weight: 500;
                }
                .stream-wrapper {
                    position: relative;
                    background: #000;
                    border-radius: 16px;
                    overflow: hidden;
                    box-shadow: 0 10px 40px rgba(0, 0, 0, 0.6);
                    line-height: 0;
                    padding: 8px;
                    border: 1px solid rgba(255, 255, 255, 0.05);
                    display: flex;
                    justify-content: center;
                    align-items: center;
                }
                .stream-img {
                    max-width: 100%;
                    max-height: 60vh;
                    border-radius: 10px;
                    transform: rotate(${defaultRotation}deg);
                    transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1), opacity 0.2s ease;
                    opacity: 1;
                }
                .control-panel {
                    background: rgba(255, 255, 255, 0.02);
                    border: 1px solid rgba(255, 255, 255, 0.06);
                    border-radius: 16px;
                    padding: 20px;
                    margin-top: 24px;
                    display: flex;
                    flex-direction: column;
                    gap: 20px;
                    backdrop-filter: blur(12px);
                }
                .settings-row {
                    display: flex;
                    gap: 16px;
                    justify-content: center;
                    flex-wrap: wrap;
                }
                .control-item {
                    display: flex;
                    flex-direction: column;
                    align-items: flex-start;
                    gap: 6px;
                    flex: 1;
                    min-width: 160px;
                }
                .control-item label {
                    font-size: 11px;
                    color: #9ca3af;
                    text-transform: uppercase;
                    letter-spacing: 1px;
                    font-weight: 700;
                }
                select {
                    background: #18181b;
                    color: #f3f4f6;
                    border: 1px solid rgba(255, 255, 255, 0.1);
                    padding: 10px 14px;
                    border-radius: 8px;
                    cursor: pointer;
                    font-size: 14px;
                    font-weight: 500;
                    width: 100%;
                    outline: none;
                    transition: all 0.2s ease;
                    box-sizing: border-box;
                }
                select:focus {
                    border-color: #00e676;
                    box-shadow: 0 0 0 3px rgba(0, 230, 118, 0.15);
                }
                .btn-group {
                    display: flex;
                    gap: 8px;
                    justify-content: center;
                    flex-wrap: wrap;
                    border-top: 1px solid rgba(255, 255, 255, 0.05);
                    padding-top: 16px;
                }
                button {
                    background: #18181b;
                    color: #f3f4f6;
                    border: 1px solid rgba(255, 255, 255, 0.1);
                    padding: 8px 16px;
                    border-radius: 8px;
                    cursor: pointer;
                    font-size: 13px;
                    font-weight: 600;
                    transition: all 0.2s ease;
                    display: inline-flex;
                    align-items: center;
                    justify-content: center;
                }
                button:hover {
                    background: rgba(255, 255, 255, 0.05);
                    border-color: #00e676;
                    color: #00e676;
                }
                button:active {
                    transform: scale(0.97);
                }
            </style>
        </head>
        <body>
            <div class="container">
                <h1>Helpera IP Camera</h1>
                <p class="subtitle">Live Video Stream</p>
                <div class="stream-wrapper">
                    <img src="/stream" class="stream-img" alt="Live Feed">
                </div>
                <div class="control-panel">
                    <div class="settings-row">
                        <div class="control-item">
                            <label for="aspect-ratio">Aspect Ratio</label>
                            <select id="aspect-ratio" onchange="onRatioChange()">
                                <option value="16:9" selected>Widescreen (16:9)</option>
                                <option value="4:3">Standard (4:3)</option>
                                <option value="1:1">Square (1:1)</option>
                                <option value="native">Native (Phone Screen)</option>
                            </select>
                        </div>
                        <div class="control-item">
                            <label for="resolution">Resolution</label>
                            <select id="resolution" onchange="updateStream()">
                                <!-- Populated by JS -->
                            </select>
                        </div>
                    </div>
                    <div class="btn-group">
                        <button onclick="rotate(0)">Rotate 0&deg;</button>
                        <button onclick="rotate(90)">Rotate 90&deg;</button>
                        <button onclick="rotate(180)">Rotate 180&deg;</button>
                        <button onclick="rotate(270)">Rotate 270&deg;</button>
                    </div>
                </div>
            </div>
            <script>
                const img = document.querySelector('.stream-img');
                const ratioSelect = document.getElementById('aspect-ratio');
                const resSelect = document.getElementById('resolution');

                const resolutionsByRatio = {
                    '4:3': [
                        { label: 'Low (320x240)', w: 320, h: 240 },
                        { label: 'Medium (640x480)', w: 640, h: 480, default: true },
                        { label: 'High (1024x768)', w: 1024, h: 768 }
                    ],
                    '16:9': [
                        { label: 'Low (640x360)', w: 640, h: 360 },
                        { label: 'Medium (1280x720)', w: 1280, h: 720 },
                        { label: 'Full HD (1920x1080)', w: 1920, h: 1080, default: true }
                    ],
                    '1:1': [
                        { label: 'Low (240x240)', w: 240, h: 240 },
                        { label: 'Medium (480x480)', w: 480, h: 480, default: true },
                        { label: 'High (720x720)', w: 720, h: 720 }
                    ],
                    'native': [
                        { label: 'Native (0x0)', w: 0, h: 0, default: true }
                    ]
                };

                function onRatioChange() {
                    const ratio = ratioSelect.value;
                    const options = resolutionsByRatio[ratio];
                    
                    resSelect.innerHTML = '';
                    let defaultIdx = 0;
                    
                    options.forEach((opt, idx) => {
                        const el = document.createElement('option');
                        el.value = opt.w + 'x' + opt.h;
                        el.textContent = opt.label;
                        if (opt.default) defaultIdx = idx;
                        resSelect.appendChild(el);
                    });
                    
                    resSelect.selectedIndex = defaultIdx;
                    updateStream();
                }

                function updateStream() {
                    const ratio = ratioSelect.value;
                    const selectedRes = resolutionsByRatio[ratio][resSelect.selectedIndex];
                    
                    // Visual transition feedback
                    img.style.opacity = 0.4;
                    
                    // Build stream query url
                    const streamUrl = '/stream?width=' + selectedRes.w + '&height=' + selectedRes.h + '&t=' + Date.now();
                    img.src = streamUrl;
                }

                img.onload = function() {
                    img.style.opacity = 1;
                };

                function rotate(deg) {
                    img.style.transform = 'rotate(' + deg + 'deg)';
                }

                // Initialize resolutions on load
                onRatioChange();
            </script>
        </body>
        </html>
        """.trimIndent()
    }
}
