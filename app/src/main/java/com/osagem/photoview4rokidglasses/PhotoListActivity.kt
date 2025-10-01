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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import java.io.File
import androidx.media3.common.MediaItem as ExoMediaItem

class PhotoListActivity : AppCompatActivity() {

    // 数据类和枚举
    data class MediaItem(val uri: Uri, val type: MediaType, val dateTaken: Long)
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
    private lateinit var deleteRequestLauncher: ActivityResultLauncher<IntentSenderRequest>

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
        }
    }

    private fun releasePlayer() {
        // 这个方法现在只在 onDestroy 中被调用。
        // 在释放播放器本身之前，先从视图解绑。
        latestVideoView.player = null
        exoPlayer?.release()
        exoPlayer = null
    }

    // ------------------- 媒体加载与切换 (逻辑不变) -------------------
    private fun loadSpecificMedia(index: Int) {
        if (index !in allMediaItems.indices) {
            handleNoPhotosFound()
            return
        }
        currentImageIndex = index
        val item = allMediaItems[index]
        debugLog("Displaying ${item.type.name} → ${item.uri}")

        // 停止并清空旧的媒体项
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()

        // 关键：在切换逻辑开始前，立即解绑播放器，触发Surface的释放流程。
        // 无论接下来是播图片还是视频，这一步都是安全的。
        latestVideoView.player = null

        if (item.type == MediaType.VIDEO) {
            latestVideoView.visibility = View.VISIBLE
            latestImageView.visibility = View.INVISIBLE

//            // 使用 Handler 在UI线程上延迟执行加载
//            // 这给了 ExoPlayer 足够的时间来完全释放前一个视频的资源（特别是 Surface）
//            // 从而避免了新旧视频争抢 Surface 导致的 "detachBuffer" 错误
//            // 50毫秒是一个经验值，通常足以应对大多数情况。
//            latestVideoView.postDelayed({
//                // 确保在这期间 Activity 没有被销毁
//                if (exoPlayer != null) {
//                    latestVideoView.player = exoPlayer
//                    val mediaItem = ExoMediaItem.fromUri(item.uri)
//                    exoPlayer?.setMediaItem(mediaItem)
//                    exoPlayer?.prepare()
//                    exoPlayer?.play()
//                    debugLog("Playing video (after delay).")
//                }
//            }, 50) // 延迟xxx毫秒

            // 使用 post 而不是 postDelayed。
            // 这会将操作推迟到下一个UI绘制周期，通常足以让底层的Surface完成解绑和重新准备。
            // 这是比固定延迟更健壮的方式。
            latestVideoView.post {
                // 再次检查Activity是否还存活，避免在已销毁的Activity上操作
                if (!isDestroyed && exoPlayer != null) {
                    // 重新将播放器实例绑定到PlayerView
                    latestVideoView.player = exoPlayer
                    val mediaItem = ExoMediaItem.fromUri(item.uri)
                    exoPlayer?.setMediaItem(mediaItem)
                    exoPlayer?.prepare()
                    exoPlayer?.play()
                    debugLog("Playing video.")
                }
            }

        } else {
            latestImageView.visibility = View.VISIBLE
            latestVideoView.visibility = View.INVISIBLE
            Glide.with(this)
                .load(item.uri)
                .into(latestImageView)
            debugLog("Displaying image.")
        }
        updatePhotoCountText()
    }

    // ------------------- 其他辅助方法 -------------------
    private fun initializeViews() {
        latestImageView = findViewById(R.id.latestImageView)
        latestVideoView = findViewById(R.id.playerView)
        buttonNext = findViewById(R.id.buttonNext)
        buttonBackmain = findViewById(R.id.buttonBackmain)
        buttonBackmain.visibility = View.VISIBLE
        buttonDelphoto = findViewById(R.id.buttonDelphoto)
        photoCountTextView = findViewById(R.id.photoCountTextView)
    }

    private fun setupListeners() {
        // 1. 设置删除操作的结果回调
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

        // 2. 为“下一张”按钮设置点击事件
        buttonNext.setOnClickListener { loadNextMedia() }

        // 3. 为“返回主页”按钮设置点击事件
        buttonBackmain.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        // 4. 为“删除”按钮设置点击事件
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
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                if (permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true) {
                    loadAllMediaUris()
                } else {
                    showCenteredToast(getString(R.string.toast_read_permission_denied))
                    finish()
                }
            }.launch(permissionsToRequest.toTypedArray())
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
        allMediaItems.clear()
        currentImageIndex = -1
        queryMedia(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            Environment.DIRECTORY_DCIM + File.separator + "Camera",
            MediaType.IMAGE
        )
        queryMedia(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            Environment.DIRECTORY_MOVIES + File.separator + "Camera",
            MediaType.VIDEO
        )
        allMediaItems.sortByDescending { it.dateTaken }
        if (allMediaItems.isNotEmpty()) {
            debugLog("Total media loaded: ${allMediaItems.size}")
            loadNextMedia()
            buttonNext.visibility = View.VISIBLE
        } else {
            debugLog("No media found in specified directories")
            handleNoPhotosFound()
        }
    }

    private fun queryMedia(contentUri: Uri, folder: String, type: MediaType) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATE_TAKEN,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        )

        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        } else {
            "${MediaStore.MediaColumns.DATA} LIKE ?"
        }
        val selectionArgs = arrayOf("%$folder/%")

        try {
            contentResolver.query(contentUri, projection, selection, selectionArgs, null)
                ?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val dateTakenColumn =
                        cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idColumn)
                        val dateTaken = cursor.getLong(dateTakenColumn)
                        val uri = ContentUris.withAppendedId(contentUri, id)
                        allMediaItems.add(MediaItem(uri, type, dateTaken))
                        debugLog("Loaded ${type.name} → $uri")
                    }
                }
        } catch (e: Exception) {
            showCenteredToast(
                getString(R.string.toast_error_loading_images, e.localizedMessage),
                Toast.LENGTH_LONG
            )
            Log.e(TAG, "Error loading ${type.name}", e)
            handleNoPhotosFound(true)
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