package com.ferrari.vnfl

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.usb.UsbDevice
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.UVCCamera
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * App do médico (Ferrari Medical — Videonasofibrolaringoscopia).
 *
 * O sistema web inteiro (prontuário, laudos, métrica, configurações, recepção) roda num
 * WebView em tela cheia, servido por um servidor local em 127.0.0.1 (só o próprio aparelho).
 * A câmera USB (UVC) é lida nativamente pela lib com.herohan:UVCAndroid e entra no sistema
 * como stream MJPEG — o navegador do Android não enxerga câmera USB sozinho.
 *
 * Sem a câmera USB conectada, tudo funciona, menos a imagem ao vivo (que aparece assim que
 * a câmera for plugada por OTG).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var server: CameraServer
    private lateinit var web: WebView
    private lateinit var preview: SurfaceView

    private var helper: CameraHelper? = null
    private var surfacePronta = false
    private var cameraAberta = false
    private var camW = 0
    private var camH = 0
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        preview = findViewById(R.id.preview)
        web = findViewById(R.id.web)

        server = CameraServer(this)
        server.startSafe()

        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { surfacePronta = true; ligarPreview() }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {
                surfacePronta = false
                try { helper?.removeSurface(preview.holder.surface) } catch (e: Exception) {}
            }
        })
        setupWebView()
    }

    override fun onStart() {
        super.onStart()
        if (helper == null) helper = CameraHelper().apply { setStateCallback(stateCallback) }
    }

    override fun onStop() {
        super.onStop()
        helper?.release(); helper = null; cameraAberta = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::server.isInitialized) server.stop()
    }

    // ----- câmera USB (UVC) -> stream MJPEG que o sistema web consome -----

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) { helper?.selectDevice(device) }
        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) { helper?.openCamera() }
        override fun onCameraOpen(device: UsbDevice) {
            cameraAberta = true
            val s = try { helper?.previewSize } catch (e: Exception) { null }
            camW = s?.width ?: 640; camH = s?.height ?: 480
            ligarPreview()
            try { helper?.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21) } catch (e: Exception) {}
            helper?.startPreview()
        }
        override fun onCameraClose(device: UsbDevice) { cameraAberta = false }
        override fun onDeviceClose(device: UsbDevice) {}
        override fun onDetach(device: UsbDevice) { cameraAberta = false }
        override fun onCancel(device: UsbDevice) {}
    }

    private val frameCallback = IFrameCallback { frame: ByteBuffer ->
        if (!cameraAberta || camW <= 0 || camH <= 0) return@IFrameCallback
        try {
            val b = ByteArray(frame.remaining()); frame.get(b)
            val out = ByteArrayOutputStream()
            YuvImage(b, ImageFormat.NV21, camW, camH, null).compressToJpeg(Rect(0, 0, camW, camH), 90, out)
            CameraServer.pushJpeg(out.toByteArray(), camW, camH)
        } catch (e: Exception) {}
    }

    private fun ligarPreview() {
        if (cameraAberta && surfacePronta) {
            try { helper?.addSurface(preview.holder.surface, false) } catch (e: Exception) {}
        }
    }

    // ----- sistema web em tela cheia -----

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != "127.0.0.1"   // tudo fica no servidor local
        }
        web.addJavascriptInterface(JsBridge(), "Android")   // o index.html detecta "modo nativo" por window.Android
        web.loadUrl("http://127.0.0.1:" + CameraServer.PORT + "/")
    }

    inner class JsBridge {
        @JavascriptInterface fun stamp(): Long = CameraServer.lastStamp()
        @JavascriptInterface fun info(): String {
            val s = CameraServer.lastSize(); return s[0].toString() + "x" + s[1]
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
}
