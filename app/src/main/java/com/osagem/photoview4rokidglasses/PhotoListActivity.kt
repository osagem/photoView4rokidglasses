package com.osagem.photoview4rokidglasses

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.media3.common.MediaItem as ExoMediaItem
import kotlinx.coroutines.launch
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player

class PhotoListActivity : AppCompatActivity() {

    // 数据类和枚举
    data class MediaItem(
        val uri: Uri,
        val type: MediaType,
        val dateTaken: Long,
    )
    enum class MediaType { IMAGE, VIDEO }
    companion object {
        private const val DEBUG = true //false or true 调试开关：上线时改为 false 即可关闭所有调试日志

        private const val TAG = "PhotoManager"

        private fun debugLog(message: String) {
            if (DEBUG) Log.d(TAG, message)
        }
    }

    // UI 控件
    private lateinit var latestImageView: ImageView
    private lateinit var latestVideoView: PlayerView
    private lateinit var buttonBackmain: MaterialButton
    private lateinit var buttonDelphoto: MaterialButton
    private lateinit var buttonNext: MaterialButton
    private lateinit var photoCountTextView: TextView

    // 播放器和数据
    private var exoPlayer: ExoPlayer? = null
    private var allMediaItems = mutableListOf<MediaItem>()
    private var currentImageIndex = -1

    // 工具类
    private var centeredToast: Toast? = null
    private var emojiBitmap: Bitmap? = null
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var textView_videoInfo: TextView //增加文件信息显示
    private lateinit var deleteRequestLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    // --- 【新增代码块开始】 ---
    // 用于定时更新播放进度的 Handler 和 Runnable
    private val handler = Handler(Looper.getMainLooper())
    private val updateProgressAction = object : Runnable {
        override fun run() {
            exoPlayer?.let { player ->
                // 【修改】放宽条件：只要播放器不是空闲状态且有时长，就更新UI
                if (player.playbackState != Player.STATE_IDLE && player.duration > 0) {
                    val currentPosition = player.currentPosition
                    val totalDuration = player.duration
                    textView_videoInfo.text = getString(
                        R.string.video_info_format,
                        formatDuration(currentPosition),
                        formatDuration(totalDuration)
                    )
                }
            }
            // 每秒钟重复执行此任务
            handler.postDelayed(this, 1000)
        }
    }
    // --- 【新增代码块结束】 ---

    // ------------------- 生命周期管理-------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_photo_list)

        // 设置窗口
        setupWindowInsets()

        // 初始化视图
        initializeViews()

        // 在 onCreate 中只调用一次，创建播放器实例
        // 初始化播放器 这是播放器生命周期的起点
        initializePlayer()

        // 设置监听器等
        setupListeners()

