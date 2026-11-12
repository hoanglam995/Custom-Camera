package com.example.fakecamera

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
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
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    enum class TimeSlot(
        val id: String,
        val title: String,
        val timeRangeLabel: String,
        val folderName: String,
        val usedPhotosKey: String,
        val lastPhotoKey: String
    ) {
        MORNING(
            id = "morning",
            title = "BUỔI SÁNG",
            timeRangeLabel = "06:00 - 17:59",
            folderName = "preset_photos_morning",
            usedPhotosKey = "used_photo_names_morning",
            lastPhotoKey = "last_used_photo_name_morning"
        ),
        EVENING(
            id = "evening",
            title = "BUỔI TỐI",
            timeRangeLabel = "18:00 - 05:59",
            folderName = "preset_photos_evening",
            usedPhotosKey = "used_photo_names_evening",
            lastPhotoKey = "last_used_photo_name_evening"
        )
    }

    private val PICK_IMAGES_REPLACE_REQUEST = 1001
    private val PICK_IMAGES_APPEND_REQUEST = 1002
    private val KEY_UPDATE_EXIF = "update_exif"

    private lateinit var prefs: SharedPreferences
    private var currentEditingSlot: TimeSlot = TimeSlot.MORNING

    private lateinit var activeTimeRuleText: TextView
    private lateinit var morningTabBtn: Button
    private lateinit var eveningTabBtn: Button
    private lateinit var statusText: TextView
    private lateinit var cycleInfoText: TextView
    private lateinit var replaceBtn: Button
    private lateinit var appendBtn: Button
    private lateinit var resetCycleBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var imageListContainer: LinearLayout
    private lateinit var updateExifCheckbox: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Khởi tạo 2 thư mục Sáng & Tối
        getSlotDir(TimeSlot.MORNING)
        getSlotDir(TimeSlot.EVENING)

        // Tự động chuyển dữ liệu ảnh từ các bản cũ (v1, v2, v3) sang kho ảnh Buổi Sáng
        migrateLegacyPhotos()

        prefs = getSharedPreferences("config", MODE_PRIVATE)

        // Kiểm tra nếu app đang được gọi bởi lệnh chụp ảnh từ ứng dụng khác
        if (intent?.action == MediaStore.ACTION_IMAGE_CAPTURE ||
            intent?.action == "android.media.action.IMAGE_CAPTURE_SECURE"
        ) {
            val updateExif = prefs.getBoolean(KEY_UPDATE_EXIF, true)
            handleCameraCaptureRequest(updateExif)
            return
        }

        // Mặc định mở tab tương ứng với khung giờ hiện tại
        currentEditingSlot = getActiveTimeSlotByCurrentClock()

        // Giao diện quản lý 2 danh sách ảnh
        val scrollView = ScrollView(this)
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 36, 36, 36)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        activeTimeRuleText = TextView(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(0, 0, 0, 20)
        }

        // Thanh chuyển đổi 2 danh sách: Buổi Sáng / Buổi Tối
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 20
            }
        }

        morningTabBtn = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 8
            }
            setOnClickListener {
                currentEditingSlot = TimeSlot.MORNING
                refreshPhotoListUI()
            }
        }

        eveningTabBtn = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = 8
            }
            setOnClickListener {
                currentEditingSlot = TimeSlot.EVENING
                refreshPhotoListUI()
            }
        }

        tabRow.addView(morningTabBtn)
        tabRow.addView(eveningTabBtn)

        statusText = TextView(this).apply {
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }

        cycleInfoText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#2E7D32"))
            setPadding(0, 0, 0, 20)
        }

        updateExifCheckbox = CheckBox(this).apply {
            text = "Tự động cập nhật ngày giờ chụp hiện tại (EXIF)"
            isChecked = prefs.getBoolean(KEY_UPDATE_EXIF, true)
            setPadding(0, 0, 0, 20)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(KEY_UPDATE_EXIF, isChecked).apply()
            }
        }

        replaceBtn = Button(this).apply {
            setOnClickListener {
                openMultiImagePicker(PICK_IMAGES_REPLACE_REQUEST)
            }
        }

        appendBtn = Button(this).apply {
            setOnClickListener {
                openMultiImagePicker(PICK_IMAGES_APPEND_REQUEST)
            }
        }

        resetCycleBtn = Button(this).apply {
            setOnClickListener {
                resetRandomCycle(currentEditingSlot, keepLastPhotoMemory = true)
                refreshPhotoListUI()
                Toast.makeText(
                    this@MainActivity,
                    "Đã làm mới vòng lặp random cho kho ${currentEditingSlot.title}!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        clearBtn = Button(this).apply {
            setOnClickListener {
                clearAllSavedImages(currentEditingSlot)
                refreshPhotoListUI()
                Toast.makeText(
                    this@MainActivity,
                    "Đã xóa toàn bộ ảnh trong kho ${currentEditingSlot.title}!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        imageListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 28, 0, 0)
        }

        rootLayout.addView(activeTimeRuleText)
        rootLayout.addView(tabRow)
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

    /**
     * Quy tắc thời gian:
     * - Từ 06:00 đến 17:59 (hour in 6..17) -> Lấy danh sách ảnh BUỔI SÁNG
     * - Từ 18:00 đến 05:59 sáng hôm sau -> Lấy danh sách ảnh BUỔI TỐI
     */
    private fun getActiveTimeSlotByCurrentClock(): TimeSlot {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (hour in 6..17) {
            TimeSlot.MORNING
        } else {
            TimeSlot.EVENING
        }
    }

    private fun getSlotDir(slot: TimeSlot): File {
        return File(filesDir, slot.folderName).apply {
            if (!exists()) mkdirs()
        }
    }

    private fun migrateLegacyPhotos() {
        val morningDir = getSlotDir(TimeSlot.MORNING)

        // Chuyển file đơn từ bản v1 (nếu còn)
        val legacySingleFile = File(filesDir, "preset_photo.jpg")
        if (legacySingleFile.exists()) {
            val target = File(morningDir, "photo_migrated_v1.jpg")
            legacySingleFile.copyTo(target, overwrite = true)
            legacySingleFile.delete()
        }

        // Chuyển thư mục chung từ bản v2/v3 sang kho Buổi Sáng
        val legacyDir = File(filesDir, "preset_photos")
        if (legacyDir.exists() && legacyDir.isDirectory) {
            legacyDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val target = File(morningDir, file.name)
                    file.copyTo(target, overwrite = true)
                    file.delete()
                }
            }
            legacyDir.delete()
        }
    }

    private fun openMultiImagePicker(requestCode: Int) {
        val pickIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(
            Intent.createChooser(
                pickIntent,
                "Chọn ảnh cho kho ${currentEditingSlot.title} (${currentEditingSlot.timeRangeLabel})"
            ),
            requestCode
        )
    }

    private fun getSavedPhotos(slot: TimeSlot): List<File> {
        return getSlotDir(slot).listFiles { file -> file.isFile }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun getUsedPhotoNames(slot: TimeSlot): Set<String> {
        val validNames = getSavedPhotos(slot).map { it.name }.toSet()
        val stored = prefs.getStringSet(slot.usedPhotosKey, emptySet()) ?: emptySet()
        return stored.intersect(validNames)
    }

    private fun resetRandomCycle(slot: TimeSlot, keepLastPhotoMemory: Boolean) {
        val editor = prefs.edit().remove(slot.usedPhotosKey)
        if (!keepLastPhotoMemory) {
            editor.remove(slot.lastPhotoKey)
        }
        editor.apply()
    }

    private fun clearAllSavedImages(slot: TimeSlot) {
        getSlotDir(slot).listFiles()?.forEach { it.delete() }
        resetRandomCycle(slot, keepLastPhotoMemory = false)
    }

    private fun pickNextNonRepeatingPhoto(slot: TimeSlot, photos: List<File>): File {
        val usedSet = getUsedPhotoNames(slot).toMutableSet()
        val lastUsedName = prefs.getString(slot.lastPhotoKey, null)

        var availablePhotos = photos.filter { it.name !in usedSet }

        // Nếu đã dùng hết toàn bộ ảnh trong vòng hiện tại của kho này -> Reset sang vòng mới
        if (availablePhotos.isEmpty()) {
            usedSet.clear()
            availablePhotos = if (photos.size > 1 && lastUsedName != null) {
                photos.filter { it.name != lastUsedName }.ifEmpty { photos }
            } else {
                photos
            }
        }

        val chosenPhoto = availablePhotos[Random.nextInt(availablePhotos.size)]
        usedSet.add(chosenPhoto.name)

        prefs.edit()
            .putStringSet(slot.usedPhotosKey, HashSet(usedSet))
            .putString(slot.lastPhotoKey, chosenPhoto.name)
            .apply()

        return chosenPhoto
    }

    private fun refreshPhotoListUI() {
        val nowTimeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val activeSlotNow = getActiveTimeSlotByCurrentClock()
        activeTimeRuleText.text =
            "Giờ hiện tại: $nowTimeStr → Nếu chụp lúc này sẽ lấy kho ${activeSlotNow.title} (${activeSlotNow.timeRangeLabel})"

        val morningCount = getSavedPhotos(TimeSlot.MORNING).size
        val eveningCount = getSavedPhotos(TimeSlot.EVENING).size

        morningTabBtn.text = "Buổi Sáng ($morningCount)\n06:00 - 17:59"
        eveningTabBtn.text = "Buổi Tối ($eveningCount)\n18:00 - 05:59"

        // Làm nổi bật tab đang chọn để chỉnh sửa
        if (currentEditingSlot == TimeSlot.MORNING) {
            morningTabBtn.setBackgroundColor(Color.parseColor("#FFB300"))
            morningTabBtn.setTextColor(Color.BLACK)
            eveningTabBtn.setBackgroundColor(Color.parseColor("#E0E0E0"))
            eveningTabBtn.setTextColor(Color.DKGRAY)
        } else {
            eveningTabBtn.setBackgroundColor(Color.parseColor("#3949AB"))
            eveningTabBtn.setTextColor(Color.WHITE)
            morningTabBtn.setBackgroundColor(Color.parseColor("#E0E0E0"))
            morningTabBtn.setTextColor(Color.DKGRAY)
        }

        val photos = getSavedPhotos(currentEditingSlot)
        val usedSet = getUsedPhotoNames(currentEditingSlot)
        val remainingCount = (photos.size - usedSet.size).coerceAtLeast(0)

        statusText.text =
            "Đang quản lý: KHO ẢNH ${currentEditingSlot.title} (${photos.size} ảnh)"

        cycleInfoText.text = if (photos.isNotEmpty()) {
            "Vòng lặp ${currentEditingSlot.title}: Đã dùng ${usedSet.size}/${photos.size} ảnh (Còn $remainingCount ảnh chưa xuất hiện)"
        } else {
            "Kho ${currentEditingSlot.title} đang trống. Hãy bấm nút bên dưới để chọn ảnh."
        }

        replaceBtn.text = "Chọn danh sách mới cho ${currentEditingSlot.title} (Thay thế)"
        appendBtn.text = "Thêm ảnh vào kho ${currentEditingSlot.title}"
        resetCycleBtn.text = "Reset lượt Random kho ${currentEditingSlot.title}"
        clearBtn.text = "Xóa tất cả ảnh kho ${currentEditingSlot.title}"

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
                text = "[${currentEditingSlot.title}] Ảnh #${index + 1} (${file.length() / 1024} KB)\n$stateLabel"
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
        val targetSlot = getActiveTimeSlotByCurrentClock()
        var activeSlot = targetSlot
        var photos = getSavedPhotos(activeSlot)

        // Nếu kho ảnh theo khung giờ hiện tại đang trống, kiểm tra kho còn lại để dự phòng
        if (photos.isEmpty()) {
            val fallbackSlot = if (targetSlot == TimeSlot.MORNING) TimeSlot.EVENING else TimeSlot.MORNING
            val fallbackPhotos = getSavedPhotos(fallbackSlot)
            if (fallbackPhotos.isNotEmpty()) {
                activeSlot = fallbackSlot
                photos = fallbackPhotos
                Toast.makeText(
                    this,
                    "Kho ${targetSlot.title} trống, tạm lấy ảnh từ kho ${fallbackSlot.title}!",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Chưa có ảnh trong kho ${targetSlot.title}! Vui lòng mở Custom Camera thêm ảnh.",
                    Toast.LENGTH_LONG
                ).show()
                setResult(Activity.RESULT_CANCELED)
                finish()
                return
            }
        }

        try {
            // Chọn ngẫu nhiên không lặp lại trong đúng kho ảnh (Sáng hoặc Tối)
            val chosenPhoto = pickNextNonRepeatingPhoto(activeSlot, photos)

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
     * sang chuẩn JPEG thực sự vào đúng thư mục Sáng hoặc Tối.
     */
    private fun saveUriToSlotDir(slot: TimeSlot, uri: Uri, indexSuffix: Int): Boolean {
        val destFile = File(getSlotDir(slot), "photo_${System.currentTimeMillis()}_$indexSuffix.jpg")
        return try {
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

                destFile.outputStream().use { output ->
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
                }
                true
            } else {
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
                clearAllSavedImages(currentEditingSlot)
            }

            var savedCount = 0
            val clipData = data.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    val uri = clipData.getItemAt(i).uri
                    if (saveUriToSlotDir(currentEditingSlot, uri, i)) {
                        savedCount++
                    }
                }
            } else {
                data.data?.let { uri ->
                    if (saveUriToSlotDir(currentEditingSlot, uri, 0)) {
                        savedCount++
                    }
                }
            }

            refreshPhotoListUI()
            Toast.makeText(
                this,
                "Đã lưu $savedCount ảnh vào kho ${currentEditingSlot.title} (Tổng: ${getSavedPhotos(currentEditingSlot).size} ảnh)!",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}
