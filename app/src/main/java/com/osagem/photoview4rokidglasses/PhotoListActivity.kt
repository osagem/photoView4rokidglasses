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
import android.view.View
import android.widget.ImageView
import android.widget.TextView // 导入 TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.io.File
import android.content.Intent // 需要导入 Intent 以便可以返回 MainActivity
import com.bumptech.glide.Glide // Import Glide

class PhotoListActivity : AppCompatActivity() {

    private lateinit var latestImageView: ImageView
    private lateinit var buttonNext: MaterialButton
    private lateinit var buttonBackmain: MaterialButton // Declare buttonBackmain
    private lateinit var photoCountTextView: TextView // 新增 TextView 引用
    private var allImageUris = listOf<Uri>()
    private var currentImageIndex = -1

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                loadAllImageUrisFromCamera()
            } else {
                Toast.makeText(this, "Permission denied to read external storage", Toast.LENGTH_SHORT).show()
                finish() // Close activity if permission is denied
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
        buttonNext = findViewById(R.id.buttonNext)
        buttonBackmain = findViewById(R.id.buttonBackmain) // Initialize buttonBackmain
        photoCountTextView = findViewById(R.id.photoCountTextView) // 初始化 TextView

        checkAndRequestPermission()
        buttonNext.setOnClickListener {
            loadNextImage()
        }

        // Set OnClickListener for buttonBackmain
        buttonBackmain.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            // Optional: Add flags if you want to clear the back stack in a specific way
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish() // Finish PhotoListActivity so it's removed from the back stack
        }
        buttonBackmain.visibility = View.VISIBLE // Make the button visible

        updatePhotoCountText() // 初始时可能没有图片，先调用一次
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
                loadAllImageUrisFromCamera()
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
    private fun loadAllImageUrisFromCamera() {
        val permissionToCheck = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permissionToCheck) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Error: Permissions not granted.", Toast.LENGTH_LONG).show()
            finish() // Return to previous view if permissions not granted
            return
        }

        val tempImageUris = mutableListOf<Uri>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN
        )

        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?"
        } else {
            MediaStore.Images.Media.DATA + " LIKE ?"
        }

        val cameraDirName = Environment.DIRECTORY_DCIM + File.separator + "Camera"
        val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf("%$cameraDirName/%")
        } else {
            arrayOf("${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)}${File.separator}Camera/%")
        }

        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC" // Get latest first

        try {
            applicationContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                if (cursor.count == 0) {
                    Toast.makeText(this, "No photos found in Camera directory.", Toast.LENGTH_LONG).show()
                    buttonBackmain.visibility = View.VISIBLE // Ensure back button is visible even if no photos
                    finish() // Optionally finish if no images
                    return@use
                }

                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val contentUri: Uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    tempImageUris.add(contentUri)
                }
                allImageUris = tempImageUris
                if (allImageUris.isNotEmpty()) {
                    currentImageIndex = -1 // Start before the first image
                    loadNextImage() // Load the first image
                    buttonNext.visibility = View.VISIBLE
                    buttonBackmain.visibility = View.VISIBLE // Also ensure it's visible here
                } else {
                    Toast.makeText(this, "No photos found after processing.", Toast.LENGTH_LONG).show()
                    buttonBackmain.visibility = View.VISIBLE // Ensure back button is visible
                    finish() // Return to previous view if no photos after processing
                }
            } ?: run {
                Toast.makeText(this, "Could not query MediaStore.", Toast.LENGTH_LONG).show()
                buttonBackmain.visibility = View.VISIBLE // Ensure back button is visible
                finish() // Return to previous view if MediaStore query fails
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error loading images: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            e.printStackTrace()
            buttonBackmain.visibility = View.VISIBLE // Ensure back button is visible
            finish()
        }
    }

    private fun loadNextImage() {
        if (allImageUris.isEmpty()) {
            Toast.makeText(this, "No images to display.", Toast.LENGTH_SHORT).show()
            if (!isFinishing && !isDestroyed) { // Check if activity is still active
                buttonBackmain.visibility = View.VISIBLE // Ensure back button is visible
                // Consider not finishing here automatically if you want the user to explicitly go back
                //finish()
            }
            return
        }

        currentImageIndex++

        if (currentImageIndex >= allImageUris.size) {
            currentImageIndex = 0 // Loop back to the first image
            Toast.makeText(this, "Reached end, starting over.", Toast.LENGTH_SHORT).show()
        }

        val imageUriToLoad = allImageUris[currentImageIndex]

        Glide.with(this)
            .load(imageUriToLoad)
            .placeholder(R.drawable.ic_launcher_background) // Optional: add a placeholder
            .error(android.R.drawable.stat_notify_error) // Optional: add an error image
            .into(latestImageView)
        updatePhotoCountText() // 加载新图片后更新计数
        photoCountTextView.visibility = View.VISIBLE // 确保计数可见
    }
    // 新增方法来更新 TextView
    private fun updatePhotoCountText() {
        if (allImageUris.isNotEmpty()) {
            val currentNumber = currentImageIndex + 1 // 用户看到的序号从1开始
            val totalNumber = allImageUris.size
            photoCountTextView.text = getString(R.string.photo_count_format, currentNumber, totalNumber)
            photoCountTextView.visibility = View.VISIBLE
        } else {
            photoCountTextView.text = "" // 或者可以显示 "0 / 0" 或特定提示
            //photoCountTextView.visibility = View.GONE // 如果没有图片，则隐藏计数
        }
    }
}