package com.pocketgammu.ui.logo

import androidx.lifecycle.ViewModel
import com.pocketgammu.AppContainer
import com.pocketgammu.core.nokia.bitmap.BmpWriter
import com.pocketgammu.core.nokia.bitmap.MonoBitmap
import com.pocketgammu.core.nokia.image.ConversionSettings
import com.pocketgammu.core.nokia.image.ImageConverter
import com.pocketgammu.core.nokia.logo.LogoFormat
import com.pocketgammu.core.nokia.logo.LogoKind
import com.pocketgammu.core.nokia.logo.NetworkCode
import com.pocketgammu.core.nokia.logo.SmartLogoEncoder
import com.pocketgammu.core.nokia.sms.SmartMessage
import com.pocketgammu.ui.common.SendInput
import com.pocketgammu.ui.logo.model.ImageSource
import com.pocketgammu.ui.logo.model.Layer
import com.pocketgammu.ui.logo.model.LogoDocument
import com.pocketgammu.ui.logo.model.Painter
import com.pocketgammu.ui.logo.model.Px
import com.pocketgammu.ui.logo.model.TextSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Tool(val label: String) {
    PENCIL("Pencil"), ERASER("Erase"), FILL("Fill"), MOVE("Move"),
    LINE("Line"), RECT("Rectangle"), ELLIPSE("Ellipse"),
}

enum class EditorPanel(val label: String) { DRAW("Draw"), SHAPES("Shapes"), TEXT("Text"), IMAGE("Image"), LAYERS("Layers") }

/** Drawing colour: black ink, white paper, or transparent (lets lower layers show through). */
enum class InkColor(val label: String) { INK("Black"), PAPER("White"), CLEAR("Transparent") }

