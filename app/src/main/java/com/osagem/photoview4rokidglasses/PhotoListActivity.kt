package com.osagem.photoview4rokidglasses

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.File


class PhotoListActivity : AppCompatActivity() {

    private lateinit var latestImageView: ImageView
    private val cameraDirectoryPath = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        "Camera"
    )

    // ActivityResultLauncher for permission request
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                loadLatestImage()
            } else {
                Toast.makeText(this, "Permission denied to read external storage", Toast.LENGTH_SHORT).show()
                // Handle the case where permission is denied, e.g., show a message or disable functionality
            }
        }


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

        checkAndRequestPermission()
    }
    private fun checkAndRequestPermission() {
        val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13 (API 33)
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            ContextCompat.checkSelfPermission(
                this,
                permissionToRequest
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission is already granted
                loadLatestImage()
            }
            shouldShowRequestPermissionRationale(permissionToRequest) -> {
                // Explain to the user why you need the permission
                Toast.makeText(this, "Media images permission is needed to show photos.", Toast.LENGTH_LONG).show()
                requestPermissionLauncher.launch(permissionToRequest)
            }
            else -> {
                // Directly request the permission
                requestPermissionLauncher.launch(permissionToRequest)
            }
        }
    }
    private fun loadLatestImage() {
        val permissionToCheck = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permissionToCheck) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Error: loadLatestImage called without required permission.", Toast.LENGTH_LONG).show()
            return
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN
        )

        // MediaStore 查询的 selection 和 selectionArgs 已经正确处理了Q版本及以上的情况
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?"
        } else {
            // 对于 API 28 (minSdk) 到 API 29 (不包括Q), DATA 字段仍然适用
            // 但请注意，即使有 READ_EXTERNAL_STORAGE，在某些设备或情况下，
            // 依赖绝对路径可能不如 MediaStore URI 稳定。
            // 不过，您这里是用于查询，而不是直接文件操作，问题不大。
            MediaStore.Images.Media.DATA + " LIKE ?"
        }

        val cameraDirName = Environment.DIRECTORY_DCIM + File.separator + "Camera" // e.g. "DCIM/Camera"

        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 对于 API 29+ (Q), RELATIVE_PATH 通常包含类似 "DCIM/Camera/" 的路径
            // 注意末尾的斜杠，确保能匹配到目录下的文件
            arrayOf("%$cameraDirName/%")
        } else {
            // 对于 API 28, 使用绝对路径
            arrayOf("${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}${File.separator}Camera/%")
        }


        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"

        try {
            applicationContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                if (cursor.count == 0) { // 检查游标是否有行
                    Toast.makeText(
                        this,
                        "No photos found in Camera directory via MediaStore.",
                        Toast.LENGTH_SHORT
                    ).show()
                    latestImageView.setImageResource(android.R.color.transparent) // 或者一个占位图
                    return@use
                }
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val id = cursor.getLong(idColumn)
                    val contentUri: Uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    latestImageView.setImageURI(contentUri)
                } else {
                    Toast.makeText(
                        this,
                        "No photos found in Camera directory.", // 此处可以更具体
                        Toast.LENGTH_SHORT
                    ).show()
                    latestImageView.setImageResource(android.R.color.transparent) // 清空或设置占位符
                }
            } ?: run {
                Toast.makeText(this, "Could not query MediaStore (query returned null)", Toast.LENGTH_SHORT).show()
                latestImageView.setImageResource(android.R.color.transparent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error loading image: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            e.printStackTrace() // For debugging
            latestImageView.setImageResource(android.R.color.transparent)
        }
    }
}