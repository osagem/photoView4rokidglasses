package com.osagem.photoview4rokidglasses

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
//给退出app按钮添加退出功能
import com.google.android.material.button.MaterialButton
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        // 获取按钮实例
        val buttonExitApp = findViewById<MaterialButton>(R.id.buttonExitapp)
        // 设置点击监听器
        buttonExitApp.setOnClickListener {
            // 调用 finishAndRemoveTask() 方法结束当前 Activity 所属的整个任务（Task），并且从“最近任务列表”（Recents screen）中移除这个任务。
            finishAndRemoveTask()
        }
    }
}