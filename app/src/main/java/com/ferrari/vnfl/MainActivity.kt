package com.ferrari.vnfl

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.PixelCopy
import android.hardware.usb.UsbDevice
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import java.io.ByteArrayOutputStream

/**
 * Etapa 1 — app mínimo só da câmera (valida se a OV6946/HD abre neste Android).
 * Usa a biblioteca com.herohan:UVCAndroid (Maven Central), que lê a câmera USB (UVC)
 * direto pela USB Host API — o navegador do Android não enxerga câmera USB.
 *
 *  - pede permissão de USB quando a câmera é conectada;
 *  - mostra a imagem ao vivo em tela cheia;
 *  - a faixa de status mostra a resolução detectada;
 *  - botão "Foto" salva um JPG (prova a captura).
 */
class MainActivity : AppCompatActivity() {

    private var helper: CameraHelper? = null
    private lateinit var preview: SurfaceView
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())

    private var cameraAberta = false
    private var surfacePronta = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        preview = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        findViewById<Button>(R.id.foto).setOnClickListener { tirarFoto() }

        preview.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { surfacePronta = true; ligarPreview() }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {
                surfacePronta = false
                try { helper?.removeSurface(preview.holder.surface) } catch (e: Exception) {}
            }
        })
        setStatus("Ligue a câmera USB no aparelho (cabo/hub OTG).\nQuando aparecer o pedido de permissão da USB, toque em OK/Permitir.")
    }

    override fun onStart() {
        super.onStart()
        if (helper == null) {
            helper = CameraHelper().apply { setStateCallback(stateCallback) }
        }
    }

    override fun onStop() {
        super.onStop()
        helper?.release()
        helper = null
        cameraAberta = false
    }

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            setStatus("Câmera USB detectada. Abrindo…")
            helper?.selectDevice(device)                     // pede permissão e abre o dispositivo
        }
        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            helper?.openCamera()
        }
        override fun onCameraOpen(device: UsbDevice) {
            cameraAberta = true
            helper?.startPreview()
            ligarPreview()
            mostrarResolucao()
        }
        override fun onCameraClose(device: UsbDevice) { cameraAberta = false }
        override fun onDeviceClose(device: UsbDevice) {}
        override fun onDetach(device: UsbDevice) {
            cameraAberta = false
            setStatus("Câmera USB desconectada. Reconecte o cabo.")
        }
        override fun onCancel(device: UsbDevice) {
            setStatus("Permissão de USB negada. Reconecte a câmera e toque em Permitir.")
        }
    }

    private fun ligarPreview() {
        if (cameraAberta && surfacePronta) {
            try { helper?.addSurface(preview.holder.surface, false) } catch (e: Exception) {}
        }
    }

    private fun mostrarResolucao() {
        ui.post {
            val s = try { helper?.previewSize } catch (e: Exception) { null }
            val txt = if (s != null) "${s.width}×${s.height}" else "resolução não informada"
            setStatus("Câmera OK · $txt\nSe não vir a imagem, confira o cabo/hub USB.")
        }
    }

    private fun tirarFoto() {
        if (!cameraAberta) { toast("A câmera ainda não abriu."); return }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) { toast("Android muito antigo para a foto."); return }
        val w = if (preview.width > 0) preview.width else 640
        val h = if (preview.height > 0) preview.height else 480
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(preview, bmp, { res ->
                if (res == PixelCopy.SUCCESS) {
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    val f = CameraFiles.savePhoto(this, out.toByteArray(), "etapa1")
                    toast("Foto salva: ${f.name}")
                } else toast("Não consegui capturar a foto (código $res).")
                bmp.recycle()
            }, ui)
        } catch (e: Exception) { toast("Erro na foto: ${e.message}"); bmp.recycle() }
    }

    private fun setStatus(t: String) { ui.post { status.text = t } }
    private fun toast(t: String) { ui.post { Toast.makeText(this, t, Toast.LENGTH_SHORT).show() } }

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onResume() {
        super.onResume()
        // tela cheia (imersivo)
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
