/*
 * 设备控制·动作自测靶——能力对齐 Reticle 的 SelfTestActivity.java（Apache-2.0，Copyright 2026
 * Bumfuzzle；已改写为 Kotlin，并把文案改成 Air-Ko）。
 *
 * 目的：给 tap／长按／输入／滑动／返回 一个「点了也不打扰用户」的自家场地——助手可以先在靶上
 * 自证动作通路真的可用（工具 device_acttest 会来跑一遍），而不是拿用户的应用试错。
 * 自带自证：点击计数、输入框内容、窗口是否已关（静态字段，工具侧读取）。
 *
 * 只由 device_acttest 打开（界面上没有入口）；小窗（系统对话框主题），用完按返回自己关掉。
 */
package me.rerere.rikkahub.device

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class DeviceSelfTestActivity : Activity() {

    companion object {
        /** 自测时输入框里要出现的文本（工具与靶共用）。 */
        const val TYPED_EXPECTED = "airko-selftest"

        @Volatile
        var ready = false
            private set

        @Volatile
        var finished = false
            private set

        @Volatile
        var btnX = 0
            private set

        @Volatile
        var btnY = 0
            private set

        @Volatile
        var editX = 0
            private set

        @Volatile
        var editY = 0
            private set

        @Volatile
        var width = 0
            private set

        @Volatile
        var top = 0
            private set

        @Volatile
        var tapCount = 0
            private set

        @Volatile
        var typed = ""
            private set

        /** 由 device_acttest 调用（后台起 Activity 可能被系统拦 → 调用方查 ready）。 */
        fun open(context: Context) {
            runCatching {
                ready = false
                finished = false
                tapCount = 0
                typed = ""
                context.startActivity(
                    Intent(context, DeviceSelfTestActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    }
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        // 别把输入法弹出来：键盘会顶起窗口让坐标失效，还会吃掉第一次返回键
        runCatching {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }

        val label = TextView(this).apply {
            text = "Air-Ko self-test target - tap the button"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.DKGRAY)
            setPadding(20, 16, 20, 6)
        }
        val button = Button(this).apply {
            text = "Tap me"
            isAllCaps = false
            setOnClickListener {
                tapCount++
                label.text = "Air-Ko self-test target - taps: $tapCount"
            }
        }
        val edit = EditText(this).apply {
            hint = "input field (self-test)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    typed = s?.toString().orEmpty()
                }
            })
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(20, 20, 20, 20)
        }
        val params = { ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ) }
        layout.addView(label, params())
        layout.addView(button, params())
        layout.addView(edit, params())
        setContentView(layout)

        layout.post {
            runCatching {
                val buttonLocation = IntArray(2)
                button.getLocationOnScreen(buttonLocation)
                btnX = buttonLocation[0] + button.width / 2
                btnY = buttonLocation[1] + button.height / 2
                val editLocation = IntArray(2)
                edit.getLocationOnScreen(editLocation)
                editX = editLocation[0] + edit.width / 2
                editY = editLocation[1] + edit.height / 2
                width = layout.width
                top = buttonLocation[1]
                runCatching { edit.requestFocus() }
                ready = true
            }
        }
    }

    override fun onDestroy() {
        finished = true
        ready = false
        super.onDestroy()
    }
}
