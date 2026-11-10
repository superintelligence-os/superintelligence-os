package com.sios.boveda

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Los archivos de los documentos (fotos y PDF), cada uno cifrado con AES-256-GCM con la clave de
 * archivos de la Bóveda (que vive dentro de la base cifrada). Nunca se escriben descifrados en el
 * disco: las fotos se descifran en memoria y los PDF se pasan al visor de Android por un archivo en
 * memoria (memfd), que desaparece al cerrarlo.
 */
object Archivos {
    const val MAX_PDF = 25L * 1024 * 1024
    private const val LADO_MAX = 2400

    private fun dir(ctx: Context) = File(Boveda.carpeta(ctx), "docs").apply { mkdirs() }
    private fun archivo(ctx: Context, id: String) = File(dir(ctx), id.filter { it.isLetterOrDigit() || it == '-' })

    private fun cifrar(datos: ByteArray, id: String): ByteArray {
        val clave = Boveda.claveDeArchivos()
        try {
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(clave, "AES"), GCMParameterSpec(128, iv)) }
            c.updateAAD(id.toByteArray())
            return iv + c.doFinal(datos)
        } finally {
            clave.fill(0)
        }
    }

    private fun descifrar(todo: ByteArray, id: String): ByteArray {
        val clave = Boveda.claveDeArchivos()
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(clave, "AES"), GCMParameterSpec(128, todo, 0, 12)) }
            c.updateAAD(id.toByteArray())
            return c.doFinal(todo, 12, todo.size - 12)
        } finally {
            clave.fill(0)
        }
    }

    /** Guarda unos bytes ya preparados (de una importación) con su id. */
    fun guardarBytes(ctx: Context, id: String, datos: ByteArray) {
        archivo(ctx, id).writeBytes(cifrar(datos, id))
    }

    fun leer(ctx: Context, a: Archivo): ByteArray = descifrar(archivo(ctx, a.id).readBytes(), a.id)

    fun borrar(ctx: Context, id: String) {
        val f = archivo(ctx, id)
        // Antes de borrarlo, se sobrescribe (en la memoria flash no es infalible, pero ayuda).
        runCatching { if (f.exists()) f.writeBytes(ByteArray(f.length().toInt().coerceAtMost(64 * 1024 * 1024))) }
        f.delete()
    }

    /** Una foto hecha con la cámara de la Bóveda: reducida y en JPEG, cifrada. */
    fun desdeFoto(ctx: Context, b: Bitmap, nombre: String): Archivo {
        val id = UUID.randomUUID().toString()
        val datos = jpeg(reducir(b))
        guardarBytes(ctx, id, datos)
        return Archivo(id, "image/jpeg", nombre, datos.size.toLong())
    }

    /** Un archivo elegido (foto o PDF): se lee entero en memoria, se cifra y ya. */
    fun desdeUri(ctx: Context, uri: Uri): Archivo? {
        val cr = ctx.contentResolver
        val mime = cr.getType(uri).orEmpty()
        val nombre = runCatching {
            cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull().orEmpty()
        val id = UUID.randomUUID().toString()
        if (mime == "application/pdf" || nombre.endsWith(".pdf", ignoreCase = true)) {
            val datos = cr.openInputStream(uri)?.use { s ->
                val salida = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_PDF) return null
                    salida.write(buf, 0, n)
                }
                salida.toByteArray()
            } ?: return null
            guardarBytes(ctx, id, datos)
            return Archivo(id, "application/pdf", nombre, datos.size.toLong())
        }
        val b = decodificar(ctx, uri) ?: return null
        val datos = jpeg(reducir(b))
        guardarBytes(ctx, id, datos)
        return Archivo(id, "image/jpeg", nombre, datos.size.toLong())
    }

    private fun decodificar(ctx: Context, uri: Uri): Bitmap? = runCatching {
        val fuente = android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)
        android.graphics.ImageDecoder.decodeBitmap(fuente) { d, info, _ ->
            d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            val lado = maxOf(info.size.width, info.size.height)
            if (lado > LADO_MAX) {
                val f = LADO_MAX.toFloat() / lado
                d.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
            }
        }
    }.getOrNull()

    private fun reducir(b: Bitmap): Bitmap {
        val lado = maxOf(b.width, b.height)
        if (lado <= LADO_MAX) return b
        val f = LADO_MAX.toFloat() / lado
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }

    private fun jpeg(b: Bitmap): ByteArray {
        val s = java.io.ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 88, s)
        return s.toByteArray()
    }

    /** La imagen de una página: la foto o la primera página del PDF, a [ancho] píxeles como mucho. */
    fun imagen(ctx: Context, a: Archivo, ancho: Int, pagina: Int = 0): Bitmap? = runCatching {
        val datos = leer(ctx, a)
        try {
            if (a.esPdf) paginaPdf(datos, pagina, ancho)
            else {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(datos, 0, datos.size, o)
                var muestra = 1
                while (o.outWidth / (muestra * 2) >= ancho) muestra *= 2
                BitmapFactory.decodeByteArray(datos, 0, datos.size, BitmapFactory.Options().apply { inSampleSize = muestra })
            }
        } finally {
            datos.fill(0)
        }
    }.getOrNull()

    fun paginasPdf(ctx: Context, a: Archivo): Int = runCatching {
        val datos = leer(ctx, a)
        try { conPdf(datos) { it.pageCount } } finally { datos.fill(0) }
    }.getOrDefault(1)

    private fun paginaPdf(datos: ByteArray, pagina: Int, ancho: Int): Bitmap? = conPdf(datos) { r ->
        val p = r.openPage(pagina.coerceIn(0, r.pageCount - 1))
        try {
            val alto = (ancho.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
            val b = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888)
            Canvas(b).drawColor(Color.WHITE)
            p.render(b, null, Matrix().apply { setScale(ancho.toFloat() / p.width, alto.toFloat() / p.height) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            b
        } finally {
            p.close()
        }
    }

    /** Abre el PDF desde la memoria (memfd): nada descifrado toca el disco. */
    private fun <T> conPdf(datos: ByteArray, bloque: (PdfRenderer) -> T): T {
        val fd = Os.memfd_create("boveda", 0)
        try {
            var escrito = 0
            while (escrito < datos.size) escrito += Os.write(fd, datos, escrito, datos.size - escrito)
            Os.lseek(fd, 0, OsConstants.SEEK_SET)
            val pfd = ParcelFileDescriptor.dup(fd)
            return PdfRenderer(pfd).use(bloque)
        } finally {
            Os.close(fd)
        }
    }
}
