package com.emprendedorlatam.embymirror

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.emprendedorlatam.embymirror.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val port = binding.portInput.text.toString().toIntOrNull() ?: 8088
            val width = binding.widthInput.text.toString().toIntOrNull() ?: 1280
            val height = binding.heightInput.text.toString().toIntOrNull() ?: 720
            val fps = binding.fpsInput.text.toString().toIntOrNull() ?: 24
            val bitrate = binding.bitrateInput.text.toString().toIntOrNull() ?: 2_500_000
            val startIntent = ScreenMirrorService.startIntent(
                context = this,
                resultCode = result.resultCode,
                data = result.data!!,
                port = port,
                width = width,
                height = height,
                fps = fps,
                bitrate = bitrate
            )
            ContextCompat.startForegroundService(this, startIntent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectionManager = getSystemService(MediaProjectionManager::class.java)

        binding.startButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
            captureLauncher.launch(projectionManager.createScreenCaptureIntent())
        }

        binding.stopButton.setOnClickListener {
            stopService(Intent(this, ScreenMirrorService::class.java))
        }

        lifecycleScope.launch {
            MirrorStateStore.state.collect { state ->
                val port = binding.portInput.text.toString().toIntOrNull() ?: 8088
                val fallbackUrl = "http://${NetUtils.firstIpv4Address()}:$port/channels.m3u"
                binding.urlText.text = "URL M3U: ${state.m3uUrl.ifBlank { fallbackUrl }}"
                binding.stateText.text = "Estado: ${state.message}"
                binding.startButton.isEnabled = !state.running
                binding.stopButton.isEnabled = state.running
            }
        }
    }
}
