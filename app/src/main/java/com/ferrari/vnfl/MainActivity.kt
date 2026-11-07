package com.ferrari.vnfl

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.base.CameraActivity
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.render.env.RotateType
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.ausbc.widget.IAspectRatio
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Etapa 1 — app mínimo só da câmera (valida se a OV6946/HD abre neste Android):
 *  - pede permissão de USB quando a câmera é conectada (libausbc, automático);
 *  - abre a câmera UVC e mostra a imagem ao vivo em tela cheia (por baixo);
 *  - serve o sistema web (assets) em http://127.0.0.1:47832 num WebView em tela cheia;
 *  - alimenta o stream /camera.mjpeg (MJPEG) com os quadros da câmera;
 *  - botão "Foto" salva um JPG e mostra resolução/formato detectados.
 *
 * Etapa 2 reutiliza esta mesma base (servidor + WebView + MJPEG já prontos).
 */
class MainActivity : CameraActivity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var cameraView: AspectRatioTextureView
    private lateinit var container: ViewGroup
    private lateinit var server: CameraServer

    // último quadro cru recebido da câmera (para o botão Foto)
    private var frameBytes: ByteArray? = null
    private var frameFmt = IPreviewDataCallBack.DataFormat.NV21
    private var frameW = 0
    private var frameH = 0
    private var lastUiPush = 0L

    // ----- integração com a libausbc 3.3.3 (API verificada no código-fonte da lib) -----

    override fun getRootView(inflater: LayoutInflater): View =
        inflater.inflate(R.layout.activity_camera, null)

    override fun getCameraView(): IAspectRatio {
        if (!::cameraView.isInitialized) cameraView = AspectRatioTextureView(this)
        return cameraView
    }

    override fun getCameraViewContainer(): ViewGroup {
        if (!::container.isInitialized) container = findViewById(R.id.cameraContainer)
        return container
    }

    override fun getGravity(): Int = Gravity.CENTER

    override fun getCameraRequest(): CameraRequest =
        CameraRequest.Builder()
            .setPreviewWidth(640)
            .setPreviewHeight(480)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setAspectRatioShow(true)
            .setDefaultRotateType(RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.NONE)
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)   // MJPEG primeiro; lib cai p/ YUYV se não houver
            .setCaptureRawImage(false)
            .setRawPreviewData(false)                                     // false = callback entrega NV21 (fácil de codificar em JPEG)
            .create()

    override fun initView() {
        super.initView()   // monta a câmera (CameraActivity da lib)
        web = findViewById(R.id.web)
        status = findViewById(R.id.status)
        server = CameraServer(this)
        server.startSafe()
        setupWebView()
        setStatus("Ligue a câmera USB no tablet (hub/cabo OTG).\nA permissão de USB vai aparecer — toque em OK/Permitir.")
    }

    override fun onCameraState(self: MultiCameraClient.ICamera, code: ICameraStateCallBack.State, msg: String?) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                val n = self.getAllPreviewSizes(null).size
                setStatus("Câmera USB aberta (" + n + " resoluções disponíveis).\nSe a imagem não aparecer, verifique o cabo/hub.")
                registerPreview()
            }
            ICameraStateCallBack.State.CLOSED -> setStatus("Câmera fechada. Reconecte o cabo USB e reabra o app.")
            ICameraStateCallBack.State.ERROR -> setStatus("Erro na câmera USB: " + (msg ?: "desconhecido") + "\nReconecte o cabo e reabra o app.")
        }
    }

    private fun registerPreview() {
        addPreviewDataCallBack(object : IPreviewDataCallBack {
            override fun onPreviewData(data: ByteArray?, width: Int, height: Int, format: IPreviewDataCallBack.DataFormat) {
                if (data == null || width <= 0 || height <= 0) return
                frameBytes = data; frameFmt = format; frameW = width; frameH = height
                // alimenta o stream MJPEG que o sistema web consome em <img src="/camera.mjpeg">
                CameraServer.pushJpeg(encodeJpeg(data, width, height, format), width, height)
                val now = System.currentTimeMillis()
                if (now - lastUiPush > 1000) {
                    lastUiPush = now
                    runOnUiThread {
                        setStatus("Câmera OK · " + width + "×" + height + " · " + fmtName(format) +
                            " · transmitindo em http://127.0.0.1:" + CameraServer.PORT + "/camera.mjpeg")
                    }
                }
            }
        })
    }

    private fun fmtName(f: IPreviewDataCallBack.DataFormat): String =
        if (f == IPreviewDataCallBack.DataFormat.NV21) "NV21" else "RGBA"

    private fun encodeJpeg(data: ByteArray, w: Int, h: Int, format: IPreviewDataCallBack.DataFormat): ByteArray {
        val out = ByteArrayOutputStream()
        if (format == IPreviewDataCallBack.DataFormat.NV21) {
            YuvImage(data, android.graphics.ImageFormat.NV21, w, h, null)
                .compressToJpeg(Rect(0, 0, w, h), 92, out)
        } else {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(data))
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
            bmp.recycle()
        }
        return out.toByteArray()
    }

    // ----- ponte JS (window.Android) — nomes usados pelo index.html no modo nativo -----

    /** Estado resumido da câmera (o sistema web chama window.Android.info()). */
    @JavascriptInterface
    fun info() {
        val s = CameraServer.lastSize()
        runOnUiThread {
            setStatus("Câmera: " + s[0] + "×" + s[1] + " · " +
                (if (CameraServer.hasFrame()) "transmitindo" else "sem quadro ainda"))
        }
    }

    /** Carimbo de tempo do último quadro (latência do stream, p/ diagnóstico). */
    @JavascriptInterface
    fun stamp(): Long = CameraServer.lastStamp()

    /** Botão Foto: salva um JPG com o último quadro cru da câmera. */
    @JavascriptInterface
    fun foto(nome: String?) {
        val d = frameBytes ?: return
        val jpg = encodeJpeg(d, frameW, frameH, frameFmt)
        val f = CameraFiles.savePhoto(this, jpg, nome)
        web.post {
            web.evaluateJavascript("window.FV_FOTO_SALVA && window.FV_FOTO_SALVA(" + JSONObject.quote(f.name) + ")", null)
        }
    }

    // ----- WebView em tela cheia com o sistema web embutido -----

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s: WebSettings = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        web.setBackgroundColor(-1)
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            // tudo fica no servidor local; navegadores externos não abrem daqui
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != "127.0.0.1"
        }
        web.addJavascriptInterface(this, "Android")
        // 127.0.0.1 é "secure context" — as APIs do sistema web funcionam
        web.loadUrl("http://127.0.0.1:" + CameraServer.PORT + "/")
    }

    private fun setStatus(t: String) {
        if (::status.isInitialized) status.text = t
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::server.isInitialized) server.stop()
    }
}
