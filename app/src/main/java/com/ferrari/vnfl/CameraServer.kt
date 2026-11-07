package com.ferrari.vnfl

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * Servidor HTTP local em 127.0.0.1:47832 (mesma porta detectada pelo sistema web).
 * Serve:
 *  - os assets do app (o sistema web inteiro, offline) — com sobrescrita opcional em Dados/web/;
 *  - /camera.mjpeg — stream multipart/x-mixed-replace com os quadros da câmera UVC;
 *  - /fs/... — filesystem do consultório (o sistema web usa quando host = 127.0.0.1:47832):
 *      GET  /fs/stat?p=a/b/c        → {"k":"d"|"f"} ou 404
 *      GET  /fs/list?p=a/b          → [{"n":"nome","k":"d"|"f"}...]
 *      GET  /fs/file?p=a/b/c        → bytes do arquivo (header Last-Modified)
 *      PUT  /fs/file?p=a/b/c        → grava o corpo no arquivo
 *      POST /fs/mkdir?p=a/b         → cria pasta
 *      POST /fs/touch?p=a/b/c       → cria arquivo vazio
 *      DELETE /fs/rm?p=a/b[&r=1]    → remove (r=1 recursivo)
 *      GET  /fs/info                → {"pasta": "<caminho real dos dados no tablet>"}
 *  - /ping (keep-alive do sistema) e /licenca ({} — sem licenciamento no app por ora).
 */
class CameraServer(private val ctx: Context) : NanoHTTPD("127.0.0.1", PORT) {   // ouve SOMENTE o proprio aparelho (nao a rede Wi-Fi)

    companion object {
        const val PORT = 47832
        private const val TAG = "FVNL"

        // último quadro JPEG pronto (empurrado por MainActivity a cada frame da câmera)
        @Volatile private var curFrame: ByteArray? = null
        @Volatile private var curStamp: Long = 0L
        @Volatile private var curW = 0
        @Volatile private var curH = 0

        fun pushJpeg(jpg: ByteArray, w: Int, h: Int) {
            curFrame = jpg; curW = w; curH = h; curStamp = System.currentTimeMillis()
        }
        fun lastStamp(): Long = curStamp
        fun lastSize(): IntArray = intArrayOf(curW, curH)
        fun hasFrame(): Boolean = curFrame != null
    }

