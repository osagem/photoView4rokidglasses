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
import android.content.Intent // 给enter按钮添加打开照片列表功能
import com.osagem.photoview4rokidglasses.BuildConfig

class MainActivity : AppCompatActivity() {

    //添加text的打字机逐个字符显示效果
    private lateinit var sloganTextView: TextView
    private val fullSloganText: String by lazy { getString(R.string.main_slogan) }
    private var sloganIndex = 0
    private val typingDelay = 60L // 每个字符显示的延迟时间 (毫秒)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var typeRunnable: Runnable

    private lateinit var buttonEnter: MaterialButton
    private lateinit var buttonExitApp: MaterialButton
    private val fadeInDuration = 500L // 淡入动画的持续时间 (毫秒)
    private lateinit var versionTextView: TextView // 将 versionTextView 声明为类成员变量

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
        versionTextView = findViewById(R.id.main_version) // 初始化 versionTextView

        // 1. 找到 main_version TextView
        val versionTextView: TextView = findViewById(R.id.main_version)
        // 2. 从自动生成的 BuildConfig 中获取版本名称
        val versionName = BuildConfig.VERSION_NAME
        // 3. 将版本名称设置到 TextView 的文本中
        // 为了格式更清晰，你可以在前面加上 "v" 或 "Version "
        versionTextView.text = "v$versionName by osagem"

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

        // Set OnClickListener for buttonEnter
        buttonEnter.setOnClickListener {
            val intent = Intent(this, PhotoListActivity::class.java)
            startActivity(intent)
        }

        buttonExitApp.setOnClickListener {
            finishAndRemoveTask()
        }
    }
    private fun startTypingEffect() {
        // 为打字效果准备
        sloganIndex = 0 // 重置索引
        sloganTextView.text = "" // 清空文本，以便重新开始
        // 确保按钮和版本textview在开始时不可见和完全透明，以便淡入效果从透明开始
        versionTextView.alpha = 0f
        versionTextView.visibility = View.GONE
        buttonExitApp.alpha = 0f
        buttonExitApp.visibility = View.GONE
        buttonEnter.alpha = 0f
        buttonEnter.visibility = View.GONE
        handler.postDelayed(typeRunnable, typingDelay)
    }
    private fun showButtonsWithFadeIn() {
        // 将界面上两个按钮和一个版本TextView设置为可见，但保持透明
        versionTextView.alpha = 0f
        versionTextView.visibility = View.VISIBLE // 然后设置为可见
        buttonExitApp.alpha = 0f
        buttonExitApp.visibility = View.VISIBLE // 然后设置为可见
        buttonEnter.alpha = 0f
        buttonEnter.visibility = View.VISIBLE // 然后设置为可见

        // 为 versionTextView 创建淡入动画
        ObjectAnimator.ofFloat(versionTextView, "alpha", 0f, 1f).apply {
            duration = fadeInDuration
            start()
        }

        // 为 buttonExitApp 创建淡入动画
        ObjectAnimator.ofFloat(buttonExitApp, "alpha", 0f, 1f).apply {
            duration = fadeInDuration
            start()
        }

        // 为 buttonEnter 创建淡入动画
        ObjectAnimator.ofFloat(buttonEnter, "alpha", 0f, 1f).apply {
            duration = fadeInDuration
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // 动画结束后，让 "进入" 按钮获得焦点，方便用户直接点击进入
                    buttonEnter.requestFocus()
                }
            })
            start()
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        // 移除回调以防止内存泄漏
        handler.removeCallbacks(typeRunnable)
    }
}