        // 【新增】在这里初始化 permissionLauncher
        permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                // 检查读取权限是否被授予
                if (permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true) {
                    loadAllMediaUris()
                } else {
                    showCenteredToast(getString(R.string.toast_read_permission_denied))
                    finish() // 权限被拒绝，关闭页面
                }
            }

        // 开始业务逻辑
        checkAndRequestPermission()
        updatePhotoCountText()
        emojiBitmap = createBitmapFromEmoji("🤷", 200)
    }

    override fun onStart() {
        super.onStart()
        // 绑定 PlayerView 和 ExoPlayer 这会创建视频渲染所需的 Surface
        latestVideoView.player = exoPlayer
    }

    override fun onResume() {
        super.onResume()
        if (latestVideoView.isVisible && exoPlayer?.isPlaying == false) {
            exoPlayer?.play()
        }
    }

    override fun onPause() {
        super.onPause()
        // 统一在在此暂停播放，以节省资源。
        exoPlayer?.pause()
    }

    override fun onStop() {
        super.onStop()
        // 解除 PlayerView 和 ExoPlayer 的绑定 安全地释放 Surface，避免资源泄露和状态冲突
        latestVideoView.player = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // --- 【新增代码行】 ---
        // 停止所有待处理的进度更新任务
        handler.removeCallbacks(updateProgressAction)

        // 在 onDestroy 中彻底释放播放器资源 这是播放器生命周期的终点
        releasePlayer()
        centeredToast?.cancel()
        emojiBitmap?.recycle()
        emojiBitmap = null
    }

    // ------------------- 播放器初始化与释放 -------------------
    private fun initializePlayer() {
        // 这个方法现在只在 onCreate 中被调用一次
        // 它只负责创建实例，不涉及UI绑定
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            repeatMode = ExoPlayer.REPEAT_MODE_ONE

            // --- 【新增代码块开始】 ---
            // 添加监听器以在视频准备就绪时更新UI
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    // 当播放器准备好时
                    if (playbackState == Player.STATE_READY) {
                        val duration = exoPlayer?.duration ?: 0
                        // 【关键修改】不再依赖外部的 currentImageIndex。
                        // 只要播放器获得了有效的时长（意味着它是一个可播放的媒体，比如视频），就更新UI。
                        if (duration > 0) {
                            textView_videoInfo.text = getString(
                                R.string.video_info_format,
                                formatDuration(0),
                                formatDuration(duration)
                            )
                        }
                    }

                    // 【新增逻辑】如果媒体播放结束或者播放器停止，我们也需要清空文本
                    // 这能确保从视频切换到图片时，信息能被正确清除
                    if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                        if (latestImageView.isVisible) { // 确认当前是图片视图在显示
                            textView_videoInfo.text = ""
                        }
                    }
                }
            })
            // --- 【修改结束】 ---
        }
    }

    private fun releasePlayer() {
        // 这个方法现在只在 onDestroy 中被调用。
        // 在释放播放器本身之前，先从视图解绑。
        latestVideoView.player = null
        exoPlayer?.release()
        exoPlayer = null
    }

    // ------------------- 媒体加载与切换 -------------------
    private fun loadSpecificMedia(index: Int) {
        if (index !in allMediaItems.indices) {
            handleNoPhotosFound()
            return
        }
        currentImageIndex = index
        val item = allMediaItems[index]
        debugLog("Displaying ${item.type.name} → ${item.uri}")

        // 移除之前的所有定时任务，防止重复更新
        handler.removeCallbacks(updateProgressAction)

        // 统一管理视图可见性和播放器状态
        when (item.type) {
            MediaType.VIDEO -> {
                // 准备播放视频
                latestImageView.visibility = View.INVISIBLE
                latestVideoView.visibility = View.VISIBLE

                // 当是视频时，显示信息文本框
                textView_videoInfo.visibility = View.VISIBLE
                // 2. 为了调试，我们先给它一个临时的文本。
                //    如果这个文本能显示，说明我们的UI控制是有效的。
                textView_videoInfo.text = "..." // 设置一个加载中的占位符

                // 确保PlayerView与播放器绑定。ExoPlayer将自动处理Surface的获取。
                if (latestVideoView.player == null) {
                    latestVideoView.player = exoPlayer
                }

                // 使用ExoPlayer的高效媒体项切换API
                val mediaItem = ExoMediaItem.fromUri(item.uri)
                exoPlayer?.setMediaItem(mediaItem)
                exoPlayer?.prepare() // 准备新的媒体项
                exoPlayer?.play()     // 开始或恢复播放

                // 启动进度更新的定时任务
                handler.post(updateProgressAction)
                debugLog("Playing video and starting progress updates.")
            }
            MediaType.IMAGE -> {
                // 当是图片时，隐藏信息文本框
                textView_videoInfo.visibility = View.INVISIBLE
                textView_videoInfo.text = "" // 同时清空文本

                // 停止播放并从PlayerView解绑，这是关键！
                // 这会干净地释放Surface，避免资源冲突。
                exoPlayer?.stop() // 停止播放
                latestVideoView.player = null // 解绑

                // 准备显示图片
                latestVideoView.visibility = View.INVISIBLE
                latestImageView.visibility = View.VISIBLE

                // 加载图片
                Glide.with(this)
                    .load(item.uri)
                    .into(latestImageView)
                debugLog("Displaying image.")
            }
        }
        updatePhotoCountText()
    }


    // ------------------- 其他辅助方法 -------------------

    // --- 【新增代码块开始】 ---
    private fun formatDuration(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }
