package com.cleanutilityapps.photofix

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.cleanutilityapps.photofix.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val worker = Executors.newSingleThreadExecutor()
    private var original: Bitmap? = null
    private var enhanced: Bitmap? = null
    private val aiEnhancer by lazy { AiImageEnhancer(applicationContext, worker) }

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadAndEnhance(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.selectPhotoButton.setOnClickListener { picker.launch("image/*") }
        binding.beforeButton.setOnClickListener {
            original?.let {
                binding.photoView.setImageBitmap(it)
                binding.modeLabel.text = "Before"
            }
        }
        binding.afterButton.setOnClickListener {
            enhanced?.let {
                binding.photoView.setImageBitmap(it)
                binding.modeLabel.text = "After"
            }
        }
        binding.saveButton.setOnClickListener { enhanced?.let(::saveBitmap) }
    }

    private fun loadAndEnhance(uri: Uri) {
        binding.modeLabel.text = "Enhancing locally…"
        binding.saveButton.isEnabled = false

        worker.execute {
            try {
                val raw = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
                    ?: error("Could not decode image")
                val scaled = downscaleForPrototype(raw, 2200)
                original = scaled
                aiEnhancer.enhance(
                    bitmap = scaled,
                    onStatus = { status ->
                        runOnUiThread {
                            binding.modeLabel.text = status
                            binding.analysisText.text = "Engine: Google on-device Media Enhancement"
                        }
                    },
                    onSuccess = { result ->
                        enhanced = result
                        runOnUiThread {
                            binding.photoView.setImageBitmap(result)
                            binding.modeLabel.text = "After · AI"
                            binding.saveButton.isEnabled = true
                            binding.analysisText.text =
                                "Engine: Google on-device Media Enhancement\n" +
                                "Tonemap: ON\nDeblur/denoise: ON\nUpscale: OFF"
                        }
                    },
                    onFallback = { reason ->
                        val pair = ImageEnhancer.enhance(scaled)
                        enhanced = pair.first
                        runOnUiThread {
                            binding.photoView.setImageBitmap(pair.first)
                            binding.modeLabel.text = "After · fallback"
                            binding.saveButton.isEnabled = true
                            binding.analysisText.text =
                                "Engine: traditional fallback\nReason: " + reason
                        }
                    },
                )
            } catch (t: Throwable) {
                runOnUiThread {
                    binding.modeLabel.text = "Enhancement failed"
                    Toast.makeText(this, t.message ?: "Unknown error", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun downscaleForPrototype(source: Bitmap, maxSide: Int): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (largest <= maxSide) return source.copy(Bitmap.Config.ARGB_8888, false)
        val scale = maxSide.toFloat() / largest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun saveBitmap(bitmap: Bitmap) {
        worker.execute {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "PhotoFix_" + stamp + ".jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Photo Fix")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

            try {
                requireNotNull(uri)
                contentResolver.openOutputStream(uri).use { out ->
                    requireNotNull(out)
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out))
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)

                runOnUiThread {
                    Toast.makeText(this, "Saved to Pictures/Photo Fix", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                uri?.let { contentResolver.delete(it, null, null) }
                runOnUiThread {
                    Toast.makeText(this, "Save failed: " + t.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