data class LogoUiState(
    val doc: LogoDocument = LogoDocument.new(LogoFormat.OPERATOR_DCT3),
    val tool: Tool = Tool.PENCIL,
    val panel: EditorPanel = EditorPanel.DRAW,
    val brush: Int = 1,
    val color: InkColor = InkColor.INK,
    val filled: Boolean = false,
    val showGrid: Boolean = true,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

class LogoEditorViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(LogoUiState())
    val state: StateFlow<LogoUiState> = _state.asStateFlow()

    private val undo = ArrayDeque<LogoDocument>()
    private val redo = ArrayDeque<LogoDocument>()

    // Active stroke
    private var strokeBase: LogoDocument? = null
    private var strokeStart: Pair<Int, Int>? = null
    private var strokeLast: Pair<Int, Int>? = null
    private var strokeChanged = false

    private val doc get() = _state.value.doc

    // --- history ---------------------------------------------------------

    private fun commit(newDoc: LogoDocument, base: LogoDocument = doc) {
        if (newDoc == base) return
        undo.addLast(base)
        if (undo.size > MAX_HISTORY) undo.removeFirst()
        redo.clear()
        _state.update { it.copy(doc = newDoc, canUndo = true, canRedo = false) }
    }

    fun undo() {
        val prev = undo.removeLastOrNull() ?: return
        redo.addLast(doc)
        _state.update { it.copy(doc = prev, canUndo = undo.isNotEmpty(), canRedo = true) }
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(doc)
        _state.update { it.copy(doc = next, canUndo = true, canRedo = redo.isNotEmpty()) }
    }

    // --- tool settings ---------------------------------------------------

    fun selectTool(tool: Tool) = _state.update { it.copy(tool = tool) }
    fun selectPanel(panel: EditorPanel) = _state.update {
        val tool = when (panel) {
            EditorPanel.DRAW -> if (it.tool in setOf(Tool.LINE, Tool.RECT, Tool.ELLIPSE)) Tool.PENCIL else it.tool
            EditorPanel.SHAPES -> if (it.tool !in setOf(Tool.LINE, Tool.RECT, Tool.ELLIPSE)) Tool.RECT else it.tool
            EditorPanel.TEXT, EditorPanel.IMAGE -> Tool.MOVE
            EditorPanel.LAYERS -> it.tool
        }
        it.copy(panel = panel, tool = tool)
    }
    fun setBrush(size: Int) = _state.update { it.copy(brush = size) }
    /** Picking a colour while erasing switches back to the pencil. */
    fun setColor(c: InkColor) = _state.update { it.copy(color = c, tool = if (it.tool == Tool.ERASER) Tool.PENCIL else it.tool) }
    fun setFilled(f: Boolean) = _state.update { it.copy(filled = f) }
    fun toggleGrid() = _state.update { it.copy(showGrid = !it.showGrid) }

    // --- strokes ---------------------------------------------------------

    fun strokeStart(x: Int, y: Int) {
        strokeBase = doc
        strokeStart = x to y
        strokeLast = x to y
        strokeChanged = _state.value.tool != Tool.MOVE
        applyStroke(x, y)
    }

    fun strokeMove(x: Int, y: Int) {
        if (strokeBase == null || strokeLast == (x to y) || _state.value.tool == Tool.FILL) return
        strokeChanged = true
        applyStroke(x, y)
        strokeLast = x to y
    }

    fun strokeEnd() {
        val base = strokeBase ?: return
        if (!strokeChanged) { strokeCancel(); return }
        var result = doc
        // A moved drawing layer is re-baked so it can be drawn on everywhere again.
        if (_state.value.tool == Tool.MOVE && result.active.text == null) {
            result = result.replaceActive(result.active.baked(result.width, result.height).copy(image = result.active.image))
        }
        _state.update { it.copy(doc = base) }
        commit(result, base)
        strokeBase = null; strokeStart = null; strokeLast = null; strokeChanged = false
    }

    fun strokeCancel() {
        strokeBase?.let { base -> _state.update { it.copy(doc = base) } }
        strokeBase = null; strokeStart = null; strokeLast = null
    }

    private fun applyStroke(x: Int, y: Int) {
        val s = _state.value
        val base = strokeBase ?: return
        val (sx, sy) = strokeStart ?: return
        // The eraser clears pixels on the active layer (layers below show through; on the
        // bottom layer that is plain paper), whatever colour is selected.
        val value = if (s.tool == Tool.ERASER) Px.CLEAR else when (s.color) {
            InkColor.INK -> Px.INK
            InkColor.PAPER -> Px.PAPER
            InkColor.CLEAR -> Px.CLEAR
        }

        if (s.tool == Tool.MOVE) {
            val src = base.active
            _state.update { it.copy(doc = base.replaceActive(src.copy(offsetX = src.offsetX + x - sx, offsetY = src.offsetY + y - sy))) }
            return
        }

        val current = _state.value.doc
        val working = when (s.tool) {
            // Freehand tools accumulate on the in-progress document; shapes redraw from the base.
            Tool.PENCIL, Tool.ERASER -> current
            else -> base
        }
        val layer = working.active.baked(working.width, working.height)
        val buf = layer.pixels.copyOf()
        val p = Painter(working.width, working.height, buf)
        val (lx, ly) = strokeLast ?: (x to y)
        when (s.tool) {
            Tool.PENCIL, Tool.ERASER -> p.line(lx, ly, x, y, value, s.brush)
            Tool.LINE -> p.line(sx, sy, x, y, value, s.brush)
            Tool.RECT -> p.rect(sx, sy, x, y, value, s.filled, s.brush)
            Tool.ELLIPSE -> p.ellipse(sx, sy, x, y, value, s.filled, s.brush)
            Tool.FILL -> p.fill(x, y, base.flatten().toBooleanArray(), value)
            Tool.MOVE -> Unit
        }
        // Painting turns text/image layers into plain drawing layers.
        _state.update { it.copy(doc = working.replaceActive(layer.copy(pixels = buf, text = null, image = null))) }
    }

    // --- document --------------------------------------------------------

    fun setFormat(format: LogoFormat) {
        if (format != doc.format) commit(doc.resizedTo(format))
    }

    fun clearCanvas() = commit(LogoDocument.new(doc.format))

    fun invertActive() {
        val l = doc.active.baked(doc.width, doc.height)
        val buf = ByteArray(l.pixels.size) { i -> if (l.pixels[i] == Px.INK) Px.PAPER else Px.INK }
        commit(doc.replaceActive(l.copy(pixels = buf)))
    }

    fun selectLayer(id: Long) = _state.update { it.copy(doc = it.doc.copy(activeId = id)) }

    fun addLayer() {
        if (doc.layers.size >= MAX_LAYERS) return
        val id = doc.nextId()
        commit(doc.copy(layers = doc.layers + Layer.blank(id, "Layer $id", doc.width, doc.height), activeId = id))
    }

    fun duplicateLayer() {
        if (doc.layers.size >= MAX_LAYERS) return
        val src = doc.active
        val id = doc.nextId()
        val copy = src.copy(id = id, name = "${src.name} copy", pixels = src.pixels.copyOf())
        val list = doc.layers.toMutableList().apply { add(doc.activeIndex + 1, copy) }
        commit(doc.copy(layers = list, activeId = id))
    }

    fun deleteLayer() {
        if (doc.layers.size <= 1) { commit(doc.replaceActive(Layer.blank(doc.activeId, doc.active.name, doc.width, doc.height))); return }
        val i = doc.activeIndex
        val list = doc.layers.filterNot { it.id == doc.activeId }
        commit(doc.copy(layers = list, activeId = list[(i - 1).coerceAtLeast(0)].id))
    }

    fun moveLayer(up: Boolean) {
        val i = doc.activeIndex
        val j = if (up) i + 1 else i - 1
        if (j !in doc.layers.indices) return
        val list = doc.layers.toMutableList().apply { add(j, removeAt(i)) }
        commit(doc.copy(layers = list))
    }

    fun toggleVisibility(id: Long) {
        commit(doc.copy(layers = doc.layers.map { if (it.id == id) it.copy(visible = !it.visible) else it }))
    }

    // --- text ------------------------------------------------------------

    fun addOrUpdateText(spec: TextSpec, editLayerId: Long?) {
        val r = TextRasterizer.render(spec, container.fonts.typeface(spec.font, spec.bold))
        val existing = editLayerId?.let { id -> doc.layers.firstOrNull { it.id == id } }
        if (existing != null) {
            // Keep the text centred on where it was.
            val cx = existing.offsetX + existing.width / 2
            val cy = existing.offsetY + existing.height / 2
            val updated = existing.copy(
                width = r.width, height = r.height, pixels = r.pixels, text = spec, name = "Text: ${spec.text.take(12)}",
                offsetX = cx - r.width / 2, offsetY = cy - r.height / 2,
            )
            commit(doc.copy(layers = doc.layers.map { if (it.id == existing.id) updated else it }, activeId = existing.id))
        } else {
            if (doc.layers.size >= MAX_LAYERS) return
            val id = doc.nextId()
            val layer = Layer(
                id, "Text: ${spec.text.take(12)}", r.width, r.height, r.pixels,
                offsetX = (doc.width - r.width) / 2, offsetY = (doc.height - r.height) / 2, text = spec,
            )
            commit(doc.copy(layers = doc.layers + layer, activeId = id))
            _state.update { it.copy(tool = Tool.MOVE) }
        }
    }

    fun centerActive() {
        val l = doc.active
        if (l.text == null) return
        commit(doc.replaceActive(l.copy(offsetX = (doc.width - l.width) / 2, offsetY = (doc.height - l.height) / 2)))
    }

    // --- images ----------------------------------------------------------

    fun previewImage(src: ImageSource): MonoBitmap =
        ImageConverter.convert(src.prepared, doc.width, doc.height, src.settings)

    fun addOrUpdateImage(src: ImageSource, editLayerId: Long?) {
        val mono = previewImage(src)
        val pixels = ByteArray(doc.width * doc.height) { i ->
            val ink = mono[i % doc.width, i / doc.width]
            when { ink -> Px.INK; src.whiteIsClear -> Px.CLEAR; else -> Px.PAPER }
        }
        val existing = editLayerId?.let { id -> doc.layers.firstOrNull { it.id == id } }
        if (existing != null) {
            val updated = Layer(existing.id, existing.name, doc.width, doc.height, pixels, visible = existing.visible, image = src)
            commit(doc.copy(layers = doc.layers.map { if (it.id == existing.id) updated else it }))
        } else {
            if (doc.layers.size >= MAX_LAYERS) return
            val id = doc.nextId()
            commit(doc.copy(layers = doc.layers + Layer(id, "Image", doc.width, doc.height, pixels, image = src), activeId = id))
        }
    }

    // --- sending ---------------------------------------------------------

    val smsUnsupportedReason: String?
        get() = if (doc.format.smsPort == null) "Startup logos can only be written over a cable." else null

    fun smartMessage(input: SendInput): SmartMessage? {
        val bmp = doc.flatten()
        return when (doc.format.kind) {
            LogoKind.OPERATOR -> input.network?.let { SmartLogoEncoder.operatorLogo(bmp, it) }
            LogoKind.CALLER_GROUP -> SmartLogoEncoder.callerGroupLogo(bmp)
            LogoKind.PICTURE -> SmartLogoEncoder.pictureMessage(bmp, input.text)
            LogoKind.STARTUP -> null
        }
    }

    /** Picture messages go into a picture-message slot (location) on 3310-class phones. */
    suspend fun sendToPhone(input: SendInput): Result<Unit> =
        container.phone.setLogo(doc.format.kind, doc.flatten(), input.network, location = input.slot, text = input.text)

    fun defaultImageSettings() = ConversionSettings()

    // --- saving ----------------------------------------------------------

    /** Suggested file name, e.g. "startup-logo-96x65.bmp". */
    val bmpFileName: String
        get() = doc.format.label.substringBefore(" (").lowercase().replace(' ', '-') + "-${doc.width}x${doc.height}.bmp"

    fun exportBmp(): ByteArray = BmpWriter.write(doc.flatten())

    private companion object {
        const val MAX_HISTORY = 50
        const val MAX_LAYERS = 8
    }
}