// --- 【新增代码块结束】 ---

    private fun initializeViews() {
        latestImageView = findViewById(R.id.latestImageView)
        latestVideoView = findViewById(R.id.playerView)
        buttonNext = findViewById(R.id.buttonNext)
        buttonBackmain = findViewById(R.id.buttonBackmain)
        buttonBackmain.visibility = View.VISIBLE
        buttonDelphoto = findViewById(R.id.buttonDelphoto)
        photoCountTextView = findViewById(R.id.photoCountTextView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        textView_videoInfo = findViewById(R.id.textView_videoInfo) //增加文件信息显示 初始化
    }

    // 视频加载耗时等待时的加载指示器
    private fun showLoadingIndicator(show: Boolean) {
        loadingIndicator.visibility = if (show) View.VISIBLE else View.GONE
    }

    // 查看窗口获得焦点时，请求next按钮获取焦点，改进体验
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            buttonNext.requestFocus()
        }
    }

    private fun setupListeners() {
        // 设置删除操作的结果回调
        deleteRequestLauncher =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    // 用户在系统的确认对话框中点击了“允许”，现在可以执行真正的删除操作
                    deleteCurrentImage()
                } else {
                    // 用户取消了操作
                    showCenteredToast(getString(R.string.toast_photo_deletion_cancelled_failed))
                }
            }

        // 为“下一个”按钮设置点击事件
        buttonNext.setOnClickListener { loadNextMedia() }

        // 为“返回主页”按钮设置点击事件
        buttonBackmain.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        // 为“删除”按钮设置点击事件
        buttonDelphoto.setOnClickListener {
            if (allMediaItems.isNotEmpty() && currentImageIndex in allMediaItems.indices) {
                deleteCurrentImage()
            } else {
                showCenteredToast(getString(R.string.toast_no_photo_selected_to_del))
            }
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun checkAndRequestPermission() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (permissionsToRequest.isNotEmpty()) {
            // 使用已声明的成员变量来启动权限请求
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            loadAllMediaUris()
        }
    }

    private fun deleteCurrentImage() {
        val uriToDelete = allMediaItems[currentImageIndex].uri
        try {
            val rowsDeleted = contentResolver.delete(uriToDelete, null, null)
            if (rowsDeleted > 0) {
                handleDeletionSuccess(uriToDelete)
            } else {
                showCenteredToast(getString(R.string.toast_failed_to_delete_photo))
            }
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val recoverable = e as? RecoverableSecurityException
                if (recoverable != null) {
                    val intentSender: IntentSender =
                        recoverable.userAction.actionIntent.intentSender
                    deleteRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                } else {
                    showCenteredToast(getString(R.string.toast_deletion_failed_security_reasons))
                }
            } else {
                showCenteredToast(getString(R.string.toast_write_permission_granted))
            }
        }
    }

    private fun handleDeletionSuccess(deletedUri: Uri? = null) {
        deletedUri?.let { debugLog("Deleted → $it") }
        showCenteredToast(getString(R.string.toast_photo_deleted_succe))
        exoPlayer?.stop()
        allMediaItems.removeAt(currentImageIndex)
        if (allMediaItems.isEmpty()) {
            handleNoPhotosFound()
        } else {
            if (currentImageIndex >= allMediaItems.size) {
                currentImageIndex = allMediaItems.size - 1
            }
            loadSpecificMedia(currentImageIndex)
        }
    }

    private fun loadAllMediaUris() {
        // 使用 lifecycleScope 启动一个协程，它会自动在 Activity 销毁时取消
        lifecycleScope.launch {
            // 显示一个加载指示器（可选，但推荐）
            showLoadingIndicator(true)
            val mediaResult = withContext(Dispatchers.IO) {
                val imageItems = queryMedia(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    Environment.DIRECTORY_DCIM + File.separator + "Camera",
                    MediaType.IMAGE
                )
                val picturesItems = queryMedia(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    Environment.DIRECTORY_PICTURES,
                    MediaType.IMAGE
                )
                val videoItems = queryMedia(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    Environment.DIRECTORY_MOVIES + File.separator + "Camera",
                    MediaType.VIDEO
                )
                val videoBItems = queryMedia(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    Environment.DIRECTORY_PICTURES,
                    MediaType.VIDEO
                )
                val videoCItems = queryMedia(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    Environment.DIRECTORY_MOVIES,
                    MediaType.VIDEO
                )
                val videoDItems = queryMedia(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    Environment.DIRECTORY_DCIM + File.separator + "Camera",
                    MediaType.VIDEO
                )
                // 在后台合并并排序
                (imageItems + picturesItems + videoItems + videoBItems + videoCItems + videoDItems).sortedByDescending { it.dateTaken }
            }
            // 隐藏加载指示器
            showLoadingIndicator(false)
            // withContext 会自动切回主线程，在这里安全地更新UI
            // showLoadingIndicator(false)
            allMediaItems.clear()
            allMediaItems.addAll(mediaResult)
            currentImageIndex = -1 // 重置索引

            if (allMediaItems.isNotEmpty()) {
                debugLog("Total media loaded: ${allMediaItems.size}")
                //loadNextMedia()
                loadSpecificMedia(0)
                buttonNext.visibility = View.VISIBLE
            } else {
                debugLog("No media found in specified directories")
                handleNoPhotosFound()
            }
            // 确保在加载完成后更新计数
            updatePhotoCountText()
        }
    }

    // queryMedia 只负责查询并返回结果列表
    private fun queryMedia(contentUri: Uri, folder: String, type: MediaType): List<MediaItem> {
        val projection: Array<String>
        val selection: String
        val selectionArgs: Array<String>

        // Android Q 及以上版本的路径查询逻辑
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATE_TAKEN
            )
            // 在 Android 10+，直接使用 RELATIVE_PATH 查询更高效、更标准
            selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            selectionArgs = arrayOf("$folder/") // 精确匹配，而不是使用 LIKE
        } else {
            projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DATA
            )
            selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
            selectionArgs = arrayOf("%/$folder/%") // 旧版本只能通过模糊匹配文件路径
        }

        // 统一的排序顺序
        val sortOrder = "${MediaStore.MediaColumns.DATE_TAKEN} DESC"

        return try {
            // 使用'use'块来自动管理Cursor的生命周期，并在结束后返回列表
            contentResolver.query(contentUri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val items = mutableListOf<MediaItem>()
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val dateTaken = cursor.getLong(dateTakenColumn)
                    val uri = ContentUris.withAppendedId(contentUri, id)
                    items.add(MediaItem(uri, type, dateTaken))
                }
                debugLog("Query found ${items.size} items of type ${type.name} in $folder")
                items // use块的最后一行作为其返回值
            } ?: emptyList() // 如果查询返回null，则直接返回一个空列表
        } catch (e: Exception) {
            Log.e(TAG, "Error loading ${type.name} from $folder. This might be a permission issue.", e)
            emptyList() // 如果发生异常，同样返回一个空列表，保证程序不会崩溃
        }
    }

    private fun loadNextMedia() {
        if (allMediaItems.isEmpty()) {
            handleNoPhotosFound()
            return
        }
        currentImageIndex++
        if (currentImageIndex >= allMediaItems.size) currentImageIndex = 0
        loadSpecificMedia(currentImageIndex)
    }

    private fun updatePhotoCountText() {
        val currentNumber = if (currentImageIndex >= 0) currentImageIndex + 1 else 0
        val totalNumber = allMediaItems.size
        photoCountTextView.text = getString(R.string.photo_count_format, currentNumber, totalNumber)
        photoCountTextView.visibility = View.VISIBLE
        buttonDelphoto.visibility = if (totalNumber > 0) View.VISIBLE else View.GONE
        buttonNext.visibility = if (totalNumber > 1) View.VISIBLE else View.GONE
    }

    private fun handleNoPhotosFound(isError: Boolean = false) {
        val message =
            if (isError) getString(R.string.toast_error_accessing_photos) else getString(R.string.toast_no_photos_found)
        showCenteredToast(message, Toast.LENGTH_LONG)
        allMediaItems.clear()
        currentImageIndex = -1
        //exoPlayer?.stop()
        latestVideoView.visibility = View.INVISIBLE
        latestImageView.visibility = View.VISIBLE
        val emojiBitmapToShow =
            emojiBitmap ?: createBitmapFromEmoji("🤷", 200).also { emojiBitmap = it }
        latestImageView.setImageBitmap(emojiBitmapToShow)
        updatePhotoCountText()
        buttonBackmain.visibility = View.VISIBLE
    }

    private fun showCenteredToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        centeredToast?.cancel()
        centeredToast = Toast.makeText(this, message, duration).apply {
            setGravity(android.view.Gravity.CENTER, 0, 120)
            show()
        }
    }

    private fun createBitmapFromEmoji(emojiString: String, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * 0.25f
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }
        val x = canvas.width / 2f
        val y = canvas.height / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(emojiString, x, y, paint)
        return bitmap
    }

}