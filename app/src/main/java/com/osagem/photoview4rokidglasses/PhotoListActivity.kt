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
import android.graphics.Canvas  // 确保导入这个
import android.graphics.Color// 确保导入这个
import android.graphics.Paint   // 确保导入这个
import android.graphics.Typeface // 确保导入这个

class PhotoListActivity : AppCompatActivity() {

    companion object {
        private const val DEBUG = false //false or true 调试开关：上线时改为 false 即可关闭所有调试日志

        private const val TAG = "PhotoManager"

        private fun debugLog(message: String) {
            if (DEBUG) Log.d(TAG, message)
        }
    }

    private lateinit var latestImageView: ImageView
    private lateinit var buttonNext: MaterialButton
    private lateinit var buttonBackmain: MaterialButton
    private lateinit var buttonDelphoto: MaterialButton
    private lateinit var photoCountTextView: TextView

    private var allImageUris = mutableListOf<Uri>()
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
        buttonNext = findViewById(R.id.buttonNext)
        buttonBackmain = findViewById(R.id.buttonBackmain)
        buttonBackmain.visibility = View.VISIBLE
        buttonDelphoto = findViewById(R.id.buttonDelphoto)
        photoCountTextView = findViewById(R.id.photoCountTextView)

        deleteRequestLauncher =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    //handleDeletionSuccess()
                    // 用户已经授予权限，再次尝试删除
                    deleteCurrentImage()
                } else {
                    Toast.makeText(this, R.string.toast_photo_deletion_cancelled_failed, Toast.LENGTH_SHORT).show()
                }
            }

        checkAndRequestPermission()

        buttonNext.setOnClickListener { loadNextImage() }

        buttonBackmain.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        buttonDelphoto.setOnClickListener {
            if (allImageUris.isNotEmpty() && currentImageIndex in allImageUris.indices) {
                deleteCurrentImage()
            } else {
                Toast.makeText(this, R.string.toast_no_photo_selected_to_del, Toast.LENGTH_SHORT).show()
            }
        }

        updatePhotoCountText()
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
                    loadAllImageUrisFromCamera()
                } else {
                    Toast.makeText(this, R.string.toast_read_permission_denied, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }.launch(permissionsToRequest.toTypedArray())
        } else {
            loadAllImageUrisFromCamera()
        }
    }

    private fun deleteCurrentImage() {
        val uriToDelete = allImageUris[currentImageIndex]
        try {
            val rowsDeleted = contentResolver.delete(uriToDelete, null, null)
            if (rowsDeleted > 0) {
                handleDeletionSuccess(uriToDelete)
            } else {
                Toast.makeText(this, R.string.toast_failed_to_delete_photo, Toast.LENGTH_SHORT).show()
            }
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val recoverable = e as? RecoverableSecurityException
                if (recoverable != null) {
                    val intentSender: IntentSender = recoverable.userAction.actionIntent.intentSender
                    deleteRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                } else {
                    Toast.makeText(this, R.string.toast_deletion_failed_security_reasons, Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, R.string.toast_write_permission_granted, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleDeletionSuccess(deletedUri: Uri? = null) {
        deletedUri?.let { debugLog("Deleted → $it") }

        Toast.makeText(this, R.string.toast_photo_deleted_succe, Toast.LENGTH_SHORT).show()
        allImageUris.removeAt(currentImageIndex)
        if (allImageUris.isEmpty()) {
            handleNoPhotosFound()
        } else {
            if (currentImageIndex >= allImageUris.size) {
                currentImageIndex = allImageUris.size - 1
            }
            loadSpecificImage(currentImageIndex)
        }
        updatePhotoCountText()
    }

    private fun loadAllImageUrisFromCamera() {
        allImageUris.clear()
        currentImageIndex = -1

        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN)

        val (selection, selectionArgs) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cameraDir = Environment.DIRECTORY_DCIM + File.separator + "Camera"
                MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?" to arrayOf("%$cameraDir/%")
            } else {
                val cameraDir = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}${File.separator}Camera"
                MediaStore.Images.Media.DATA + " LIKE ?" to arrayOf("$cameraDir/%")
            }

        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    allImageUris.add(uri)
                    debugLog("Loaded → $uri")
                }
            }
            if (allImageUris.isNotEmpty()) {
                debugLog("Total photos loaded: ${allImageUris.size}")
                loadNextImage()
                buttonNext.visibility = View.VISIBLE
            } else {
                debugLog("No photos found in Camera directory")
                handleNoPhotosFound()
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.toast_error_loading_images, e.localizedMessage), Toast.LENGTH_LONG).show()
            Log.e(TAG, "Error loading images", e)
            handleNoPhotosFound(true)
        }
    }

    private fun loadNextImage() {
        if (allImageUris.isEmpty()) {
            handleNoPhotosFound()
            return
        }
        currentImageIndex++
        if (currentImageIndex >= allImageUris.size) currentImageIndex = 0
        loadSpecificImage(currentImageIndex)
    }

    private fun loadSpecificImage(index: Int) {
        if (index !in allImageUris.indices) {
            handleNoPhotosFound()
            return
        }
        currentImageIndex = index
        val uri = allImageUris[index]
        debugLog("Displaying → $uri")

        Glide.with(this)
            .load(uri)
            .placeholder(R.drawable.ic_launcher_background)
            .error(android.R.drawable.stat_notify_error)
            .into(latestImageView)

        updatePhotoCountText()
        buttonNext.visibility = if (allImageUris.size > 1) View.VISIBLE else View.GONE
        buttonDelphoto.visibility = View.VISIBLE
    }

    private fun updatePhotoCountText() {
        val currentNumber = if (currentImageIndex >= 0) currentImageIndex + 1 else 0
        val totalNumber = allImageUris.size
        photoCountTextView.text = getString(R.string.photo_count_format, currentNumber, totalNumber)
        photoCountTextView.visibility = View.VISIBLE
        buttonDelphoto.visibility = if (totalNumber > 0) View.VISIBLE else View.GONE
        buttonNext.visibility = if (totalNumber > 1) View.VISIBLE else View.GONE
    }

    private fun handleNoPhotosFound(isError: Boolean = false) {
        Toast.makeText(
            this,
            if (isError) getString(R.string.toast_error_accessing_photos) else getString(R.string.toast_no_photos_found),
            Toast.LENGTH_LONG
        ).show()
        allImageUris.clear()
        currentImageIndex = -1
        //latestImageView.setImageDrawable(ContextCompat.getDrawable(this, android.R.drawable.ic_menu_gallery))
        // 新的代码: 使用 Emoji 🤷
        val emojiBitmap = createBitmapFromEmoji("🤷", 200) // 200是Emoji的大小，可以调整
        latestImageView.setImageBitmap(emojiBitmap)
        // --- 修改结束 ---
        updatePhotoCountText()
        buttonBackmain.visibility = View.VISIBLE
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
