package com.ferrari.vnfl

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Licença/avaliação do app (mesma regra e mesma chave do programa de Windows).
 * Sem chave: vale até 31/12/2026 OU 100 aberturas (o que vencer primeiro).
 * A chave é gerada pelo programa da gerência (mesmo segredo).
 *
 * Aviso: no Android não há "registro do Windows"; os dados ficam na pasta do app.
 * Limpar os dados do app ou reinstalar zera a contagem (trava mais fraca que no PC).
 */
object Lic {
    const val TRIAL_ATE = "20261231"
    const val TRIAL_MAX = 100
    private val SECRET = "IUFSFKEWSNUAP27MQDMHCNNOSUJMHGRY".toByteArray(Charsets.US_ASCII)
    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private lateinit var appCtx: Context
    fun init(ctx: Context) { appCtx = ctx.applicationContext }

    private fun hmac(msg: String): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(SECRET, "HmacSHA256"))
        return m.doFinal(msg.toByteArray(Charsets.US_ASCII))
    }

    private fun b32(b: ByteArray): String {
        val sb = StringBuilder(); var buf = 0; var bits = 0
        for (x in b) {
            buf = (buf shl 8) or (x.toInt() and 0xff); bits += 8
            while (bits >= 5) { bits -= 5; sb.append(B32[(buf ushr bits) and 0x1f]) }
        }
        if (bits > 0) sb.append(B32[(buf shl (5 - bits)) and 0x1f])
        return sb.toString()
    }

    private fun licSig(date: String): String {
        val s = b32(hmac("FMED-LIC|$date")).substring(0, 12)
        return s.substring(0, 4) + "-" + s.substring(4, 8) + "-" + s.substring(8, 12)
    }

    private fun norm(k: String): String {
        val r = StringBuilder()
        for (c in k.uppercase(Locale.US)) if (c in 'A'..'Z' || c in '0'..'9') r.append(c)
        val s = r.toString(); if (s.length != 20) return ""
        return s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-" + s.substring(16, 20)
    }

    // devolve a data (AAAAMMDD) se a chave for válida, senão null
    fun check(k: String): String? {
        val n = norm(k); if (n.isEmpty()) return null
        val d = n.substring(0, 8)
        try { SimpleDateFormat("yyyyMMdd", Locale.US).apply { isLenient = false }.parse(d) } catch (e: Exception) { return null }
        return if (n.substring(9) == licSig(d)) d else null
    }

    private data class LF(var chave: String = "", var ultimo: String = "", var aberturas: Int = 0, var sig: String = "")
    private fun assina(f: LF): String = b32(hmac("FMED-EST|${f.chave}|${f.ultimo}|${f.aberturas}")).substring(0, 16)

    private fun paths(): List<File> {
        val l = mutableListOf(File(appCtx.filesDir, "licenca.dat"))
        appCtx.getExternalFilesDir(null)?.let { l.add(File(it, "licenca.dat")) }
        return l
    }

    private fun load(): LF {
        var best = LF(); var adulterado = false
        for (p in paths()) {
            try {
                if (!p.exists()) continue
                val o = JSONObject(p.readText())
                val f = LF(o.optString("chave"), o.optString("ultimo"), o.optInt("aberturas"), o.optString("sig"))
                if (f.sig != assina(f)) { adulterado = true; continue }   // arquivo mexido à mão: trava
                if (f.aberturas > best.aberturas) best.aberturas = f.aberturas
                if (f.ultimo > best.ultimo) best.ultimo = f.ultimo
                if (check(f.chave) != null) { val db = check(best.chave) ?: ""; if (f.chave.substring(0, 8) > db) best.chave = f.chave }
            } catch (e: Exception) {}
        }
        if (adulterado && best.aberturas < TRIAL_MAX + 1) best.aberturas = TRIAL_MAX + 1
        return best
    }

    private fun save(f: LF) {
        f.sig = assina(f)
        val j = JSONObject().put("chave", f.chave).put("ultimo", f.ultimo).put("aberturas", f.aberturas).put("sig", f.sig).toString()
        for (p in paths()) try { p.parentFile?.mkdirs(); p.writeText(j) } catch (e: Exception) {}
    }

    private fun hoje(): String = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date())

    @Synchronized fun abriu() { val f = load(); f.aberturas += 1; save(f) }

    @Synchronized fun status(): JSONObject {
        val f = load(); var hoje = hoje()
        if (f.ultimo > hoje) hoje = f.ultimo                       // relógio voltou para trás: vale a maior data já vista
        else if (f.ultimo != hoje) { f.ultimo = hoje; save(f) }
        var ate = TRIAL_ATE; var trial = true
        val d = check(f.chave); if (d != null && d > ate) { ate = d; trial = false }
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val dias = try { ((fmt.parse(ate)!!.time - fmt.parse(hoje)!!.time) / 86400000L).toInt() } catch (e: Exception) { 0 }
        val o = JSONObject().put("trial", trial).put("ate", ate).put("hoje", hoje).put("dias", dias).put("usos", f.aberturas).put("max", TRIAL_MAX)
        when {
            hoje > ate -> o.put("vencida", true).put("motivo", "data")
            trial && f.aberturas > TRIAL_MAX -> o.put("vencida", true).put("motivo", "usos")
            else -> o.put("vencida", false)
        }
        return o
    }

    @Synchronized fun ativar(k: String): Boolean {
        if (check(k) == null) return false
        val f = load(); f.chave = norm(k); save(f); return true
    }
}
