package com.osagem.photoview4rokidglasses

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import java.io.File
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
// 引入 ExoPlayer 相关类
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class PhotoListActivity : AppCompatActivity() {

    // 数据类，用于封装媒体项（图片或视频）
    data class MediaItem(val uri: Uri, val type: MediaType, val dateTaken: Long)
    enum class MediaType { IMAGE, VIDEO }
    companion object {
        private const val DEBUG = true //false or true 调试开关：上线时改为 false 即可关闭所有调试日志

        private const val TAG = "PhotoManager"

        private fun debugLog(message: String) {
            if (DEBUG) Log.d(TAG, message)
        }
    }

    private lateinit var latestImageView: ImageView
    private lateinit var latestVideoView: PlayerView
    private var exoPlayer: ExoPlayer? = null // 新增 ExoPlayer 实例
    private lateinit var buttonBackmain: MaterialButton
    private lateinit var buttonDelphoto: MaterialButton
    private lateinit var buttonNext: MaterialButton
    private lateinit var photoCountTextView: TextView

    //private var allImageUris = mutableListOf<Uri>()
    private var allMediaItems = mutableListOf<MediaItem>() // 修改为存储 MediaItem
    private var currentImageIndex = -1

    private lateinit var deleteRequestLauncher: ActivityResultLauncher<IntentSenderRequest>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_photo_list)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        latestImageView = findViewById(R.id.latestImageView)
        latestVideoView = findViewById(R.id.playerView) // 初始化 PlayerView
        buttonNext = findViewById(R.id.buttonNext)
        buttonBackmain = findViewById(R.id.buttonBackmain)
        buttonBackmain.visibility = View.VISIBLE
        buttonDelphoto = findViewById(R.id.buttonDelphoto)
        photoCountTextView = findViewById(R.id.photoCountTextView)
        initializePlayer()

        deleteRequestLauncher =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    //handleDeletionSuccess()
                    // 用户已经授予权限，再次尝试删除
                    deleteCurrentImage()
                } else {
                    //Toast.makeText(this, R.string.toast_photo_deletion_cancelled_failed, Toast.LENGTH_SHORT).show()
                    showCenteredToast(getString(R.string.toast_photo_deletion_cancelled_failed))
                }
            }

        //buttonNext.setOnClickListener { loadNextImage() }
        buttonNext.setOnClickListener { loadNextMedia() }

        buttonBackmain.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        buttonDelphoto.setOnClickListener {
            //if (allImageUris.isNotEmpty() && currentImageIndex in allImageUris.indices) {
            if (allMediaItems.isNotEmpty() && currentImageIndex in allMediaItems.indices) {
                deleteCurrentImage()
            } else {
                //Toast.makeText(this, R.string.toast_no_photo_selected_to_del, Toast.LENGTH_SHORT).show()
                showCenteredToast(getString(R.string.toast_no_photo_selected_to_del))
            }
        }
        checkAndRequestPermission()
        updatePhotoCountText()
    }

    // 5. 添加 ExoPlayer 的生命周期管理方法
    private fun initializePlayer() {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(this).build()
            latestVideoView.player = exoPlayer
            // 设置为循环播放模式
            exoPlayer?.repeatMode = ExoPlayer.REPEAT_MODE_ONE
        }
    }

    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
        latestVideoView.player = null
    }

    // 6. 在 Activity 生命周期中调用这些方法
    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT > 23) {
            initializePlayer()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT <= 23 || exoPlayer == null) {
            initializePlayer()
        }
        // 如果 PlayerView 可见，则恢复播放
        if (latestVideoView.visibility == View.VISIBLE) {
            exoPlayer?.play()
        }
    }

    override fun onPause() {
        super.onPause()
        // 暂停播放器
        exoPlayer?.pause()
        if (Build.VERSION.SDK_INT <= 23) {
            releasePlayer()
        }
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT > 23) {
            releasePlayer()
        }
    }


    private fun checkAndRequestPermission() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        if (permissionsToRequest.isNotEmpty()) {
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                if (permissions[Manifest.permission.READ_EXTERNAL_STORAGE] == true) {
                    //loadAllImageUrisFromCamera()
                    loadAllMediaUris()
                } else {
                    //Toast.makeText(this, R.string.toast_read_permission_denied, Toast.LENGTH_SHORT).show()
                    showCenteredToast(getString(R.string.toast_read_permission_denied))
                    finish()
                }
            }.launch(permissionsToRequest.toTypedArray())
        } else {
            //loadAllImageUrisFromCamera()
            loadAllMediaUris()
        }
    }

    private fun deleteCurrentImage() {
        //val uriToDelete = allImageUris[currentImageIndex]
        val uriToDelete = allMediaItems[currentImageIndex].uri
        try {
            val rowsDeleted = contentResolver.delete(uriToDelete, null, null)
            if (rowsDeleted > 0) {
                handleDeletionSuccess(uriToDelete)
            } else {
                //Toast.makeText(this, R.string.toast_failed_to_delete_photo, Toast.LENGTH_SHORT).show()
                showCenteredToast(getString(R.string.toast_failed_to_delete_photo))
            }
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val recoverable = e as? RecoverableSecurityException
                if (recoverable != null) {
                    val intentSender: IntentSender = recoverable.userAction.actionIntent.intentSender
                    deleteRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                } else {
                    //Toast.makeText(this, R.string.toast_deletion_failed_security_reasons, Toast.LENGTH_SHORT).show()
                    showCenteredToast(getString(R.string.toast_deletion_failed_security_reasons))
                }
            } else {
                //Toast.makeText(this, R.string.toast_write_permission_granted, Toast.LENGTH_SHORT).show()
                showCenteredToast(getString(R.string.toast_write_permission_granted))
            }
        }
    }

    private fun handleDeletionSuccess(deletedUri: Uri? = null) {
        deletedUri?.let { debugLog("Deleted → $it") }

        //Toast.makeText(this, R.string.toast_photo_deleted_succe, Toast.LENGTH_SHORT).show()
        showCenteredToast(getString(R.string.toast_photo_deleted_succe))

        // 8. 停止 ExoPlayer 而不是 VideoView
        exoPlayer?.stop()
        allMediaItems.removeAt(currentImageIndex)
        //allImageUris.removeAt(currentImageIndex)
        //if (allImageUris.isEmpty()) {
        //    handleNoPhotosFound()
        //} else {
        //    if (currentImageIndex >= allImageUris.size) {
        //        currentImageIndex = allImageUris.size - 1
        //    }
        //    loadSpecificImage(currentImageIndex)
        //}

        // --- 新增逻辑开始 ---
        if (allMediaItems.isEmpty()) {
            // 如果所有媒体都被删除了
            handleNoPhotosFound()
        } else {
            // 如果删除后列表里还有媒体
            // 确保索引不会越界
            if (currentImageIndex >= allMediaItems.size) {
                currentImageIndex = allMediaItems.size - 1
            }
            // 重新加载当前索引位置的新媒体
            loadSpecificMedia(currentImageIndex)
        }
        // --- 新增逻辑结束 ---

        //updatePhotoCountText()
    }

    private fun loadAllMediaUris() {
        allMediaItems.clear()
        currentImageIndex = -1

        // 查询图片
        queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_DCIM + File.separator + "Camera", MediaType.IMAGE)
        // 查询视频
        queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES + File.separator + "Camera", MediaType.VIDEO)

        // 按拍摄日期降序排序
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
            contentResolver.query(contentUri, projection, selection, selectionArgs, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val dateTaken = cursor.getLong(dateTakenColumn)
                    val uri = ContentUris.withAppendedId(contentUri, id)
                    allMediaItems.add(MediaItem(uri, type, dateTaken))
                    debugLog("Loaded ${type.name} → $uri")
                }
            }
        } catch (e: Exception) {
            showCenteredToast(getString(R.string.toast_error_loading_images, e.localizedMessage), Toast.LENGTH_LONG)
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

    private fun loadSpecificMedia(index: Int) {
        if (index !in allMediaItems.indices) {
            handleNoPhotosFound()
            return
        }
        currentImageIndex = index
        val item = allMediaItems[index]
        debugLog("Displaying ${item.type.name} → ${item.uri}")

        // 停止任何正在播放的视频
        // 9. 停止当前播放（图片或视频切换时）
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()

        if (item.type == MediaType.VIDEO) {
            // 如果是视频
            latestVideoView.visibility = View.VISIBLE // 1. 显示视频播放器
            latestImageView.visibility = View.GONE     // 2. 隐藏图片视图

            val mediaItem = ExoMediaItem.fromUri(item.uri)
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            exoPlayer?.play() // 自动播放
            debugLog("Playing video.")
        } else {
            // 如果是图片
            latestImageView.visibility = View.VISIBLE // 1. 显示图片视图
            latestVideoView.visibility = View.GONE     // 2. 隐藏视频播放器

            Glide.with(this)
                .load(item.uri)
                .into(latestImageView)
            debugLog("Displaying image.")
        }

        updatePhotoCountText()
        buttonNext.visibility = if (allMediaItems.size > 1) View.VISIBLE else View.GONE
        buttonDelphoto.visibility = View.VISIBLE
    }



//    private fun loadAllImageUrisFromCamera() {
//        allImageUris.clear()
//        currentImageIndex = -1
//
//        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN)
//
//        val (selection, selectionArgs) =
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
//                val cameraDir = Environment.DIRECTORY_DCIM + File.separator + "Camera"
//                MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?" to arrayOf("%$cameraDir/%")
//            } else {
//                val cameraDir = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}${File.separator}Camera"
//                MediaStore.Images.Media.DATA + " LIKE ?" to arrayOf("$cameraDir/%")
//            }
//
//        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
//
//        try {
//            contentResolver.query(
//                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
//                projection,
//                selection,
//                selectionArgs,
//                sortOrder
//            )?.use { cursor ->
//                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
//                while (cursor.moveToNext()) {
//                    val id = cursor.getLong(idColumn)
//                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
//                    allImageUris.add(uri)
//                    debugLog("Loaded → $uri")
//                }
//            }
//            if (allImageUris.isNotEmpty()) {
//                debugLog("Total photos loaded: ${allImageUris.size}")
//                loadNextImage()
//                buttonNext.visibility = View.VISIBLE
//            } else {
//                debugLog("No photos found in Camera directory")
//                handleNoPhotosFound()
//            }
//        } catch (e: Exception) {
//            //Toast.makeText(this, getString(R.string.toast_error_loading_images, e.localizedMessage), Toast.LENGTH_LONG).show()
//            showCenteredToast(getString(R.string.toast_error_loading_images, e.localizedMessage), Toast.LENGTH_LONG)
//            Log.e(TAG, "Error loading images", e)
//            handleNoPhotosFound(true)
//        }
//    }

//    private fun loadNextImage() {
//        if (allImageUris.isEmpty()) {
//            handleNoPhotosFound()
//            return
//        }
//        currentImageIndex++
//        if (currentImageIndex >= allImageUris.size) currentImageIndex = 0
//        loadSpecificImage(currentImageIndex)
//    }

//    private fun loadSpecificImage(index: Int) {
//        if (index !in allImageUris.indices) {
//            handleNoPhotosFound()
//            return
//        }
//        currentImageIndex = index
//        val uri = allImageUris[index]
//        debugLog("Displaying → $uri")
//
//        Glide.with(this)
//            .load(uri)
//            .placeholder(R.drawable.ic_launcher_background)
//            .error(android.R.drawable.stat_notify_error)
//            .into(latestImageView)
//
//        updatePhotoCountText()
//        buttonNext.visibility = if (allImageUris.size > 1) View.VISIBLE else View.GONE
//        buttonDelphoto.visibility = View.VISIBLE
//    }

    private fun updatePhotoCountText() {
        val currentNumber = if (currentImageIndex >= 0) currentImageIndex + 1 else 0
        //val totalNumber = allImageUris.size
        val totalNumber = allMediaItems.size
        photoCountTextView.text = getString(R.string.photo_count_format, currentNumber, totalNumber)
        photoCountTextView.visibility = View.VISIBLE
        buttonDelphoto.visibility = if (totalNumber > 0) View.VISIBLE else View.GONE
        buttonNext.visibility = if (totalNumber > 1) View.VISIBLE else View.GONE
    }

    private fun handleNoPhotosFound(isError: Boolean = false) {
        val message = if (isError) getString(R.string.toast_error_accessing_photos) else getString(R.string.toast_no_photos_found)
        showCenteredToast(message, Toast.LENGTH_LONG)
        //allImageUris.clear()
        allMediaItems.clear()
        currentImageIndex = -1
        // 11. 确保在没有媒体时隐藏 PlayerView
        latestVideoView.visibility = View.GONE
        //latestVideoView.visibility = View.GONE
        latestImageView.visibility = View.VISIBLE
        //latestImageView.setImageDrawable(ContextCompat.getDrawable(this, android.R.drawable.ic_menu_gallery))
        // 新的代码: 使用 Emoji 🤷
        val emojiBitmap = createBitmapFromEmoji("🤷", 200) // 200是Emoji的大小，可以调整
        latestImageView.setImageBitmap(emojiBitmap)
        // --- 修改结束 ---
        updatePhotoCountText()
        buttonBackmain.visibility = View.VISIBLE
    }

    /**
     * 创建并显示一个在屏幕中间的 Toast。
     * @param message 要显示的消息文本。
     * @param duration Toast 显示的时长 (Toast.LENGTH_SHORT 或 Toast.LENGTH_LONG)。
     */
    private fun showCenteredToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        val toast = Toast.makeText(this, message, duration)
        toast.setGravity(android.view.Gravity.CENTER, 0, 0)
        toast.show()
    }

    /**
     * 创建一个包含指定 Emoji 的 Bitmap。
     * @param emojiString 要显示的 Emoji 字符。
     * @param size 生成的 Bitmap 的边长（像素）。
     * @return 包含 Emoji 的正方形 Bitmap。
     */
    private fun createBitmapFromEmoji(emojiString: String, size: Int): Bitmap {
        // 使用 android.graphics.Bitmap
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        // 使用 android.graphics.Canvas
        val canvas = Canvas(bitmap)
        // 使用 android.graphics.Paint
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * 0.25f // Emoji 大小约为画布的 25%，留出边距
            color = Color.BLACK    // 使用 android.graphics.Color
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT // 使用 android.graphics.Typeface
        }

        // 计算绘制的中心点
        val x = canvas.width / 2f
        val y = canvas.height / 2f - (paint.descent() + paint.ascent()) / 2f

        // 使用 canvas.drawText(String, Float, Float, Paint)
        canvas.drawText(emojiString, x, y, paint)
        return bitmap
    }
}
