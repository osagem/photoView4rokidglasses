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
    private var exoPlayer: ExoPlayer? = null
    private lateinit var buttonBackmain: MaterialButton
    private lateinit var buttonDelphoto: MaterialButton
    private lateinit var buttonNext: MaterialButton
    private lateinit var photoCountTextView: TextView

    private var allMediaItems = mutableListOf<MediaItem>()
    private var currentImageIndex = -1

    private var centeredToast: Toast? = null
    private var emojiBitmap: Bitmap? = null

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
        latestVideoView = findViewById(R.id.playerView)
        buttonNext = findViewById(R.id.buttonNext)
        buttonBackmain = findViewById(R.id.buttonBackmain)
        buttonBackmain.visibility = View.VISIBLE
        buttonDelphoto = findViewById(R.id.buttonDelphoto)
        photoCountTextView = findViewById(R.id.photoCountTextView)
        initializePlayer()

        deleteRequestLauncher =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    // 用户已经授予权限，再次尝试删除，此为确实删除的步骤
                    deleteCurrentImage()
                } else {
                    showCenteredToast(getString(R.string.toast_photo_deletion_cancelled_failed))
                }
            }

        buttonNext.setOnClickListener { loadNextMedia() }

        buttonBackmain.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        buttonDelphoto.setOnClickListener {
            if (allMediaItems.isNotEmpty() && currentImageIndex in allMediaItems.indices) {
                deleteCurrentImage()
            } else {
                showCenteredToast(getString(R.string.toast_no_photo_selected_to_del))
            }
        }
        checkAndRequestPermission()
        updatePhotoCountText()
        emojiBitmap = createBitmapFromEmoji("🤷", 200) // 只创建一次
    }

    private fun initializePlayer() {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(this).build()
            latestVideoView.player = exoPlayer
            exoPlayer?.repeatMode = ExoPlayer.REPEAT_MODE_ONE // 设置为循环播放模式
        }
    }

    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
        latestVideoView.player = null
    }

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
        if (latestVideoView.visibility == View.VISIBLE) {
            exoPlayer?.play()
        }
    }

    override fun onPause() {
        super.onPause()
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
                    val intentSender: IntentSender = recoverable.userAction.actionIntent.intentSender
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
        queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_DCIM + File.separator + "Camera", MediaType.IMAGE)
        queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES + File.separator + "Camera", MediaType.VIDEO)
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
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        if (item.type == MediaType.VIDEO) {
            latestVideoView.visibility = View.VISIBLE
            latestImageView.visibility = View.GONE
            val mediaItem = ExoMediaItem.fromUri(item.uri)
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            exoPlayer?.play()
            debugLog("Playing video.")
        } else {
            latestImageView.visibility = View.VISIBLE
            latestVideoView.visibility = View.GONE
            Glide.with(this)
                .load(item.uri)
                .into(latestImageView)
            debugLog("Displaying image.")
        }

        updatePhotoCountText()
        buttonNext.visibility = if (allMediaItems.size > 1) View.VISIBLE else View.GONE
        buttonDelphoto.visibility = View.VISIBLE
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
        val message = if (isError) getString(R.string.toast_error_accessing_photos) else getString(R.string.toast_no_photos_found)
        showCenteredToast(message, Toast.LENGTH_LONG)
        allMediaItems.clear()
        currentImageIndex = -1
        latestVideoView.visibility = View.GONE
        latestImageView.visibility = View.VISIBLE
        val emojiBitmapToShow = emojiBitmap ?: createBitmapFromEmoji("🤷", 200).also { emojiBitmap = it }
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

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
        centeredToast?.cancel()
        emojiBitmap?.recycle()
        emojiBitmap = null
    }
}