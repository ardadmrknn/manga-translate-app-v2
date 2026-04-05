package com.vibecode.mangaceviriv2

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class OverlayManager(
    private val context: Context,
    private val onSelectionConfirmed: (FrameSelection) -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var bubbleView: ImageButton? = null
    private var selectionView: FrameLayout? = null
    private var statusBarView: LinearLayout? = null
    private var statusTextView: TextView? = null

    private var currentModelStatus: ModelStatus = ModelStatus.IDLE
    private var currentOperationMessage: String = "Hazir"

    private var lastRenderedStatusText: String = ""

    // dp -> px donusumu
    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()
    private fun selectionMinSizePx(): Int = dp(SELECTION_MIN_SIZE_DP)
    private fun selectionSafeMarginPx(): Int = dp(SELECTION_SAFE_MARGIN_DP)
    private fun moveTouchSlopPx(): Int = dp(MOVE_TOUCH_SLOP_DP)

    fun show() {
        if (bubbleView != null) return
        if (!canDrawOverlays()) {
            Log.w(TAG, "Overlay izni yok, bubble gosterilmeyecek")
            return
        }

        val bubble = createCircleButton(android.R.drawable.ic_menu_camera).apply {
            setOnClickListener { toggleSelectionFrame() }
        }

        val bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(20)
            y = dp(80)
        }

        bubble.setOnTouchListener(
            DragTouchListener(
                params = bubbleParams,
                touchSlopPx = moveTouchSlopPx(),
                onConstrainMove = { p, view -> clampBubbleLayoutParams(p, view) },
                onConstrainEnd = { p, view -> clampBubbleLayoutParams(p, view) },
                onMove = { p ->
                    runCatching { windowManager.updateViewLayout(bubble, p) }
                }
            )
        )

        runCatching {
            windowManager.addView(bubble, bubbleParams)
            bubbleView = bubble
            showStatusBar()
            setOperationStatus("Overlay hazir")
        }.onFailure {
            Log.e(TAG, "Bubble overlay eklenemedi", it)
        }
    }

    fun hide() {
        selectionView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        statusBarView?.let { runCatching { windowManager.removeView(it) } }
        selectionView = null
        bubbleView = null
        statusBarView = null
        statusTextView = null
        lastRenderedStatusText = ""
    }

    fun setOperationStatus(message: String) {
        updateState(operation = message.ifBlank { "Durum guncelleniyor" })
    }

    fun setModelLoading(detail: String? = null) {
        updateState(
            modelStatus = ModelStatus.LOADING,
            operation = detail?.takeIf { it.isNotBlank() } ?: "Model yukleniyor"
        )
    }

    fun setModelReady(detail: String? = null) {
        updateState(
            modelStatus = ModelStatus.READY,
            operation = detail?.takeIf { it.isNotBlank() } ?: "Model hazir"
        )
    }

    fun setModelRunning(detail: String? = null) {
        updateState(
            modelStatus = ModelStatus.RUNNING,
            operation = detail?.takeIf { it.isNotBlank() } ?: "Ceviri isleniyor"
        )
    }

    fun setModelError(detail: String? = null) {
        updateState(
            modelStatus = ModelStatus.ERROR,
            operation = detail?.takeIf { it.isNotBlank() } ?: "Model hatasi"
        )
    }

    private fun toggleSelectionFrame() {
        if (selectionView == null) showSelectionFrame() else removeSelectionFrame()
    }

    private fun showSelectionFrame() {
        if (!canDrawOverlays()) return

        val minSize = selectionMinSizePx()
        val defaultSelection = FrameSelection(
            left = dp(48),
            top = dp(120),
            width = dp(240),
            height = dp(240)
        )
        val initialSelection = clampSelection(loadSavedSelection() ?: defaultSelection, minSize)

        val params = WindowManager.LayoutParams(
            initialSelection.width,
            initialSelection.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialSelection.left
            y = initialSelection.top
        }

        val frame = FrameLayout(context).apply {
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setStroke(dp(3), COLOR_ACCENT)
                cornerRadius = dp(12).toFloat()
                setColor(COLOR_SELECTION_FILL)
            }
        }

        val sizeLabel = TextView(context).apply {
            text = "0 x 0"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(COLOR_PANEL_BG)
            }
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }

        var lastAppliedLayout = SelectionLayoutState.from(params)
        val applySelectionLayoutUpdate = { refreshSizeLabel: Boolean ->
            val nextState = SelectionLayoutState.from(params)
            if (nextState != lastAppliedLayout) {
                runCatching { windowManager.updateViewLayout(frame, params) }
                lastAppliedLayout = nextState
            }
            if (refreshSizeLabel) {
                updateSelectionSizeLabel(sizeLabel, params)
            }
        }

        val confirmButton = createActionButton("Çevir", COLOR_ACCENT_BUTTON).apply {
            setOnClickListener {
                val p = frame.layoutParams as WindowManager.LayoutParams
                val confirmedSelection = clampSelection(
                    FrameSelection(
                        left = p.x,
                        top = p.y,
                        width = p.width.coerceAtLeast(minSize),
                        height = p.height.coerceAtLeast(minSize)
                    ),
                    minSize
                )
                saveSelection(confirmedSelection)
                removeSelectionFrame()
                setOperationStatus("Alan seçildi, çeviri başlatıldı")
                Handler(Looper.getMainLooper()).postDelayed(
                    { onSelectionConfirmed(confirmedSelection) },
                    OCR_CAPTURE_SETTLE_DELAY_MS
                )
            }
        }

        val cancelButton = createActionButton("İptal", COLOR_NEUTRAL_BUTTON).apply {
            setOnClickListener {
                removeSelectionFrame()
                setOperationStatus("Alan seçimi iptal edildi")
            }
        }

        val presetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(
                createPresetButton("Tam") {
                    applyPresetSelection(params, SelectionPreset.FULL, minSize, defaultSelection)
                    applySelectionLayoutUpdate(true)
                }
            )
            addView(
                createPresetButton("Sol") {
                    applyPresetSelection(params, SelectionPreset.LEFT_HALF, minSize, defaultSelection)
                    applySelectionLayoutUpdate(true)
                }
            )
            addView(
                createPresetButton("Sag") {
                    applyPresetSelection(params, SelectionPreset.RIGHT_HALF, minSize, defaultSelection)
                    applySelectionLayoutUpdate(true)
                }
            )
            addView(
                createPresetButton("Son") {
                    applyPresetSelection(params, SelectionPreset.LAST_SAVED, minSize, defaultSelection)
                    applySelectionLayoutUpdate(true)
                }
            )
        }

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(cancelButton)
            addView(confirmButton)
        }

        val sizeParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            topMargin = dp(8)
            marginEnd = dp(8)
        }
        frame.addView(sizeLabel, sizeParams)

        val presetParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = dp(10)
        }
        frame.addView(presetRow, presetParams)

        val controlsParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = dp(20)
        }
        frame.addView(controls, controlsParams)

        updateSelectionSizeLabel(sizeLabel, params)

        frame.setOnTouchListener(
            DragTouchListener(
                params = params,
                touchSlopPx = moveTouchSlopPx(),
                onConstrainMove = { p, _ -> clampSelectionLayoutParams(p, minSize, applySnap = false) },
                onConstrainEnd = { p, _ -> clampSelectionLayoutParams(p, minSize, applySnap = true) },
                onMove = { p ->
                    applySelectionLayoutUpdate(false)
                }
            )
        )

        attachResizeHandle(
            frame = frame,
            params = params,
            minSize = minSize,
            sizeLabel = sizeLabel,
            anchor = ResizeAnchor.TOP_LEFT,
            layoutParams = FrameLayout.LayoutParams(
                dp(CORNER_HANDLE_SIZE_DP),
                dp(CORNER_HANDLE_SIZE_DP),
                Gravity.TOP or Gravity.START
            ).apply {
                topMargin = dp(2)
                marginStart = dp(2)
            },
            onResizeLayout = { applySelectionLayoutUpdate(true) },
            isCorner = true
        )
        attachResizeHandle(
            frame = frame,
            params = params,
            minSize = minSize,
            sizeLabel = sizeLabel,
            anchor = ResizeAnchor.TOP,
            layoutParams = FrameLayout.LayoutParams(
                dp(EDGE_HANDLE_LENGTH_DP),
                dp(EDGE_HANDLE_THICKNESS_DP),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = dp(2)
            },
            onResizeLayout = { applySelectionLayoutUpdate(true) },
            isCorner = false
        )
        attachResizeHandle(
            frame = frame,
            params = params,
            minSize = minSize,
            sizeLabel = sizeLabel,
            anchor = ResizeAnchor.TOP_RIGHT,
            layoutParams = FrameLayout.LayoutParams(
                dp(CORNER_HANDLE_SIZE_DP),
                dp(CORNER_HANDLE_SIZE_DP),
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(2)
                marginEnd = dp(2)
            },
            onResizeLayout = { applySelectionLayoutUpdate(true) },
            isCorner = true
        )
        attachResizeHandle(
            frame = frame,
            params = params,
            minSize = minSize,
            sizeLabel = sizeLabel,
            anchor = ResizeAnchor.RIGHT,
            layoutParams = FrameLayout.LayoutParams(
                dp(EDGE_HANDLE_THICKNESS_DP),
                dp(EDGE_HANDLE_LENGTH_DP),
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply {
                marginEnd = dp(2)
            },
            onResizeLayout = { applySelectionLayoutUpdate(true) },
            isCorner = false
        )
        attachResizeHandle(
            frame = frame,
            params = params,
            minSize = minSize,
            sizeLabel = sizeLabel,
            anchor = ResizeAnchor.BOTTOM_RIGHT,
            layoutParams = FrameLayout.LayoutParams(
                dp(CORNER_HANDLE_SIZE_DP),
                dp(CORNER_HANDLE_SIZE_DP),
                Gravity.BOTTOM or Gravity.END
            ).apply {
                bottomMargin = dp(2)
                marginEnd = dp(2)
            },
            onResizeLayout = { applySelectionLayoutUpdate(true) },
            isCorner = true
        )
        attachResizeHandle(
            frame = frame,
            params = params,
            minSize = minSize,
            sizeLabel = sizeLabel,
            anchor = ResizeAnchor.BOTTOM,
            layoutParams = FrameLayout.LayoutParams(
                dp(EDGE_HANDLE_LENGTH_DP),
                dp(EDGE_HANDLE_THICKNESS_DP),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(2)
            },
            onResizeLayout = { applySelectionLayoutUpdate(true) },
            isCorner = false
        )
        attachResizeHandle(
            frame = frame,
            params = params,
            minSize = minSize,
            sizeLabel = sizeLabel,
            anchor = ResizeAnchor.BOTTOM_LEFT,
            layoutParams = FrameLayout.LayoutParams(
                dp(CORNER_HANDLE_SIZE_DP),
                dp(CORNER_HANDLE_SIZE_DP),
                Gravity.BOTTOM or Gravity.START
            ).apply {
                bottomMargin = dp(2)
                marginStart = dp(2)
            },
            onResizeLayout = { applySelectionLayoutUpdate(true) },
            isCorner = true
        )
        attachResizeHandle(
            frame = frame,
            params = params,
            minSize = minSize,
            sizeLabel = sizeLabel,
            anchor = ResizeAnchor.LEFT,
            layoutParams = FrameLayout.LayoutParams(
                dp(EDGE_HANDLE_THICKNESS_DP),
                dp(EDGE_HANDLE_LENGTH_DP),
                Gravity.START or Gravity.CENTER_VERTICAL
            ).apply {
                marginStart = dp(2)
            },
            onResizeLayout = { applySelectionLayoutUpdate(true) },
            isCorner = false
        )

        runCatching {
            windowManager.addView(frame, params)
            selectionView = frame
            setOperationStatus("Seçim alanı aktif")
        }.onFailure {
            Log.e(TAG, "Selection overlay eklenemedi", it)
        }
    }

    private fun removeSelectionFrame() {
        val current = (selectionView?.layoutParams as? WindowManager.LayoutParams)
        if (current != null) {
            val minSize = selectionMinSizePx()
            saveSelection(
                clampSelection(
                    FrameSelection(
                        left = current.x,
                        top = current.y,
                        width = current.width.coerceAtLeast(minSize),
                        height = current.height.coerceAtLeast(minSize)
                    ),
                    minSize
                )
            )
        }
        selectionView?.let { runCatching { windowManager.removeView(it) } }
        selectionView = null
        setOperationStatus("Secim cercevesi kapatildi")
    }

    private fun loadSavedSelection(): FrameSelection? {
        if (!prefs.contains(KEY_LEFT)) return null
        val left = prefs.getInt(KEY_LEFT, dp(48))
        val top = prefs.getInt(KEY_TOP, dp(120))
        val width = prefs.getInt(KEY_WIDTH, dp(240))
        val height = prefs.getInt(KEY_HEIGHT, dp(240))
        return FrameSelection(left = left, top = top, width = width, height = height)
    }

    private fun saveSelection(selection: FrameSelection) {
        prefs.edit()
            .putInt(KEY_LEFT, selection.left)
            .putInt(KEY_TOP, selection.top)
            .putInt(KEY_WIDTH, selection.width)
            .putInt(KEY_HEIGHT, selection.height)
            .apply()
    }

    private fun clampSelection(selection: FrameSelection, minSize: Int): FrameSelection {
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels.coerceAtLeast(minSize)
        val screenHeight = metrics.heightPixels.coerceAtLeast(minSize)
        val safeMargin = selectionSafeMarginPx()
        val maxAllowedWidth = (screenWidth - safeMargin * 2).coerceAtLeast(minSize)
        val maxAllowedHeight = (screenHeight - safeMargin * 2).coerceAtLeast(minSize)

        val safeWidth = selection.width.coerceAtLeast(minSize).coerceAtMost(maxAllowedWidth)
        val safeHeight = selection.height.coerceAtLeast(minSize).coerceAtMost(maxAllowedHeight)
        val minLeft = safeMargin
        val minTop = safeMargin
        val maxLeft = (screenWidth - safeMargin - safeWidth).coerceAtLeast(minLeft)
        val maxTop = (screenHeight - safeMargin - safeHeight).coerceAtLeast(minTop)

        return selection.copy(
            left = selection.left.coerceIn(minLeft, maxLeft),
            top = selection.top.coerceIn(minTop, maxTop),
            width = safeWidth,
            height = safeHeight
        )
    }

    private fun clampSelectionLayoutParams(
        params: WindowManager.LayoutParams,
        minSize: Int,
        applySnap: Boolean = true
    ) {
        val clamped = clampSelection(
            FrameSelection(
                left = params.x,
                top = params.y,
                width = params.width.coerceAtLeast(minSize),
                height = params.height.coerceAtLeast(minSize)
            ),
            minSize
        )
        params.x = clamped.left
        params.y = clamped.top
        params.width = clamped.width
        params.height = clamped.height
        if (applySnap) {
            snapSelectionLayoutParams(params, minSize)
        }
    }

    private fun snapSelectionLayoutParams(params: WindowManager.LayoutParams, minSize: Int) {
        val metrics = context.resources.displayMetrics
        val safeMargin = selectionSafeMarginPx()
        val maxAllowedWidth = (metrics.widthPixels - safeMargin * 2).coerceAtLeast(minSize)
        val maxAllowedHeight = (metrics.heightPixels - safeMargin * 2).coerceAtLeast(minSize)
        val maxLeft = (metrics.widthPixels - safeMargin - params.width).coerceAtLeast(safeMargin)
        val maxTop = (metrics.heightPixels - safeMargin - params.height).coerceAtLeast(safeMargin)
        val threshold = dp(SELECTION_SNAP_THRESHOLD_DP)

        if (kotlin.math.abs(params.x - safeMargin) <= threshold) params.x = safeMargin
        if (kotlin.math.abs(params.x - maxLeft) <= threshold) params.x = maxLeft
        if (kotlin.math.abs(params.y - safeMargin) <= threshold) params.y = safeMargin
        if (kotlin.math.abs(params.y - maxTop) <= threshold) params.y = maxTop

        val rightDistance = kotlin.math.abs((params.x + params.width) - (metrics.widthPixels - safeMargin))
        if (rightDistance <= threshold) {
            params.width = (metrics.widthPixels - safeMargin - params.x).coerceIn(minSize, maxAllowedWidth)
        }

        val bottomDistance = kotlin.math.abs((params.y + params.height) - (metrics.heightPixels - safeMargin))
        if (bottomDistance <= threshold) {
            params.height = (metrics.heightPixels - safeMargin - params.y).coerceIn(minSize, maxAllowedHeight)
        }
    }

    private fun applyPresetSelection(
        params: WindowManager.LayoutParams,
        preset: SelectionPreset,
        minSize: Int,
        defaultSelection: FrameSelection
    ) {
        val metrics = context.resources.displayMetrics
        val safeMargin = selectionSafeMarginPx()
        val availableWidth = (metrics.widthPixels - safeMargin * 2).coerceAtLeast(minSize)
        val availableHeight = (metrics.heightPixels - safeMargin * 2).coerceAtLeast(minSize)

        when (preset) {
            SelectionPreset.FULL -> {
                params.x = safeMargin
                params.y = safeMargin
                params.width = availableWidth
                params.height = availableHeight
            }
            SelectionPreset.LEFT_HALF -> {
                val halfWidth = (availableWidth / 2).coerceAtLeast(minSize)
                params.x = safeMargin
                params.y = safeMargin
                params.width = halfWidth
                params.height = availableHeight
            }
            SelectionPreset.RIGHT_HALF -> {
                val halfWidth = (availableWidth / 2).coerceAtLeast(minSize)
                params.x = (metrics.widthPixels - safeMargin - halfWidth).coerceAtLeast(safeMargin)
                params.y = safeMargin
                params.width = halfWidth
                params.height = availableHeight
            }
            SelectionPreset.LAST_SAVED -> {
                val target = clampSelection(loadSavedSelection() ?: defaultSelection, minSize)
                params.x = target.left
                params.y = target.top
                params.width = target.width
                params.height = target.height
            }
        }

        clampSelectionLayoutParams(params, minSize)
    }

    private fun createPresetButton(text: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(COLOR_PRESET_BUTTON)
            }
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(6)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun attachResizeHandle(
        frame: FrameLayout,
        params: WindowManager.LayoutParams,
        minSize: Int,
        sizeLabel: TextView,
        anchor: ResizeAnchor,
        layoutParams: FrameLayout.LayoutParams,
        onResizeLayout: () -> Unit,
        isCorner: Boolean
    ) {
        val handle = ImageView(context).apply {
            background = GradientDrawable().apply {
                shape = if (isCorner) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(if (isCorner) COLOR_ACCENT_HANDLE else COLOR_EDGE_HANDLE)
            }
            alpha = 0.9f
            this.layoutParams = layoutParams
        }

        handle.setOnTouchListener(
            EdgeResizeTouchListener(
                params = params,
                minSize = minSize,
                anchor = anchor,
                touchSlopPx = moveTouchSlopPx(),
                onConstrainMove = { p, _ -> clampSelectionLayoutParams(p, minSize, applySnap = false) },
                onConstrainEnd = { p, _ -> clampSelectionLayoutParams(p, minSize, applySnap = true) },
                onResize = { p ->
                    onResizeLayout()
                    updateSelectionSizeLabel(sizeLabel, p)
                }
            )
        )

        frame.addView(handle)
    }

    private fun clampBubbleLayoutParams(params: WindowManager.LayoutParams, view: View) {
        val metrics = context.resources.displayMetrics
        val safeMargin = selectionSafeMarginPx()
        val bubbleWidth = view.width.takeIf { it > 0 } ?: dp(48)
        val bubbleHeight = view.height.takeIf { it > 0 } ?: dp(48)
        val maxX = (metrics.widthPixels - bubbleWidth - safeMargin).coerceAtLeast(safeMargin)
        val maxY = (metrics.heightPixels - bubbleHeight - safeMargin).coerceAtLeast(safeMargin)
        params.x = params.x.coerceIn(safeMargin, maxX)
        params.y = params.y.coerceIn(safeMargin, maxY)
    }

    private fun updateSelectionSizeLabel(label: TextView, params: WindowManager.LayoutParams) {
        val text = "${params.width} x ${params.height}"
        if (label.text != text) {
            label.text = text
        }
    }

    private fun showStatusBar() {
        if (statusBarView != null || !canDrawOverlays()) return

        val statusText = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            val padH = dp(12)
            val padV = dp(8)
            setPadding(padH, padV, padH, padV)
        }

        val statusContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = false
            isFocusable = false
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(COLOR_PANEL_BG)
                setStroke(dp(1), COLOR_PANEL_STROKE)
            }
            addView(
                statusText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(16)
        }

        runCatching {
            windowManager.addView(statusContainer, params)
            statusBarView = statusContainer
            statusTextView = statusText
        }.onFailure {
            Log.e(TAG, "Durum overlay eklenemedi", it)
        }
    }

    private fun updateState(modelStatus: ModelStatus? = null, operation: String? = null) {
        modelStatus?.let { currentModelStatus = it }
        operation?.let { currentOperationMessage = it }
        updateStatusDisplay()
    }

    private fun updateStatusDisplay() {
        if (statusTextView == null && bubbleView != null && canDrawOverlays()) {
            showStatusBar()
        }

        val modelMessage = when (currentModelStatus) {
            ModelStatus.IDLE -> "Model: bekliyor"
            ModelStatus.LOADING -> "Model: yukleniyor"
            ModelStatus.READY -> "Model: hazir"
            ModelStatus.RUNNING -> "Model: ceviri yapiyor"
            ModelStatus.ERROR -> "Model: hata"
        }
        val composedText = "$modelMessage | Islem: $currentOperationMessage"

        if (composedText == lastRenderedStatusText) return
        statusTextView?.text = composedText
        lastRenderedStatusText = composedText
    }

    private fun createCircleButton(iconRes: Int): ImageButton {
        val buttonSize = dp(48)
        val pad = dp(10)
        return ImageButton(context).apply {
            setImageResource(iconRes)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(COLOR_ACCENT)
            }
            setColorFilter(0xFFFFFFFF.toInt())
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize)
        }
    }

    private fun createActionButton(text: String, backgroundColor: Int): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(backgroundColor)
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(8)
            }
            isClickable = true
            isFocusable = true
        }
    }

    private class DragTouchListener(
        private val params: WindowManager.LayoutParams,
        private val touchSlopPx: Int,
        private val onConstrainMove: ((WindowManager.LayoutParams, View) -> Unit)? = null,
        private val onConstrainEnd: ((WindowManager.LayoutParams, View) -> Unit)? = null,
        private val onMove: (WindowManager.LayoutParams) -> Unit
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var dragging = false
        private var lastDispatchMs = 0L

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    lastDispatchMs = 0L
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - touchX).toInt()
                    val deltaY = (event.rawY - touchY).toInt()
                    if (!dragging && kotlin.math.abs(deltaX) < touchSlopPx && kotlin.math.abs(deltaY) < touchSlopPx) {
                        return false
                    }

                    dragging = true
                    params.x = startX + deltaX
                    params.y = startY + deltaY
                    onConstrainMove?.invoke(params, v)

                    if (event.eventTime - lastDispatchMs >= WINDOW_LAYOUT_UPDATE_THROTTLE_MS) {
                        onMove(params)
                        lastDispatchMs = event.eventTime
                    }
                    return true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        (onConstrainEnd ?: onConstrainMove)?.invoke(params, v)
                        onMove(params)
                        dragging = false
                        return true
                    }
                }
            }
            return false
        }
    }

    private class EdgeResizeTouchListener(
        private val params: WindowManager.LayoutParams,
        private val minSize: Int,
        private val anchor: ResizeAnchor,
        private val touchSlopPx: Int,
        private val onConstrainMove: ((WindowManager.LayoutParams, View) -> Unit)? = null,
        private val onConstrainEnd: ((WindowManager.LayoutParams, View) -> Unit)? = null,
        private val onResize: (WindowManager.LayoutParams) -> Unit
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var startW = 0
        private var startH = 0
        private var touchX = 0f
        private var touchY = 0f
        private var resizing = false
        private var lastDispatchMs = 0L

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    startW = params.width
                    startH = params.height
                    touchX = event.rawX
                    touchY = event.rawY
                    resizing = false
                    lastDispatchMs = 0L
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - touchX).toInt()
                    val deltaY = (event.rawY - touchY).toInt()
                    if (!resizing && kotlin.math.abs(deltaX) < touchSlopPx && kotlin.math.abs(deltaY) < touchSlopPx) {
                        return true
                    }

                    resizing = true
                    var newX = startX
                    var newY = startY
                    var newWidth = startW
                    var newHeight = startH

                    if (anchor.affectsLeft) {
                        newX = startX + deltaX
                        newWidth = startW - deltaX
                    }
                    if (anchor.affectsRight) {
                        newWidth = startW + deltaX
                    }
                    if (anchor.affectsTop) {
                        newY = startY + deltaY
                        newHeight = startH - deltaY
                    }
                    if (anchor.affectsBottom) {
                        newHeight = startH + deltaY
                    }

                    if (newWidth < minSize) {
                        if (anchor.affectsLeft && !anchor.affectsRight) {
                            val right = startX + startW
                            newWidth = minSize
                            newX = right - minSize
                        } else {
                            newWidth = minSize
                        }
                    }

                    if (newHeight < minSize) {
                        if (anchor.affectsTop && !anchor.affectsBottom) {
                            val bottom = startY + startH
                            newHeight = minSize
                            newY = bottom - minSize
                        } else {
                            newHeight = minSize
                        }
                    }

                    params.x = newX
                    params.y = newY
                    params.width = newWidth.coerceAtLeast(minSize)
                    params.height = newHeight.coerceAtLeast(minSize)
                    onConstrainMove?.invoke(params, v)

                    if (event.eventTime - lastDispatchMs >= WINDOW_LAYOUT_UPDATE_THROTTLE_MS) {
                        onResize(params)
                        lastDispatchMs = event.eventTime
                    }
                    return true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (resizing) {
                        (onConstrainEnd ?: onConstrainMove)?.invoke(params, v)
                        onResize(params)
                    }
                    resizing = false
                    return true
                }
            }
            return false
        }
    }

    private enum class SelectionPreset {
        FULL,
        LEFT_HALF,
        RIGHT_HALF,
        LAST_SAVED
    }

    private enum class ResizeAnchor(
        val affectsLeft: Boolean,
        val affectsTop: Boolean,
        val affectsRight: Boolean,
        val affectsBottom: Boolean
    ) {
        TOP_LEFT(true, true, false, false),
        TOP(false, true, false, false),
        TOP_RIGHT(false, true, true, false),
        RIGHT(false, false, true, false),
        BOTTOM_RIGHT(false, false, true, true),
        BOTTOM(false, false, false, true),
        BOTTOM_LEFT(true, false, false, true),
        LEFT(true, false, false, false)
    }

    private data class SelectionLayoutState(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    ) {
        companion object {
            fun from(params: WindowManager.LayoutParams): SelectionLayoutState {
                return SelectionLayoutState(
                    x = params.x,
                    y = params.y,
                    width = params.width,
                    height = params.height
                )
            }
        }
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    private enum class ModelStatus {
        IDLE,
        LOADING,
        READY,
        RUNNING,
        ERROR
    }

    companion object {
        private const val TAG = "OverlayManager"
        private const val PREFS_NAME = "overlay_selection_prefs"
        private const val KEY_LEFT = "left"
        private const val KEY_TOP = "top"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val COLOR_ACCENT = 0xFF00E5FF.toInt()
        private const val COLOR_ACCENT_BUTTON = 0xFF007C91.toInt()
        private const val COLOR_SELECTION_FILL = 0x1400E5FF
        private const val COLOR_PANEL_BG = 0xDD000000.toInt()
        private const val COLOR_PANEL_STROKE = 0x5536D4F0
        private const val COLOR_PRESET_BUTTON = 0xAA101820.toInt()
        private const val COLOR_NEUTRAL_BUTTON = 0xFF1A1A1A.toInt()
        private const val COLOR_ACCENT_HANDLE = 0xCC00E5FF.toInt()
        private const val COLOR_EDGE_HANDLE = 0xAA1D3138.toInt()
        private const val CORNER_HANDLE_SIZE_DP = 28
        private const val EDGE_HANDLE_THICKNESS_DP = 18
        private const val EDGE_HANDLE_LENGTH_DP = 54
        private const val SELECTION_MIN_SIZE_DP = 120
        private const val SELECTION_SAFE_MARGIN_DP = 8
        private const val SELECTION_SNAP_THRESHOLD_DP = 14
        private const val MOVE_TOUCH_SLOP_DP = 6
        private const val WINDOW_LAYOUT_UPDATE_THROTTLE_MS = 16L
        private const val OCR_CAPTURE_SETTLE_DELAY_MS = 140L
    }
}
