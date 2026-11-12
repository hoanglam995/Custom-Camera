package com.example.fakecamera

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private val PICK_IMAGES_REPLACE_REQUEST = 1001
    private val PICK_IMAGES_APPEND_REQUEST = 1002

    private val KEY_USED_PHOTOS = "used_photo_names"
    private val KEY_LAST_PHOTO = "last_used_photo_name"
    private val KEY_UPDATE_EXIF = "update_exif"

    private lateinit var presetDir: File
    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var cycleInfoText: TextView
    private lateinit var imageListContainer: LinearLayout
    private lateinit var updateExifCheckbox: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        presetDir = File(filesDir, "preset_photos").apply {
            if (!exists()) mkdirs()
        }

        // Tự động chuyển file ảnh đơn từ bản v1 (nếu có) sang thư mục danh sách ảnh
        val legacyFile = File(filesDir, "preset_photo.jpg")
        if (legacyFile.exists()) {
            val migratedFile = File(presetDir, "photo_migrated.jpg")
            legacyFile.copyTo(migratedFile, overwrite = true)
            legacyFile.delete()
        }

        prefs = getSharedPreferences("config", MODE_PRIVATE)

        // Kiểm tra nếu app đang được gọi bởi lệnh chụp ảnh từ ứng dụng khác
        if (intent?.action == MediaStore.ACTION_IMAGE_CAPTURE ||
            intent?.action == "android.media.action.IMAGE_CAPTURE_SECURE"
        ) {
            val updateExif = prefs.getBoolean(KEY_UPDATE_EXIF, true)
            handleCameraCaptureRequest(updateExif)
            return
        }

        // Giao diện quản lý danh sách ảnh khi mở app trực tiếp
        val scrollView = ScrollView(this)
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, 12)
        }

        cycleInfoText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#2E7D32"))
            setPadding(0, 0, 0, 24)
        }

        updateExifCheckbox = CheckBox(this).apply {
            text = "Tự động cập nhật ngày giờ chụp hiện tại (EXIF)"
            isChecked = prefs.getBoolean(KEY_UPDATE_EXIF, true)
            setPadding(0, 0, 0, 24)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(KEY_UPDATE_EXIF, isChecked).apply()
            }
        }

        val replaceBtn = Button(this).apply {
            text = "Chọn danh sách ảnh mới (Thay thế)"
            setOnClickListener {
                openMultiImagePicker(PICK_IMAGES_REPLACE_REQUEST)
            }
        }

        val appendBtn = Button(this).apply {
            text = "Thêm ảnh vào danh sách hiện có"
            setOnClickListener {
                openMultiImagePicker(PICK_IMAGES_APPEND_REQUEST)
            }
        }

        val resetCycleBtn = Button(this).apply {
            text = "Reset lượt Random (Bắt đầu vòng mới)"
            setOnClickListener {
                resetRandomCycle(keepLastPhotoMemory = true)
                refreshPhotoListUI()
                Toast.makeText(this@MainActivity, "Đã làm mới vòng lặp random!", Toast.LENGTH_SHORT).show()
            }
        }

        val clearBtn = Button(this).apply {
            text = "Xóa tất cả ảnh đã lưu"
            setOnClickListener {
                clearAllSavedImages()
                refreshPhotoListUI()
                Toast.makeText(this@MainActivity, "Đã xóa toàn bộ danh sách ảnh!", Toast.LENGTH_SHORT).show()
            }
        }

        imageListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 32, 0, 0)
        }

        rootLayout.addView(statusText)
        rootLayout.addView(cycleInfoText)
        rootLayout.addView(updateExifCheckbox)
        rootLayout.addView(replaceBtn)
        rootLayout.addView(appendBtn)
        rootLayout.addView(resetCycleBtn)
        rootLayout.addView(clearBtn)
        rootLayout.addView(imageListContainer)

        scrollView.addView(rootLayout)
        setContentView(scrollView)

        refreshPhotoListUI()
    }

    override fun onResume() {
        super.onResume()
        if (::imageListContainer.isInitialized) {
            refreshPhotoListUI()
        }
    }

    private fun openMultiImagePicker(requestCode: Int) {
        val pickIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(
            Intent.createChooser(pickIntent, "Chọn ảnh từ Thư viện hoặc Tệp tải xuống"),
            requestCode
        )
    }

    private fun getSavedPhotos(): List<File> {
        return presetDir.listFiles { file -> file.isFile }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun getUsedPhotoNames(): Set<String> {
        val validNames = getSavedPhotos().map { it.name }.toSet()
        val stored = prefs.getStringSet(KEY_USED_PHOTOS, emptySet()) ?: emptySet()
        // Chỉ giữ lại tên các file hiện còn tồn tại trong thư mục
        return stored.intersect(validNames)
    }

    private fun resetRandomCycle(keepLastPhotoMemory: Boolean) {
        val editor = prefs.edit().remove(KEY_USED_PHOTOS)
        if (!keepLastPhotoMemory) {
            editor.remove(KEY_LAST_PHOTO)
        }
        editor.apply()
    }

    private fun clearAllSavedImages() {
        presetDir.listFiles()?.forEach { it.delete() }
        resetRandomCycle(keepLastPhotoMemory = false)
    }

    private fun pickNextNonRepeatingPhoto(photos: List<File>): File {
        val usedSet = getUsedPhotoNames().toMutableSet()
        val lastUsedName = prefs.getString(KEY_LAST_PHOTO, null)

        var availablePhotos = photos.filter { it.name !in usedSet }

        // Nếu đã dùng hết toàn bộ ảnh trong vòng hiện tại -> Reset sang vòng mới
        if (availablePhotos.isEmpty()) {
            usedSet.clear()
            // Tránh chọn lại đúng bức ảnh vừa dùng ở cuối vòng trước (nếu có từ 2 ảnh trở lên)
            availablePhotos = if (photos.size > 1 && lastUsedName != null) {
                photos.filter { it.name != lastUsedName }.ifEmpty { photos }
            } else {
                photos
            }
        }

        val chosenPhoto = availablePhotos[Random.nextInt(availablePhotos.size)]
        usedSet.add(chosenPhoto.name)

        prefs.edit()
            .putStringSet(KEY_USED_PHOTOS, HashSet(usedSet))
            .putString(KEY_LAST_PHOTO, chosenPhoto.name)
            .apply()

        return chosenPhoto
    }

    private fun refreshPhotoListUI() {
        val photos = getSavedPhotos()
        val usedSet = getUsedPhotoNames()
        val remainingCount = (photos.size - usedSet.size).coerceAtLeast(0)

        statusText.text = if (photos.isNotEmpty()) {
            "Trạng thái: Tổng cộng ${photos.size} ảnh trong kho"
        } else {
            "Trạng thái: Chưa có ảnh nào (Hỗ trợ cả ảnh chụp & ảnh tải từ mạng)"
        }

        cycleInfoText.text = if (photos.isNotEmpty()) {
            "Vòng lặp hiện tại: Đã dùng ${usedSet.size}/${photos.size} ảnh (Còn $remainingCount ảnh chưa xuất hiện)"
        } else {
            "Giữ lì vào ảnh trong mục Tải xuống / Thư viện để chọn nhiều ảnh cùng lúc"
        }

        imageListContainer.removeAllViews()

        photos.forEachIndexed { index, file ->
            val isUsed = file.name in usedSet
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 16, 16, 16)
            }

            val thumbView = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(180, 180).apply {
                    rightMargin = 24
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    setImageBitmap(ThumbnailUtils.extractThumbnail(bmp, 180, 180))
                }
            }

            val stateLabel = if (isUsed) "Đã dùng ở vòng này" else "Chưa dùng (Sẵn sàng)"
            val infoText = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "Ảnh #${index + 1} (${file.length() / 1024} KB)\n$stateLabel"
                textSize = 14f
                setTextColor(if (isUsed) Color.GRAY else Color.BLACK)
            }

            val deleteBtn = Button(this).apply {
                text = "Xóa"
                setTextColor(Color.RED)
                setOnClickListener {
                    file.delete()
                    refreshPhotoListUI()
                }
            }

            row.addView(thumbView)
            row.addView(infoText)
            row.addView(deleteBtn)
            imageListContainer.addView(row)
        }
    }

    private fun handleCameraCaptureRequest(updateExif: Boolean) {
        val photos = getSavedPhotos()
        if (photos.isEmpty()) {
            Toast.makeText(
                this,
                "Vui lòng mở Custom Camera chọn ít nhất 1 ảnh trước!",
                Toast.LENGTH_LONG
            ).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        try {
            // Chọn ngẫu nhiên không lặp lại cho đến khi hết vòng
            val chosenPhoto = pickNextNonRepeatingPhoto(photos)

            val workingFile = File(cacheDir, "temp_capture.jpg")
            chosenPhoto.copyTo(workingFile, overwrite = true)

            if (updateExif) {
                try {
                    val exif = ExifInterface(workingFile.absolutePath)
                    val nowStr = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
                    exif.setAttribute(ExifInterface.TAG_DATETIME, nowStr)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, nowStr)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, nowStr)
                    exif.saveAttributes()
                } catch (_: Exception) {
                }
            }

            @Suppress("DEPRECATION")
            val outputUri: Uri? = intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT)

            if (outputUri != null) {
                contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                    workingFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                setResult(Activity.RESULT_OK)
            } else {
                val bitmap = BitmapFactory.decodeFile(workingFile.absolutePath)
                val thumbnail = ThumbnailUtils.extractThumbnail(bitmap, 256, 256)
                val resultIntent = Intent().putExtra("data", thumbnail)
                setResult(Activity.RESULT_OK, resultIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Lỗi trả ảnh: ${e.message}", Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_CANCELED)
        } finally {
            finish()
        }
    }

    /**
     * Lưu và chuẩn hóa mọi định dạng ảnh tải từ trên mạng (WebP, PNG, JPEG...)
     * sang chuẩn JPEG thực sự để ghi EXIF và tương thích 100% với các app chấm công.
     */
    private fun saveUriToPresetDir(uri: Uri, indexSuffix: Int): Boolean {
        val destFile = File(presetDir, "photo_${System.currentTimeMillis()}_$indexSuffix.jpg")
        return try {
            // 1. Đọc hướng xoay EXIF gốc (nếu có) để không bị xoay ngang ảnh tải về
            var rotationDegrees = 0f
            contentResolver.openInputStream(uri)?.use { exifStream ->
                try {
                    val exif = ExifInterface(exifStream)
                    rotationDegrees = when (
                        exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                    ) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } catch (_: Exception) {
                }
            }

            // 2. Giải mã ảnh (hỗ trợ cả JPEG, PNG, WebP, BMP tải từ Chrome/Zalo/Facebook)
            val decodedBitmap = contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }

            if (decodedBitmap != null) {
                val finalBitmap = if (rotationDegrees != 0f) {
                    val matrix = Matrix().apply { postRotate(rotationDegrees) }
                    Bitmap.createBitmap(
                        decodedBitmap,
                        0,
                        0,
                        decodedBitmap.width,
                        decodedBitmap.height,
                        matrix,
                        true
                    )
                } else {
                    decodedBitmap
                }

                // 3. Nén chuẩn hóa thành file JPEG chất lượng cao (95%)
                destFile.outputStream().use { output ->
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
                }
                true
            } else {
                // Dự phòng: copy trực tiếp luồng dữ liệu nếu không giải mã được qua BitmapFactory
                contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                destFile.exists() && destFile.length() > 0
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == PICK_IMAGES_REPLACE_REQUEST || requestCode == PICK_IMAGES_APPEND_REQUEST) &&
            resultCode == Activity.RESULT_OK && data != null
        ) {
            if (requestCode == PICK_IMAGES_REPLACE_REQUEST) {
                clearAllSavedImages()
            }

            var savedCount = 0
            val clipData = data.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    val uri = clipData.getItemAt(i).uri
                    if (saveUriToPresetDir(uri, i)) {
                        savedCount++
                    }
                }
            } else {
                data.data?.let { uri ->
                    if (saveUriToPresetDir(uri, 0)) {
                        savedCount++
                    }
                }
            }

            refreshPhotoListUI()
            Toast.makeText(
                this,
                "Đã lưu & chuẩn hóa JPEG $savedCount ảnh (Tổng: ${getSavedPhotos().size} ảnh)!",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
