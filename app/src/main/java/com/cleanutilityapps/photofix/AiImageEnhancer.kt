package com.cleanutilityapps.photofix

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.google.android.gms.common.api.Status
import com.google.android.gms.media.effect.enhancement.Enhancement
import com.google.android.gms.media.effect.enhancement.EnhancementCallback
import com.google.android.gms.media.effect.enhancement.EnhancementMode
import com.google.android.gms.media.effect.enhancement.EnhancementOptions
import com.google.android.gms.media.effect.enhancement.EnhancementSession
import com.google.android.gms.media.effect.enhancement.EnhancementSessionCallback
import java.util.concurrent.Executor

class AiImageEnhancer(
    context: Context,
    private val executor: Executor,
) {
    private val client = if (Build.VERSION.SDK_INT >= 30) Enhancement.getClient(context.applicationContext) else null

    fun enhance(
        bitmap: Bitmap,
        onStatus: (String) -> Unit,
        onSuccess: (Bitmap) -> Unit,
        onFallback: (String) -> Unit,
    ) {
        val enhancementClient = client ?: run {
            onFallback("Android version does not support Media Enhancement")
            return
        }

        enhancementClient.isDeviceSupported()
            .addOnSuccessListener(executor) { supported ->
                if (!supported) {
                    onFallback("Google AI enhancement is not supported on this device")
                    return@addOnSuccessListener
                }

                enhancementClient.isModuleInstalled()
                    .addOnSuccessListener(executor) { installed ->
                        if (installed) {
                            createAndProcess(bitmap, onStatus, onSuccess, onFallback)
                        } else {
                            onStatus("Downloading on-device AI model…")
                            enhancementClient.installModule(null)
                                .addOnSuccessListener(executor) { success ->
                                    if (success) {
                                        createAndProcess(bitmap, onStatus, onSuccess, onFallback)
                                    } else {
                                        onFallback("AI model installation was not completed")
                                    }
                                }
                                .addOnFailureListener(executor) {
                                    onFallback("AI model download failed: " + (it.message ?: "unknown error"))
                                }
                        }
                    }
                    .addOnFailureListener(executor) {
                        onFallback("Could not check AI module: " + (it.message ?: "unknown error"))
                    }
            }
            .addOnFailureListener(executor) {
                onFallback("Could not check device support: " + (it.message ?: "unknown error"))
            }
    }

    private fun createAndProcess(
        bitmap: Bitmap,
        onStatus: (String) -> Unit,
        onSuccess: (Bitmap) -> Unit,
        onFallback: (String) -> Unit,
    ) {
        val enhancementClient = client ?: return onFallback("AI client unavailable")
        onStatus("AI tonemap + deblur/denoise…")

        val options = EnhancementOptions(
            bitmap.width,
            bitmap.height,
            EnhancementMode.BITMAP,
            true,  // tonemapping - strength is controlled by blending in the UI
            true,  // photo deblur + denoise
            false, // video deblur + denoise
            false, // photo upscale
            false, // video upscale
        )

        val callback = object : EnhancementSessionCallback {
            override fun onSessionCreated(session: EnhancementSession) {
                val processCallback = object : EnhancementCallback {
                    override fun onBitmapProcessed(enhancedBitmap: Bitmap) {
                        session.release()
                        onSuccess(enhancedBitmap)
                    }

                    override fun onError(statusCode: Int) {
                        session.release()
                        onFallback("AI processing failed with status " + statusCode)
                    }

                    override fun onSurfaceProcessed(timestamp: Long) = Unit

                    override fun onCancelled(statusCode: Int) {
                        session.release()
                        onFallback("AI processing cancelled with status " + statusCode)
                    }
                }

                try {
                    session.process(bitmap, options, processCallback)
                } catch (t: Throwable) {
                    session.release()
                    onFallback("AI processing failed: " + (t.message ?: t.javaClass.simpleName))
                }
            }

            override fun onSessionCreationFailed(status: Status) {
                onFallback("AI session failed: " + status.statusCode)
            }

            override fun onSessionDestroyed() = Unit

            override fun onSessionDisconnected(status: Status) {
                onFallback("AI session disconnected: " + status.statusCode)
            }
        }

        enhancementClient.createSession(options, callback)
            .addOnFailureListener(executor) {
                onFallback("AI session request failed: " + (it.message ?: "unknown error"))
            }
    }
}
