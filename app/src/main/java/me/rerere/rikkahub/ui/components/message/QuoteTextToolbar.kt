package me.rerere.rikkahub.ui.components.message

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import me.rerere.rikkahub.R
import me.rerere.rikkahub.utils.getActivity

/**
 * 替换系统文本选择菜单：在「复制」旁边提供「引用并追问」。
 *
 * Compose 的 SelectionContainer 只暴露 copy/paste 回调，拿不到选区文本，因此这里用
 * 「先复制、再从剪贴板读取」的方式取回选中内容；[selectionText] 非空时（例如只读
 * TextField）直接读选区，不经过剪贴板。
 */
class QuoteTextToolbar(
    private val context: Context,
    private val selectionText: (() -> String?)? = null,
    private val onQuote: (String) -> Unit,
) : TextToolbar {
    private val handler = Handler(Looper.getMainLooper())
    private var popup: PopupWindow? = null

    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        hide()
        val anchor = context.getActivity()?.window?.decorView ?: return
        val canQuote = selectionText != null || onCopyRequested != null
        if (onCopyRequested == null && !canQuote) return

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        if (onCopyRequested != null) {
            row.addView(menuItem(context.getString(android.R.string.copy)) {
                onCopyRequested()
                hide()
            })
        }
        if (canQuote) {
            row.addView(menuItem(context.getString(R.string.chat_message_quote_and_ask)) {
                requestQuote(onCopyRequested)
            })
        }
        if (row.childCount == 0) return

        val window = PopupWindow(
            row,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            setBackgroundDrawable(ColorDrawable(0xF2323232.toInt()))
            elevation = dp(8).toFloat()
            isOutsideTouchable = true
            setOnDismissListener { status = TextToolbarStatus.Hidden }
        }
        row.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val height = row.measuredHeight
        val screenHeight = context.resources.displayMetrics.heightPixels
        val x = rect.left.toInt().coerceAtLeast(dp(8))
        val above = rect.top.toInt() - height - dp(8)
        val y = if (above > 0) {
            above
        } else {
            (rect.bottom.toInt() + dp(8)).coerceAtMost(screenHeight - height - dp(8))
        }
        popup = window
        status = TextToolbarStatus.Shown
        window.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }

    override fun hide() {
        popup?.dismiss()
        popup = null
        status = TextToolbarStatus.Hidden
    }

    private fun requestQuote(onCopyRequested: (() -> Unit)?) {
        val direct = selectionText?.invoke()?.takeIf { it.isNotBlank() }
        hide()
        if (direct != null) {
            onQuote(direct)
            return
        }
        onCopyRequested?.invoke()
        // 系统复制是异步的：下一帧再读剪贴板，避免拿到旧内容
        handler.postDelayed({
            val copied = readClipboard()
            if (!copied.isNullOrBlank()) onQuote(copied)
        }, 200)
    }

    private fun readClipboard(): String? {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()
    }

    private fun menuItem(label: String, action: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundResource(android.R.attr.selectableItemBackground)
            isClickable = true
            setOnClickListener {
                action()
                hide()
            }
        }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
