package com.app.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.video.*
import androidx.camera.video.VideoRecordEvent.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.constraintlayout.widget.ConstraintLayout
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var timerText: TextView
    private lateinit var recordBtn: Button
    private lateinit var modeText: TextView
    private lateinit var rootLayout: ConstraintLayout

    private var videoCapture: VideoCapture<Recorder>? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var currentRecording: androidx.camera.video.Recording? = null

    private val cameraExecutor: Executor by lazy { Executors.newSingleThreadExecutor() }

    private val modes = listOf("VIDEO", "OTHER")
    private var modeIndex = 0

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            var granted = true
            for ((_, ok) in perms) {
                if (!ok) granted = false
            }
            if (granted) startCamera()
            else Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        recordBtn = findViewById(R.id.recordBtn)
        modeText = findViewById(R.id.modeText)
        timerText = findViewById(R.id.timerText)
        rootLayout = findViewById(R.id.root)

        modeText.text = modes[modeIndex]

        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 100
            private val SWIPE_VELOCITY_THRESHOLD = 100
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                val diffX = e2.x - (e1?.x ?: 0f)
                val diffY = e2.y - (e1?.y ?: 0f)
                if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > 100 && Math.abs(velocityX) > 100) {
                    if (diffX > 0) onSwipeRight() else onSwipeLeft()
                    return true
                }
                return false
            }
        })

        rootLayout.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        recordBtn.setOnClickListener {
            when (modes[modeIndex]) {
                "VIDEO" -> toggleRecording()
                else -> Toast.makeText(this, "Mode '${modes[modeIndex]}' has no action", Toast.LENGTH_SHORT).show()
            }
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun onSwipeLeft() {
        modeIndex = (modeIndex + 1) % modes.size
        modeText.text = modes[modeIndex]
    }

    private fun onSwipeRight() {
        modeIndex = (modeIndex - 1 + modes.size) % modes.size
        modeText.text = modes[modeIndex]
    }

    private fun allPermissionsGranted(): Boolean {
        return requiredPermissions.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.FHD))
                .build()

            videoCapture = VideoCapture.withOutput(recorder)

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, preview, videoCapture)
            } catch (exc: Exception) {
                exc.printStackTrace()
                Toast.makeText(this, "Failed to bind camera: ${exc.message}", Toast.LENGTH_SHORT).show()
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleRecording() {
        val currentVideoCapture = videoCapture ?: run {
            Toast.makeText(this, "Video not ready", Toast.LENGTH_SHORT).show()
            return
        }

        if (currentRecording != null) {
            currentRecording?.stop()
            currentRecording = null
            recordBtn.text = "REC"
            return
        }

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.UK).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VID_$name")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Videos")
        }

        val mediaStoreOutput = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        val canRecordAudio = ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        val pendingRecording = currentVideoCapture.output
            .prepareRecording(this, mediaStoreOutput)
            .apply {
                if (canRecordAudio) withAudioEnabled()
            }

        currentRecording = pendingRecording.start(ContextCompat.getMainExecutor(this)) { recordEvent ->
            when (recordEvent) {
                is Start -> {
                    runOnUiThread {
                        recordBtn.text = "STOP"
                        timerText.visibility = View.VISIBLE
                        timerText.text = "00:00"
                        }
                }

                is Status -> {
                    val duration = recordEvent.recordingStats.recordedDurationNanos / 1_000_000_000
                    val minutes = duration / 60
                    val seconds = duration % 60
                    runOnUiThread {
                        timerText.text = String.format("%02d:%02d", minutes, seconds)
                    }
                }

                is Finalize -> {
                    val cause = recordEvent.error
                    val savedUri = recordEvent.outputResults.outputUri
                    Toast.makeText(this, "Saved: $savedUri", Toast.LENGTH_SHORT).show()

                    runOnUiThread {
                        recordBtn.text = "REC"
                        timerText.visibility = View.GONE
                        }

                    currentRecording = null
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        (cameraExecutor as? java.util.concurrent.ExecutorService)?.shutdown()
        cameraProvider?.unbindAll()
    }
}