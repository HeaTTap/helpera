package com.helpera.camera

import android.Manifest
import android.content.pm.PackageManager
import android.content.ContentValues
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
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var isCameraPermissionGranted = mutableStateOf(false)
    private var isStoragePermissionGranted = mutableStateOf(false)

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
        
        // Keep screen on while taking photos
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraExecutor = Executors.newSingleThreadExecutor()

        checkPermission()

        setContent {
            MaterialTheme {
                CameraTimeLapseScreen(
                    isPermissionGranted = isCameraPermissionGranted.value && isStoragePermissionGranted.value,
                    onRequestPermission = { requestPermission() },
                    imageCaptureProvider = { imageCapture },
                    setImageCapture = { imageCapture = it },
                    cameraExecutor = cameraExecutor
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

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

@Composable
fun CameraTimeLapseScreen(
    isPermissionGranted: Boolean,
    onRequestPermission: () -> Unit,
    imageCaptureProvider: () -> ImageCapture?,
    setImageCapture: (ImageCapture?) -> Unit,
    cameraExecutor: ExecutorService
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isCapturing by remember { mutableStateOf(false) }
    var photoCount by remember { mutableStateOf(0) }
    var lastPhotoPath by remember { mutableStateOf<String?>(null) }
    var captureJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

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
                    text = "Camera Permission Required",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "This app needs camera access to take time-lapse photos.",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onRequestPermission) {
                    Text("Grant Permission")
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                // Camera Preview
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
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
                        }, ContextCompat.getMainExecutor(ctx))
                        
                        previewView
                    },
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
                    Text(
                        text = if (isCapturing) "TIME-LAPSE ACTIVE" else "TIME-LAPSE IDLE",
                        color = if (isCapturing) Color.Green else Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "Photos Taken: $photoCount",
                        color = Color.White,
                        fontSize = 16.sp
                    )

                    lastPhotoPath?.let { path ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Last: ${path.substringAfterLast("/")}",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { isCapturing = !isCapturing },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCapturing) Color.Red else Color.Green,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = if (isCapturing) "STOP" else "START (5s INTERVAL)",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
