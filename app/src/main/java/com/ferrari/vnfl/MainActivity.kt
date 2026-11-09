package com.ferrari.vnfl

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.usb.UsbDevice
import android.webkit.PermissionRequest
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
import com.serenegiant.usb.UVCParam
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

    // --- diagnostico (so leitura): registra os eventos da camera USB para aparecer na tela ---
    private val eventos = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private fun logEv(s: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        eventos.add("$t  $s")
        while (eventos.size > 14) eventos.poll()
    }

    // --- compressao JPEG em linha PARALELA (nao trava a entrega da camera USB) ---
    private val frameLock = Object()
    @Volatile private var nextFrame: ByteArray? = null   // ultimo quadro cru aguardando compressao (quadro atrasado e descartado)
    @Volatile private var nextW = 0
    @Volatile private var nextH = 0
    @Volatile private var encoderOn = false
    private var encoderThread: Thread? = null
    @Volatile private var modosStr = ""   // tamanhos que a camera USB oferece (diagnostico, so leitura)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        preview = findViewById(R.id.preview)
        web = findViewById(R.id.web)

        Lic.init(this)
        Lic.abriu()               // conta esta abertura do app (limite da versão de avaliação)

        server = CameraServer(this)
        server.startSafe()
        iniciarEncoder()          // linha paralela que comprime os quadros em JPEG

        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { surfacePronta = true; ligarPreview() }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {
                surfacePronta = false
                try { helper?.removeSurface(preview.holder.surface) } catch (e: Exception) {}
            }
        })
        setupWebView()

        // câmera do próprio aparelho (para testar antes de ter a câmera USB)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (::web.isInitialized) web.reload()   // recarrega para a câmera do aparelho abrir já liberada
        }
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
        encoderOn = false
        synchronized(frameLock) { frameLock.notifyAll() }
        if (::server.isInitialized) server.stop()
    }

    // Comprime cada quadro em JPEG numa linha separada: a câmera entrega o quadro e é liberada na hora.
    private fun iniciarEncoder() {
        if (encoderThread != null) return
        encoderOn = true
        encoderThread = Thread {
            while (encoderOn) {
                var b: ByteArray? = null; var w = 0; var h = 0
                synchronized(frameLock) {
                    while (nextFrame == null && encoderOn) { try { frameLock.wait() } catch (e: InterruptedException) {} }
                    b = nextFrame; w = nextW; h = nextH; nextFrame = null
                }
                val buf = b ?: continue
                if (w <= 0 || h <= 0) continue
                try {
                    val out = ByteArrayOutputStream()
                    YuvImage(buf, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 85, out)
                    CameraServer.pushJpeg(out.toByteArray(), w, h)
                } catch (e: Exception) {}
            }
        }.apply { isDaemon = true; name = "jpeg-encoder"; start() }
    }

    // ----- câmera USB (UVC) -> stream MJPEG que o sistema web consome -----

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            logEv("1-detectada USB ${String.format("%04x:%04x", device.vendorId, device.productId)}")
            helper?.selectDevice(device)
        }
        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            // Abre a camera com o ajuste UVC_QUIRK_FIX_BANDWIDTH (128): corrige camera que
            // trava depois de alguns segundos no Android (pede mais banda do que o Android libera).
            // Rede de seguranca: se falhar, abre do jeito padrao (que ja reconhece).
            try {
                val param = UVCParam()
                param.quirks = UVCCamera.UVC_QUIRK_FIX_BANDWIDTH
                logEv("2-permissao OK, abrindo (ajuste de banda)")
                helper?.openCamera(param)
            } catch (e: Exception) {
                logEv("2-permissao OK, abrindo (padrao)")
                helper?.openCamera()
            }
        }
        override fun onCameraOpen(device: UsbDevice) {
            cameraAberta = true
            val s = try { helper?.previewSize } catch (e: Exception) { null }
            camW = s?.width ?: 640; camH = s?.height ?: 480
            // lista os tamanhos que a camera oferece (so para diagnostico; nao muda nada)
            modosStr = try { helper?.supportedSizeList?.joinToString(" ") { "${it.width}x${it.height}/t${it.type}" } ?: "" } catch (e: Exception) { "" }
            logEv("3-camera aberta ${camW}x${camH}")
            ligarPreview()
            try { helper?.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21) } catch (e: Exception) {}
            helper?.startPreview()
        }
        override fun onCameraClose(device: UsbDevice) { cameraAberta = false; logEv("camera fechou") }
        override fun onDeviceClose(device: UsbDevice) { logEv("dispositivo fechou") }
        override fun onDetach(device: UsbDevice) { cameraAberta = false; logEv("USB desconectada") }
        override fun onCancel(device: UsbDevice) { logEv("X-permissao NEGADA/cancelada") }
    }

    private val frameCallback = IFrameCallback { frame: ByteBuffer ->
        if (!cameraAberta || camW <= 0 || camH <= 0) return@IFrameCallback
        try {
            val b = ByteArray(frame.remaining()); frame.get(b)      // só copia (rápido) e libera a câmera
            synchronized(frameLock) { nextFrame = b; nextW = camW; nextH = camH; frameLock.notifyAll() }
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
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }   // libera câmera/mic para a página (127.0.0.1)
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != "127.0.0.1"   // tudo fica no servidor local
        }
        web.addJavascriptInterface(JsBridge(), "Android")   // o index.html detecta "modo nativo" por window.Android
        web.loadUrl("http://127.0.0.1:" + CameraServer.PORT + "/")
    }

    inner class JsBridge {
        @JavascriptInterface fun sair() { runOnUiThread { finishAffinity() } }   // fecha o aplicativo
        @JavascriptInterface fun stamp(): Long = CameraServer.lastStamp()
        @JavascriptInterface fun info(): String {
            val s = CameraServer.lastSize(); return s[0].toString() + "x" + s[1]
        }
        // botao "Conectar camera": recria o helper -> reenumera a USB -> dispara a permissao e abre a camera
        @JavascriptInterface fun conectar() {
            runOnUiThread {
                try { helper?.release() } catch (e: Exception) {}
                cameraAberta = false
                helper = CameraHelper().apply { setStateCallback(stateCallback) }
                logEv("reconectar solicitado")
            }
        }
        // diagnostico (so leitura): estado da camera USB + ultimos eventos, para mostrar na tela
        @JavascriptInterface fun diag(): String {
            val o = org.json.JSONObject()
            o.put("aberta", cameraAberta)
            o.put("w", camW); o.put("h", camH)
            val st = CameraServer.lastStamp()
            o.put("ultimoQuadroMs", if (st == 0L) -1L else System.currentTimeMillis() - st)
            o.put("temQuadro", CameraServer.hasFrame())
            o.put("modos", modosStr)
            o.put("eventos", org.json.JSONArray(eventos.toList()))
            return o.toString()
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
