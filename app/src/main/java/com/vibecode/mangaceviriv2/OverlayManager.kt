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
    private var selectionResizeHandle: ImageView? = null
    private var statusBarView: LinearLayout? = null
    private var statusTextView: TextView? = null

    private var currentModelStatus: ModelStatus = ModelStatus.IDLE
    private var currentOperationMessage: String = "Hazir"

    private var lastRenderedStatusText: String = ""

    // dp -> px donusumu
    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

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

        bubble.setOnTouchListener(DragTouchListener(bubbleParams) { p ->
            runCatching { windowManager.updateViewLayout(bubble, p) }
        })

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
        selectionResizeHandle = null
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

        val frame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setStroke(dp(3), 0xFF42A5F5.toInt())
                cornerRadius = dp(12).toFloat()
                setColor(0x1A2196F3)
            }
        }

        val infoLabel = TextView(context).apply {
            text = "Alanı sürükle, köşeden büyüt, sonra Çevir"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(0xCC212121.toInt())
            }
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }

        val confirmButton = createActionButton("Çevir", 0xFF1E88E5.toInt()).apply {
            setOnClickListener {
                val p = frame.layoutParams as WindowManager.LayoutParams
                val minSize = dp(80)
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

        val cancelButton = createActionButton("İptal", 0xFF616161.toInt()).apply {
            setOnClickListener {
                removeSelectionFrame()
                setOperationStatus("Alan seçimi iptal edildi")
            }
        }

        val resizeHandle = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_crop)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xDD1E88E5.toInt())
            }
            setColorFilter(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(cancelButton)
            addView(confirmButton)
        }

        val topParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            topMargin = dp(8)
        }
        frame.addView(infoLabel, topParams)

        val controlsParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply {
            bottomMargin = dp(10)
        }
        frame.addView(controls, controlsParams)

        val resizeParams = FrameLayout.LayoutParams(
            dp(44),
            dp(44),
            Gravity.BOTTOM or Gravity.END
        ).apply {
            marginEnd = dp(8)
            bottomMargin = dp(8)
        }
        frame.addView(resizeHandle, resizeParams)

        val minSize = dp(80)
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

        frame.setOnTouchListener(DragTouchListener(params) { p -> runCatching { windowManager.updateViewLayout(frame, p) } })
        resizeHandle.setOnTouchListener(ResizeTouchListener(params, minSize) { p -> runCatching { windowManager.updateViewLayout(frame, p) } })

        runCatching {
            windowManager.addView(frame, params)
            selectionView = frame
            selectionResizeHandle = resizeHandle
            setOperationStatus("Seçim alanı aktif")
        }.onFailure {
            Log.e(TAG, "Selection overlay eklenemedi", it)
        }
    }

    private fun removeSelectionFrame() {
        val current = (selectionView?.layoutParams as? WindowManager.LayoutParams)
        if (current != null) {
            val minSize = dp(80)
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
        selectionResizeHandle = null
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

        val safeWidth = selection.width.coerceAtLeast(minSize).coerceAtMost(screenWidth)
        val safeHeight = selection.height.coerceAtLeast(minSize).coerceAtMost(screenHeight)
        val maxLeft = (screenWidth - safeWidth).coerceAtLeast(0)
        val maxTop = (screenHeight - safeHeight).coerceAtLeast(0)

        return selection.copy(
            left = selection.left.coerceIn(0, maxLeft),
            top = selection.top.coerceIn(0, maxTop),
            width = safeWidth,
            height = safeHeight
        )
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
                setColor(0xCC1F1F1F.toInt())
                setStroke(dp(1), 0x5533B5E5)
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
                setColor(0xFF1E88E5.toInt())
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
        private val onMove: (WindowManager.LayoutParams) -> Unit
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    onMove(params)
                    return true
                }
            }
            return false
        }
    }

    private class ResizeTouchListener(
        private val params: WindowManager.LayoutParams,
        private val minSize: Int,
        private val onResize: (WindowManager.LayoutParams) -> Unit
    ) : View.OnTouchListener {
        private var startW = 0
        private var startH = 0
        private var touchX = 0f
        private var touchY = 0f

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startW = params.width
                    startH = params.height
                    touchX = event.rawX
                    touchY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.width = (startW + (event.rawX - touchX).toInt()).coerceAtLeast(minSize)
                    params.height = (startH + (event.rawY - touchY).toInt()).coerceAtLeast(minSize)
                    onResize(params)
                    return true
                }
            }
            return false
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
        private const val OCR_CAPTURE_SETTLE_DELAY_MS = 140L
    }
}
