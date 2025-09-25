package com.osagem.photoview4rokidglasses

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
//给退出app按钮添加退出功能
import com.google.android.material.button.MaterialButton
//添加text的打字机逐个字符显示效果
import android.os.Handler
import android.os.Looper
import android.widget.TextView // 导入 TextView
import android.view.View // 导入 View
import android.animation.ObjectAnimator // 导入 ObjectAnimator
class MainActivity : AppCompatActivity() {

    //添加text的打字机逐个字符显示效果
    private lateinit var sloganTextView: TextView
    private val fullSloganText: String by lazy { getString(R.string.main_slogan) }
    private var sloganIndex = 0
    private val typingDelay = 150L // 每个字符显示的延迟时间 (毫秒)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var typeRunnable: Runnable

    private lateinit var buttonEnter: MaterialButton
    private lateinit var buttonExitApp: MaterialButton
    private val fadeInDuration = 500L // 淡入动画的持续时间 (毫秒)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 获取 TextView 实例
        sloganTextView = findViewById(R.id.main_slogan)

        buttonEnter = findViewById(R.id.buttonEnter)
        buttonExitApp = findViewById(R.id.buttonExitapp)

        // 初始化时清空 TextView
        sloganTextView.text = ""
        // 定义打字机效果的 Runnable
        typeRunnable = object : Runnable {
            override fun run() {
                if (sloganIndex < fullSloganText.length) {
                    sloganTextView.append(fullSloganText[sloganIndex].toString())
                    sloganIndex++
                    handler.postDelayed(this, typingDelay)
                } else {
                    // 打字机效果完成
                    showButtonsWithFadeIn()
                }
            }
        }

        startTypingEffect()

        buttonExitApp.setOnClickListener {
            finishAndRemoveTask()
        }
    }
    private fun startTypingEffect() {
        sloganIndex = 0 // 重置索引
        sloganTextView.text = "" // 清空文本，以便重新开始
        buttonEnter.visibility = View.GONE
        buttonExitApp.visibility = View.GONE
        // 确保按钮在开始时完全透明，以便淡入效果从透明开始
        buttonEnter.alpha = 0f
        buttonExitApp.alpha = 0f
        handler.postDelayed(typeRunnable, typingDelay)
    }
    private fun showButtonsWithFadeIn() {
        // 先将按钮设置为可见，但保持透明
        buttonEnter.alpha = 0f
        buttonEnter.visibility = View.VISIBLE

        buttonExitApp.alpha = 0f
        buttonExitApp.visibility = View.VISIBLE

        // 为 buttonEnter 创建淡入动画
        ObjectAnimator.ofFloat(buttonEnter, "alpha", 0f, 1f).apply {
            duration = fadeInDuration
            start()
        }

        // 为 buttonExitApp 创建淡入动画
        ObjectAnimator.ofFloat(buttonExitApp, "alpha", 0f, 1f).apply {
            duration = fadeInDuration
            start()
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        // 移除回调以防止内存泄漏
        handler.removeCallbacks(typeRunnable)
    }
}