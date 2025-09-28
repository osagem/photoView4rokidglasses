package com.osagem.photoview4rokidglasses

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.Manifest
import android.app.RecoverableSecurityException // Needed for Android Q+ deletion
import android.content.ContentUris
import android.content.IntentSender // Needed for Android Q+ deletion
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log // For logging
import android.view.View
import android.widget.ImageView
import android.widget.TextView // 导入 TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.io.File
import android.content.Intent // 需要导入 Intent 以便可以返回 MainActivity
import androidx.activity.result.IntentSenderRequest
//import androidx.activity.result.launch
//import androidx.privacysandbox.tools.core.generator.build
import com.bumptech.glide.Glide // Import Glide
//import kotlin.collections.addAll
//import kotlin.text.clear
import androidx.activity.result.ActivityResultLauncher // For IntentSender launcher


class PhotoListActivity : AppCompatActivity() {

    private lateinit var latestImageView: ImageView
    private lateinit var buttonNext: MaterialButton
    private lateinit var buttonBackmain: MaterialButton // Declare buttonBackmain
    private lateinit var buttonDelphoto: MaterialButton // Declare buttonDelphoto
    private lateinit var photoCountTextView: TextView // 新增 TextView 引用
    //private var allImageUris = listOf<Uri>()
    private var allImageUris = mutableListOf<Uri>() // Use mutableListOf
    private var currentImageIndex = -1

