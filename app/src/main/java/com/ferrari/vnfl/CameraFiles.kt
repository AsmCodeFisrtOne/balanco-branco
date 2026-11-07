package com.ferrari.vnfl

import android.content.Context
import java.io.File

/**
 * Pasta de dados do consultório no tablet (etapa 2 usa a mesma pasta).
 * Fica no armazenamento externo privado do app (sem permissão, sobrevive a reinstalação
 * enquanto o usuário não apagar os dados do app) e pode ser copiada pelo backup ZIP (SAF).
 */
object CameraFiles {

    fun dataRoot(ctx: Context): File {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val dir = File(base, "Dados")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun safeName(s: String?): String {
        val n = (s ?: "").ifBlank { "captura" }
        return n.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120)
    }

    /** Botão Foto da Etapa 1: salva JPG em Dados/avulsos/ (mesma pasta de capturas soltas do sistema). */
    fun savePhoto(ctx: Context, jpg: ByteArray, nome: String?): File {
        val d = File(dataRoot(ctx), "avulsos")
        if (!d.exists()) d.mkdirs()
        val b = safeName(nome)
        var f = File(d, "$b.jpg")
        var i = 1
        while (f.exists()) { f = File(d, "$b-$i.jpg"); i++ }
        f.writeBytes(jpg)
        return f
    }
}
