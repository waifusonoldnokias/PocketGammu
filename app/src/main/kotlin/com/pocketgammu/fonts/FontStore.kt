package com.pocketgammu.fonts

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import com.pocketgammu.ui.logo.model.FontRef
import com.pocketgammu.ui.logo.model.LogoFont
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * User-imported TrueType/OpenType fonts, copied into the app's private storage
 * (files/fonts) so they keep working after the original file moves.
 */
class FontStore(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "fonts").apply { mkdirs() }
    private val cache = HashMap<String, Typeface>()
    private val _fonts = MutableStateFlow(scan())

    /** Imported fonts, by file name. */
    val fonts: StateFlow<List<String>> = _fonts.asStateFlow()

    private fun scan(): List<String> =
        dir.listFiles { f -> f.isFile && f.extension.lowercase() in EXTENSIONS }?.map { it.name }?.sorted().orEmpty()

    /** All selectable fonts: built-ins first, then imported ones. */
    fun all(): List<FontRef> = FontRef.builtins + _fonts.value.map { FontRef.Custom(it) }

    fun typeface(ref: FontRef, bold: Boolean): Typeface {
        val style = if (bold) Typeface.BOLD else Typeface.NORMAL
        return when (ref) {
            is FontRef.Builtin -> Typeface.create(
                when (ref.font) {
                    LogoFont.SANS -> "sans-serif"
                    LogoFont.SERIF -> "serif"
                    LogoFont.MONO -> "monospace"
                    LogoFont.CONDENSED -> "sans-serif-condensed"
                },
                style,
            )
            is FontRef.Custom -> {
                val base = synchronized(cache) {
                    cache.getOrPut(ref.fileName) {
                        val f = File(dir, ref.fileName)
                        if (f.exists()) Typeface.Builder(f).build() ?: Typeface.DEFAULT else Typeface.DEFAULT
                    }
                }
                // Fake bold for fonts that have no bold face.
                if (bold) Typeface.create(base, Typeface.BOLD) else base
            }
        }
    }

    /** Copies a picked font into the app. Returns the stored file name. */
    suspend fun import(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val display = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "font.ttf"
            val ext = display.substringAfterLast('.', "").lowercase()
            require(ext in EXTENSIONS) { "Choose a .ttf or .otf font file" }
            val base = display.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _.-]"), "_").take(40).ifBlank { "font" }
            var target = File(dir, "$base.$ext")
            var n = 2
            while (target.exists()) target = File(dir, "$base ($n).$ext").also { n++ }
            app.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                ?: error("Couldn't read the font file")
            val tf = Typeface.Builder(target).build()
            if (tf == null) {
                target.delete()
                error("That file isn't a font Android can read")
            }
            synchronized(cache) { cache[target.name] = tf }
            _fonts.value = scan()
            target.name
        }
    }

    fun delete(fileName: String) {
        File(dir, fileName).delete()
        synchronized(cache) { cache.remove(fileName) }
        _fonts.value = scan()
    }

    private companion object {
        val EXTENSIONS = setOf("ttf", "otf")
    }
}