    // Launcher for requesting user permission to delete a file (for Android Q+)
    private lateinit var deleteRequestLauncher: ActivityResultLauncher<IntentSenderRequest>

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                loadAllImageUrisFromCamera()
            } else {
                Toast.makeText(this, getString(R.string.toast_permission_Denied), Toast.LENGTH_SHORT).show()
                finish() // Close activity if permission is denied
            }
        }

    // Launcher for requesting write permission (needed for pre-Q deletion)
    private val requestWritePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // If write permission is granted, and we have a pending delete, try it again
                // You might need to store the URI of the photo to be deleted if you implement a retry logic
                Toast.makeText(this, getString(R.string.toast_write_permission_granted), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.toast_write_permission_denied), Toast.LENGTH_SHORT).show()
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
        buttonDelphoto = findViewById(R.id.buttonDelphoto) // Initialize buttonDelphoto
        photoCountTextView = findViewById(R.id.photoCountTextView) // 初始化 TextView

        // Initialize the delete request launcher
        deleteRequestLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { activityResult ->
            if (activityResult.resultCode == RESULT_OK) {
                Toast.makeText(this, getString(R.string.toast_photo_deleted_succe), Toast.LENGTH_SHORT).show()
                // Photo was successfully deleted by the user's explicit consent.
                // Now, remove it from our list and load the next one.
                if (currentImageIndex >= 0 && currentImageIndex < allImageUris.size) {
                    allImageUris.removeAt(currentImageIndex)
                    // Adjust currentImageIndex because an item was removed.
                    // If it was the last item, currentImageIndex might now be out of bounds or equal to size.
                    // If the list becomes empty, handle that case.
                    if (allImageUris.isEmpty()) {
                        handleNoPhotosFound()
                    } else {
                        // If currentImageIndex was pointing to the last element that got removed,
                        // or if it was beyond the new last element, reset to 0 or the new last valid index.
                        if (currentImageIndex >= allImageUris.size) {
                            currentImageIndex = allImageUris.size - 1 // Go to the new last image
                            if (currentImageIndex < 0) currentImageIndex = 0 // Or first if list became 1 item
                        }
                        // Important: After removing, we might be on the "next" image already in terms of position.
                        // Or we might want to stay at the same index if it's still valid.
                        // Let's try loading the image at the current (possibly adjusted) index.
                        // If we deleted the last image, currentImageIndex will be allImageUris.size -1
                        // If we deleted an image in the middle, currentImageIndex still points to the *next* image in sequence
                        // So, we don't necessarily need to increment currentImageIndex here before loading.
                        // However, to ensure we show the *next logical* image after deletion,
                        // we effectively want to load what *would have been* the next image.
                        // Since `loadNextImage` increments, and we just removed,
                        // let's decrement currentImageIndex so loadNextImage loads the correct one.
                        // But if we deleted the *last* image, currentImageIndex should be reset.

                        if (currentImageIndex >= allImageUris.size) { // If we deleted the last item
                            currentImageIndex = 0 // Loop to start or handle empty
                        }
                        // No, `loadNextImage` handles index internally. We just need to ensure the index is valid
                        // or signal to load the "next" one based on the current state.
                        // Let's simply call loadNextImage, it will handle the indexing.
                        // However, after removing, the currentImageIndex might be pointing to an image
                        // that was *after* the deleted one.
                        // A safer approach is to decide which image to show based on the deletion.
                        // Typically, you show the image that takes the place of the deleted one.
                        // If `currentImageIndex` was not the last item, it now points to the "next" image.

                        if (allImageUris.isNotEmpty()) {
                            // If currentImageIndex is now past the end of the list (e.g., deleted the last item)
                            if (currentImageIndex >= allImageUris.size) {
                                currentImageIndex = 0 // Wrap around to the beginning
                            }
                            // We need to display the image at the *new* currentImageIndex.
                            // `loadNextImage` increments, so to display the image at the *current*
                            // new position, we can temporarily decrement and let `loadNextImage` increment it back.
                            // Or, more directly:
                            if (allImageUris.isNotEmpty()) {
                                // If the currentImageIndex is now invalid (e.g. was last item)
                                // or if you always want to show the image that took the deleted one's place
                                if (currentImageIndex >= allImageUris.size) {
                                    currentImageIndex = if (allImageUris.isEmpty()) -1 else 0
                                }
                                // Now load the image at the adjusted currentImageIndex directly or call loadNextImage appropriately
                                if (allImageUris.isNotEmpty()) {
                                    // We need to load the image that is now at `currentImageIndex`
                                    // or the "next" if `currentImageIndex` was the one deleted.
                                    // Let's adjust currentImageIndex so loadNextImage loads the one that took its place, or the next available.
                                    // If currentImageIndex was, say, 2 (3rd image) and we deleted it,
                                    // the new image at index 2 should be shown.
                                    // loadNextImage increments, so we set currentImageIndex to one less than what we want to display next
                                    // and it will pick it up.
                                    // This logic gets tricky. Let's simplify:
                                    // After removing, if currentImageIndex is still valid, display that one.
                                    // If it's not (e.g. was the last item), go to the new last or first.

                                    if (currentImageIndex >= allImageUris.size) { // If we deleted the item at the end
                                        currentImageIndex = if (allImageUris.isEmpty()) -1 else allImageUris.size -1 // Point to the new last item
                                        loadSpecificImage(currentImageIndex) // Load this specific image
                                    } else {
                                        // An item in the middle (or start) was deleted.
                                        // The item at `currentImageIndex` is now the one that followed the deleted item.
                                        loadSpecificImage(currentImageIndex) // Load this item that shifted into place
                                    }
                                } else {
                                    handleNoPhotosFound()
                                }
                            } else {
                                handleNoPhotosFound()
                            }
                        }
                    }
                }
            } else {
                // Deletion was denied or cancelled by the user.
                Toast.makeText(this, getString(R.string.toast_photo_deletion_cancelled_failed), Toast.LENGTH_SHORT).show()
            }
        }


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

        buttonDelphoto.setOnClickListener {
            if (allImageUris.isNotEmpty() && currentImageIndex >= 0 && currentImageIndex < allImageUris.size) {
                deleteCurrentImage()
            } else {
                Toast.makeText(this, getString(R.string.toast_no_photo_selected_to_del), Toast.LENGTH_SHORT).show()
            }
        }
        // Make buttonDelphoto visible if there are photos
        // This will be handled in updatePhotoCountText or loadAllImageUrisFromCamera


        updatePhotoCountText() // 初始时可能没有图片，先调用一次
    }

    private fun checkAndRequestPermission() {
        val readPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        // For deleting, WRITE_EXTERNAL_STORAGE is needed for API < Q (29)
        // For API Q+, special handling via MediaStore is needed (RecoverableSecurityException)
        // For API R+ (30), no direct file path access usually, deletions through MediaStore.

        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, readPermission) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(readPermission)
        }

        // Only request WRITE_EXTERNAL_STORAGE if targeting below Android Q and it's not granted
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }


        if (permissionsToRequest.isNotEmpty()) {
            // Explain why you need the permissions if rationale should be shown for any of them
            var showRationale = false
            permissionsToRequest.forEach { perm ->
                if (shouldShowRequestPermissionRationale(perm)) {
                    showRationale = true
                }
            }
            if (showRationale) {
                Toast.makeText(this, getString(R.string.toast_need_permissions_show_manage_photo), Toast.LENGTH_LONG).show()
            }
            // Request all needed permissions
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                val readGranted = permissions[readPermission] ?: false
                // val writeGranted = permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) // Assume "granted" for Q+ for this check as it's handled differently

                if (readGranted) {
                    loadAllImageUrisFromCamera()
                } else {
                    Toast.makeText(this, getString(R.string.toast_read_permission_denied), Toast.LENGTH_SHORT).show()
                    finish()
                }
                // Check write permission specifically for older versions if it was requested
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && permissionsToRequest.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    if (permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] != true) {
                        Toast.makeText(this, getString(R.string.toast_write_permission_denied_on_older_sysver), Toast.LENGTH_LONG).show()
                    }
                }

            }.launch(permissionsToRequest.toTypedArray())

        } else {
            // All necessary permissions already granted
            loadAllImageUrisFromCamera()
        }
    }

    private fun deleteCurrentImage() {
        if (currentImageIndex < 0 || currentImageIndex >= allImageUris.size) {
            Toast.makeText(this, getString(R.string.toast_no_image_to_del_or_index), Toast.LENGTH_SHORT).show()
            return
        }

        val uriToDelete = allImageUris[currentImageIndex]
        Log.d("PhotoListActivity", "Attempting to delete URI: $uriToDelete")


        try {
            // For Android Q (API 29) and above, you need to handle RecoverableSecurityException
            // For older versions, you can use contentResolver.delete directly if you have WRITE_EXTERNAL_STORAGE
            val deletedRows = contentResolver.delete(uriToDelete, null, null)
            if (deletedRows > 0) {
                Toast.makeText(this, getString(R.string.toast_photo_deleted_succe), Toast.LENGTH_SHORT).show()
                allImageUris.removeAt(currentImageIndex)

                if (allImageUris.isEmpty()) {
                    handleNoPhotosFound()
                } else {
                    // Important: Adjust currentImageIndex carefully.
                    // If we deleted the last item, index should wrap around or point to new last.
                    // If we deleted from middle/start, currentImageIndex now points to the *next* item.
                    if (currentImageIndex >= allImageUris.size) { // If it was the last item
                        currentImageIndex = 0 // Go to the first item (or could be allImageUris.size - 1 for new last)
                    }
                    // No need to increment currentImageIndex before calling loadNextImage,
                    // as loadNextImage itself will handle advancing.
                    // However, to show the item that *took the place* of the deleted one,
                    // we want to load the image at the *current* `currentImageIndex` (if valid).
                    // Or if currentImageIndex became invalid (e.g., list became shorter), adjust it.

                    // Let's simply try to load the "next" image. If the list is now shorter,
                    // currentImageIndex might be out of bounds for the *next* access in loadNextImage.
                    // So, it's often better to load the image at the current index (if it's still valid)
                    // or the new first/last if the old index is no longer valid.

                    // Simplified logic: After deletion, attempt to load the image now at currentImageIndex.
                    // If currentImageIndex is now out of bounds (e.g., deleted the last image), reset.
                    if (currentImageIndex >= allImageUris.size && allImageUris.isNotEmpty()) {
                        currentImageIndex = allImageUris.size - 1 // Go to the new last image
                    }
                    // We call loadSpecificImage to ensure the image at the *new* currentImageIndex is loaded
                    // without accidentally skipping one due to loadNextImage's increment.
                    loadSpecificImage(currentImageIndex)
                }
                updatePhotoCountText()

            } else {
                // This might happen if the URI is invalid, or on Android Q+ without proper handling
                Log.e("PhotoListActivity", "Deletion failed, rows deleted: $deletedRows. URI: $uriToDelete")
                Toast.makeText(this, getString(R.string.toast_failed_to_delete_photo), Toast.LENGTH_LONG).show()
            }
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val recoverableSecurityException = e as? RecoverableSecurityException
                if (recoverableSecurityException != null) {
                    val intentSender = recoverableSecurityException.userAction.actionIntent.intentSender
                    try {
                        deleteRequestLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                        // The result will be handled in the deleteRequestLauncher's callback
                    } catch (sendEx: IntentSender.SendIntentException) {
                        Log.e("PhotoListActivity", "Error launching delete confirmation", sendEx)
                        Toast.makeText(this, "getString(R.string.toast_could_not_request_deletion): ${sendEx.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                    return // Important: return here as the deletion is now pending user confirmation
                } else {
                    // Not a RecoverableSecurityException, re-throw or handle differently
                    Log.e("PhotoListActivity", "SecurityException (not recoverable) while deleting: ${e.localizedMessage}", e)
                    Toast.makeText(this, "getString(R.string.toast_deletion_failed_security_reasons): ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    // You might want to request WRITE_EXTERNAL_STORAGE here for pre-Q if that's the issue,
                    // but the checkAndRequestPermission should handle that.
                    // For Q+, this usually means you can't delete it this way.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(this, "WRITE_EXTERNAL_STORAGE getString(R.string.toast_permission_needed)", Toast.LENGTH_LONG).show()
                        requestWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                }
            } else {
                // On versions below Q, a SecurityException usually means lack of WRITE_EXTERNAL_STORAGE
                Log.e("PhotoListActivity", "SecurityException while deleting (pre-Q): ${e.localizedMessage}", e)
                Toast.makeText(this, "getString(R.string.toast_deletion_failed_check_permissions): ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requestWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        } catch (ex: Exception) {
            Log.e("PhotoListActivity", "Error deleting photo: ${ex.localizedMessage}", ex)
            Toast.makeText(this, "getString(R.string.toast_error_deleting_photo): ${ex.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadAllImageUrisFromCamera() {
        // ... (rest of the loadAllImageUrisFromCamera method remains largely the same)
        // Ensure allImageUris is cleared before loading new ones
        allImageUris.clear() // Clear previous URIs
        currentImageIndex = -1 // Reset index

        val permissionToCheck = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permissionToCheck) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.toast_permissions_not_granted), Toast.LENGTH_LONG).show()
            handleNoPhotosFound(true) // Pass true to indicate it's due to permissions
            //finish() // Return to previous view if permissions not granted
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
                    handleNoPhotosFound()
                    //Toast.makeText(this, "No photos found in Camera directory.", Toast.LENGTH_LONG).show()
                    //buttonBackmain.visibility = View.VISIBLE // Ensure back button is visible even if no photos
                    //finish() // Optionally finish if no images
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
                //allImageUris = tempImageUris
                allImageUris.addAll(tempImageUris) // Use addAll to the mutable list
                if (allImageUris.isNotEmpty()) {
                    currentImageIndex = -1 // Start before the first image
                    loadNextImage() // Load the first image
                    buttonNext.visibility = View.VISIBLE
                    buttonBackmain.visibility = View.VISIBLE // Also ensure it's visible here
                } else {
                    handleNoPhotosFound()
                    //Toast.makeText(this, "No photos found after processing.", Toast.LENGTH_LONG).show()
                    //buttonBackmain.visibility = View.VISIBLE // Ensure back button is visible
                    //finish() // Return to previous view if no photos after processing
                }
            } ?: run {
                Toast.makeText(this, getString(R.string.toast_cant_query_MediaStore), Toast.LENGTH_LONG).show()
                //buttonBackmain.visibility = View.VISIBLE // Ensure back button is visible
                handleNoPhotosFound()
                //finish() // Return to previous view if MediaStore query fails
            }
        } catch (e: Exception) {
            Toast.makeText(this, "getString(R.string.toast_error_loading_images): ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            Log.e("PhotoListActivity", "Error in loadAllImageUrisFromCamera", e)
            handleNoPhotosFound(true)
            //e.printStackTrace()
            buttonBackmain.visibility = View.VISIBLE // Ensure back button is visible
            updatePhotoCountText()
            //finish()
        }
    }

    private fun loadNextImage() {
        if (allImageUris.isEmpty()) {
            //Toast.makeText(this, "No images to display.", Toast.LENGTH_SHORT).show()
            if (!isFinishing && !isDestroyed) { // Check if activity is still active
                latestImageView.setImageDrawable(null) // Clear image
                Toast.makeText(this, getString(R.string.toast_no_images_to_display), Toast.LENGTH_SHORT).show()
                buttonNext.visibility = View.GONE
                buttonDelphoto.visibility = View.GONE
                updatePhotoCountText() // Will show "0 / 0" or similar
                //buttonBackmain.visibility = View.VISIBLE // Ensure back button is visible
                // Consider not finishing here automatically if you want the user to explicitly go back
                //finish()
            }
            return
        }

        currentImageIndex++

        if (currentImageIndex >= allImageUris.size) {
            currentImageIndex = 0 // Loop back to the first image
            Toast.makeText(this, getString(R.string.toast_reached_end_starting_over), Toast.LENGTH_SHORT).show()
        }
        loadSpecificImage(currentImageIndex)

        //val imageUriToLoad = allImageUris[currentImageIndex]

        //Glide.with(this)
        //    .load(imageUriToLoad)
        //    .placeholder(R.drawable.ic_launcher_background) // Optional: add a placeholder
        //    .error(android.R.drawable.stat_notify_error) // Optional: add an error image
        //    .into(latestImageView)
        //updatePhotoCountText() // 加载新图片后更新计数
        //photoCountTextView.visibility = View.VISIBLE // 确保计数可见
    }

    // New function to load a specific image by index
    private fun loadSpecificImage(index: Int) {
        if (index < 0 || index >= allImageUris.size) {
            Log.w("PhotoListActivity", "loadSpecificImage: Invalid index $index, list size ${allImageUris.size}")
            if (allImageUris.isEmpty()) {
                handleNoPhotosFound()
            } else {
                // Fallback to loading the first image if index is bad but list is not empty
                currentImageIndex = 0
                if (allImageUris.isNotEmpty()) { // Double check
                    Glide.with(this)
                        .load(allImageUris[currentImageIndex])
                        .placeholder(R.drawable.ic_launcher_background)
                        .error(android.R.drawable.stat_notify_error)
                        .into(latestImageView)
                } else {
                    handleNoPhotosFound()
                }
            }
            updatePhotoCountText()
            return
        }

        currentImageIndex = index // Ensure currentImageIndex is set correctly
        val imageUriToLoad = allImageUris[index]
        Log.d("PhotoListActivity", "Loading image at index $index: $imageUriToLoad")

        Glide.with(this)
            .load(imageUriToLoad)
            .placeholder(R.drawable.ic_launcher_background)
            .error(android.R.drawable.stat_notify_error)
            .into(latestImageView)
        updatePhotoCountText()
        photoCountTextView.visibility = View.VISIBLE
        buttonNext.visibility = if (allImageUris.size > 1) View.VISIBLE else View.GONE
        buttonDelphoto.visibility = View.VISIBLE
    }



    // 新增方法来更新 TextView
    private fun updatePhotoCountText() {
        if (allImageUris.isNotEmpty()) {
            val displayIndex = if (currentImageIndex < 0 && allImageUris.isNotEmpty()) 0 else currentImageIndex
            val currentNumber = if (allImageUris.isEmpty()) 0 else displayIndex + 1
            val totalNumber = allImageUris.size
            //val currentNumber = currentImageIndex + 1 // 用户看到的序号从1开始
            //val totalNumber = allImageUris.size
            photoCountTextView.text = getString(R.string.photo_count_format, currentNumber, totalNumber)
            photoCountTextView.visibility = View.VISIBLE
            buttonDelphoto.visibility = View.VISIBLE
            buttonNext.visibility = if (allImageUris.size > 1) View.VISIBLE else View.GONE
        } else {
            photoCountTextView.text = getString(R.string.photo_count_format, 0, 0) // Or "No photos"
            photoCountTextView.visibility = View.VISIBLE // Or GONE if you prefer
            buttonDelphoto.visibility = View.GONE
            buttonNext.visibility = View.GONE
            latestImageView.setImageDrawable(null) // Clear image view
            //photoCountTextView.text = "" // 或者可以显示 "0 / 0" 或特定提示
            //photoCountTextView.visibility = View.GONE // 如果没有图片，则隐藏计数
        }
    }
    private fun handleNoPhotosFound(isError: Boolean = false) {
        if (!isFinishing && !isDestroyed) {
            Toast.makeText(
                this,
                if (isError) getString(R.string.toast_error_accessing_photos) else getString(R.string.toast_no_photos_found),
                Toast.LENGTH_LONG
            ).show()
            allImageUris.clear()
            currentImageIndex = -1
            latestImageView.setImageDrawable(
                ContextCompat.getDrawable(
                    this,
                    android.R.drawable.ic_menu_gallery
                )
            ) // Show a placeholder
            updatePhotoCountText() // This will update text to "0 / 0" and hide buttons
            buttonBackmain.visibility = View.VISIBLE
            // Consider if you want to finish() the activity here.
            // If it's an error or permission issue, finishing might be appropriate.
            // If it's just no photos, maybe let the user go back manually.
            // For now, let's not finish automatically unless it's a critical permission error handled elsewhere.
        }
    }
}