    fun startSafe() {
        try {
            start(SOCKET_READ_TIMEOUT, true)
        } catch (e: Exception) {
            Log.e(TAG, "Não consegui abrir a porta $PORT (outro processo usando?): " + e.message)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        if (session.method == Method.PUT && uri == "/fs/file") return serveFilePut(session)
        return when {
            uri == "/ping" -> json("{\"ok\":true}")
            uri == "/camera.mjpeg" -> serveMjpeg()
            uri == "/fs/info" -> json("{\"pasta\":\"" + esc(CameraFiles.dataRoot(ctx).absolutePath) + "\"}")
            uri == "/licenca" -> json("{}")
            uri.startsWith("/fs/") -> serveFs(session, uri)
            else -> serveStatic(session, uri)
        }
    }

    // ----- MJPEG: <img> do sistema web lê este stream como se fosse a câmera -----
    private fun serveMjpeg(): Response {
        val pin = PipedInputStream(512 * 1024)
        val pout = PipedOutputStream(pin)
        Thread {
            try {
                while (true) {
                    val f = curFrame
                    if (f == null) { Thread.sleep(50); continue }
                    val head = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + f.size + "\r\n\r\n"
                    pout.write(head.toByteArray(Charsets.US_ASCII))
                    pout.write(f)
                    pout.write("\r\n".toByteArray(Charsets.US_ASCII))
                    pout.flush()
                    Thread.sleep(33)   // ~30 fps
                }
            } catch (e: Exception) {
                // cliente desconectou — encerra a thread
            } finally {
                try { pout.close() } catch (e: Exception) {}
            }
        }.apply { isDaemon = true; name = "mjpeg-writer"; start() }
        return newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=frame", pin)
    }

    // ----- assets embutidos no APK (offline) -----
    private fun serveStatic(session: IHTTPSession, uri: String): Response {
        val path = if (uri == "/") "index.html" else uri.removePrefix("/")
        val mime = mimeOf(path)
        val ov = File(CameraFiles.dataRoot(ctx), "web/$path")   // sobrescrita opcional pelo usuário
        if (ov.isFile && ov.canRead()) {
            val etag = ov.lastModified().toString()
            if (etag == session.headers["if-none-match"]) {
                return newFixedLengthResponse(Response.Status.NOT_MODIFIED, mime, "")
            }
            val r = newChunkedResponse(Response.Status.OK, mime, ov.inputStream())
            r.addHeader("ETag", etag)
            return r
        }
        return try {
            newChunkedResponse(Response.Status.OK, mime, ctx.assets.open(path))
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404: $path")
        }
    }

    // ----- filesystem do consultório (mesmo contrato do programa de Windows) -----
    private fun serveFs(session: IHTTPSession, uri: String): Response {
        val ep = uri.removePrefix("/fs/").substringBefore("?")
        val root = CameraFiles.dataRoot(ctx)
        val f = safeFile(root, session.parameters["p"]?.firstOrNull() ?: "")
            ?: return bad("caminho inválido")
        val recursive = session.parameters["r"]?.firstOrNull() == "1"
        return try {
            when (ep) {
                "stat" -> when {
                    f.isDirectory -> json("{\"k\":\"d\"}")
                    f.isFile -> json("{\"k\":\"f\"}")
                    else -> notFound()
                }
                "list" -> {
                    if (!f.isDirectory) return notFound()
                    val sb = StringBuilder("[")
                    val kids = f.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
                    kids.forEachIndexed { i, c ->
                        if (i > 0) sb.append(",")
                        sb.append("{\"n\":\"").append(esc(c.name)).append("\",\"k\":\"")
                            .append(if (c.isDirectory) "d" else "f").append("\"}")
                    }
                    sb.append("]")
                    json(sb.toString())
                }
                "file" -> when {
                    f.isDirectory -> bad("é uma pasta")
                    !f.isFile -> notFound()
                    else -> {
                        val r = newChunkedResponse(Response.Status.OK, mimeOf(f.name), f.inputStream())
                        r.addHeader("Last-Modified", httpDate(f.lastModified()))
                        r
                    }
                }
                "mkdir" -> { if (!f.exists()) f.mkdirs(); if (f.isDirectory) json("{\"ok\":true}") else bad("não criou a pasta") }
                "touch" -> { f.parentFile?.mkdirs(); if (!f.exists()) f.createNewFile(); if (f.isFile) json("{\"ok\":true}") else bad("não criou o arquivo") }
                "rm" -> {
                    if (!f.exists()) return notFound()
                    val ok = if (recursive) f.deleteRecursively() else f.delete()
                    if (ok) json("{\"ok\":true}") else bad("não removeu")
                }
                else -> notFound()
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "erro")
        }
    }

    private fun serveFilePut(session: IHTTPSession): Response {
        val root = CameraFiles.dataRoot(ctx)
        val f = safeFile(root, session.parameters["p"]?.firstOrNull() ?: "") ?: return bad("caminho inválido")
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)                       // corpo vai para arquivo temporário ("postData")
            val tmp = files["postData"] ?: return bad("corpo vazio")
            f.parentFile?.mkdirs()
            val tmpF = File(f.absolutePath + ".tmp")
            File(tmp).copyTo(tmpF, overwrite = true)
            if (f.exists()) f.delete()
            if (!tmpF.renameTo(f)) return bad("falha ao gravar")
            json("{\"ok\":true}")
        } catch (e: Exception) {
            bad(e.message ?: "erro")
        }
    }

    // ----- auxiliares -----
    private fun safeFile(root: File, rel: String): File? {
        if (rel.isBlank()) return root     // caminho vazio = a própria pasta de dados (raiz)
        val parts = rel.split("/")
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return File(root, parts.joinToString(File.separator))
    }

    private fun json(s: String): Response = newFixedLengthResponse(Response.Status.OK, "application/json", s)
    private fun notFound(): Response = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "não encontrado")
    private fun bad(msg: String): Response = newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", msg)
    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun httpDate(ms: Long): String {
        val sdf = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("GMT")
        return sdf.format(java.util.Date(ms))
    }

    private fun mimeOf(p: String): String = when {
        p.endsWith(".html") -> "text/html; charset=utf-8"
        p.endsWith(".js") -> "application/javascript"
        p.endsWith(".css") -> "text/css"
        p.endsWith(".json") -> "application/json"
        p.endsWith(".png") -> "image/png"
        p.endsWith(".jpg") || p.endsWith(".jpeg") -> "image/jpeg"
        p.endsWith(".webp") -> "image/webp"
        p.endsWith(".svg") -> "image/svg+xml"
        p.endsWith(".gif") -> "image/gif"
        p.endsWith(".ico") -> "image/x-icon"
        p.endsWith(".mp4") -> "video/mp4"
        p.endsWith(".webm") -> "video/webm"
        p.endsWith(".pdf") -> "application/pdf"
        p.endsWith(".woff2") -> "font/woff2"
        else -> "application/octet-stream"   // inclui .bin (modelo de IA das métricas)
    }
}
