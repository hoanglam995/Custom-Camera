package com.example.fakecamera

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    enum class TimeSlot(
        val id: String,
        val title: String,
        val folderName: String,
        val usedPhotosKey: String,
        val lastPhotoKey: String,
        val driveUrlKey: String
    ) {
        MORNING(
            id = "morning",
            title = "BUỔI SÁNG",
            folderName = "preset_photos_morning",
            usedPhotosKey = "used_photo_names_morning",
            lastPhotoKey = "last_used_photo_name_morning",
            driveUrlKey = "drive_folder_url_morning"
        ),
        EVENING(
            id = "evening",
            title = "BUỔI TỐI",
            folderName = "preset_photos_evening",
            usedPhotosKey = "used_photo_names_evening",
            lastPhotoKey = "last_used_photo_name_evening",
            driveUrlKey = "drive_folder_url_evening"
        )
    }

    private val PICK_IMAGES_REPLACE_REQUEST = 1001
    private val PICK_IMAGES_APPEND_REQUEST = 1002

    private val KEY_UPDATE_EXIF = "update_exif"
    private val KEY_MORNING_START_HOUR = "morning_start_hour"
    private val KEY_MORNING_START_MIN = "morning_start_min"
    private val KEY_EVENING_START_HOUR = "evening_start_hour"
    private val KEY_EVENING_START_MIN = "evening_start_min"

    private lateinit var prefs: SharedPreferences
    private var currentEditingSlot: TimeSlot = TimeSlot.MORNING
    private var isUpdatingDriveEditText = false

    private lateinit var activeTimeRuleText: TextView
    private lateinit var morningTimeBtn: Button
    private lateinit var eveningTimeBtn: Button
    private lateinit var morningTabBtn: Button
    private lateinit var eveningTabBtn: Button
    private lateinit var statusText: TextView
    private lateinit var cycleInfoText: TextView
    private lateinit var driveLinkInput: EditText
    private lateinit var syncDriveBtn: Button
    private lateinit var syncBothDriveBtn: Button
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

        // Giao diện quản lý
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
            setPadding(0, 0, 0, 16)
        }

        // Khu vực cài đặt mốc thời gian Sáng / Tối
        val timeConfigLabel = TextView(this).apply {
            text = "Cài đặt mốc giờ chuyển đổi Sáng / Tối (Bấm để đổi giờ):"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        }

        val timeConfigRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 20
            }
        }

        morningTimeBtn = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = 8
            }
            textSize = 12f
            setOnClickListener {
                showTimePickerForSlot(isMorning = true)
            }
        }

        eveningTimeBtn = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = 8
            }
            textSize = 12f
            setOnClickListener {
                showTimePickerForSlot(isMorning = false)
            }
        }

        timeConfigRow.addView(morningTimeBtn)
        timeConfigRow.addView(eveningTimeBtn)

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
            setPadding(0, 0, 0, 16)
        }

        updateExifCheckbox = CheckBox(this).apply {
            text = "Tự động cập nhật ngày giờ chụp hiện tại (EXIF)"
            isChecked = prefs.getBoolean(KEY_UPDATE_EXIF, true)
            setPadding(0, 0, 0, 16)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(KEY_UPDATE_EXIF, isChecked).apply()
            }
        }

        // Khu vực đồng bộ ảnh từ link Folder Google Drive
        val driveSectionTitle = TextView(this).apply {
            text = "Đồng bộ từ Google Drive Folder (Chia sẻ: Bất kỳ ai có liên kết):"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 8, 0, 6)
        }

        driveLinkInput = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8
            }
            hint = "Dán link folder Google Drive vào đây..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            textSize = 13f
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!isUpdatingDriveEditText) {
                        prefs.edit()
                            .putString(currentEditingSlot.driveUrlKey, s?.toString()?.trim() ?: "")
                            .apply()
                    }
                }
            })
        }

        syncDriveBtn = Button(this).apply {
            setBackgroundColor(Color.parseColor("#1E88E5"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8
            }
            setOnClickListener {
                val url = driveLinkInput.text.toString().trim()
                prefs.edit().putString(currentEditingSlot.driveUrlKey, url).apply()
                syncPhotosFromGoogleDrive(listOf(currentEditingSlot))
            }
        }

        syncBothDriveBtn = Button(this).apply {
            text = "Sync cả 2 kho Sáng & Tối từ Google Drive"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 20
            }
            setOnClickListener {
                val url = driveLinkInput.text.toString().trim()
                prefs.edit().putString(currentEditingSlot.driveUrlKey, url).apply()
                syncPhotosFromGoogleDrive(listOf(TimeSlot.MORNING, TimeSlot.EVENING))
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
        rootLayout.addView(timeConfigLabel)
        rootLayout.addView(timeConfigRow)
        rootLayout.addView(tabRow)
        rootLayout.addView(statusText)
        rootLayout.addView(cycleInfoText)
        rootLayout.addView(updateExifCheckbox)
        rootLayout.addView(driveSectionTitle)
        rootLayout.addView(driveLinkInput)
        rootLayout.addView(syncDriveBtn)
        rootLayout.addView(syncBothDriveBtn)
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

    private fun getMorningStartHour(): Int = prefs.getInt(KEY_MORNING_START_HOUR, 6)
    private fun getMorningStartMin(): Int = prefs.getInt(KEY_MORNING_START_MIN, 0)
    private fun getEveningStartHour(): Int = prefs.getInt(KEY_EVENING_START_HOUR, 18)
    private fun getEveningStartMin(): Int = prefs.getInt(KEY_EVENING_START_MIN, 0)

    private fun formatTimeHM(hour: Int, minute: Int): String {
        return String.format(Locale.US, "%02d:%02d", hour, minute)
    }

    private fun subtractOneMinute(hour: Int, minute: Int): Pair<Int, Int> {
        val total = (hour * 60 + minute - 1 + 1440) % 1440
        return Pair(total / 60, total % 60)
    }

    private fun getSlotTimeRangeLabel(slot: TimeSlot): String {
        val mH = getMorningStartHour()
        val mM = getMorningStartMin()
        val eH = getEveningStartHour()
        val eM = getEveningStartMin()

        return if (slot == TimeSlot.MORNING) {
            val (endH, endM) = subtractOneMinute(eH, eM)
            "${formatTimeHM(mH, mM)} - ${formatTimeHM(endH, endM)}"
        } else {
            val (endH, endM) = subtractOneMinute(mH, mM)
            "${formatTimeHM(eH, eM)} - ${formatTimeHM(endH, endM)}"
        }
    }

    private fun showTimePickerForSlot(isMorning: Boolean) {
        val currentH = if (isMorning) getMorningStartHour() else getEveningStartHour()
        val currentM = if (isMorning) getMorningStartMin() else getEveningStartMin()
        val title = if (isMorning) "Chọn giờ bắt đầu BUỔI SÁNG" else "Chọn giờ bắt đầu BUỔI TỐI"

        val dialog = TimePickerDialog(
            this,
            { _, selectedHour, selectedMinute ->
                if (isMorning) {
                    prefs.edit()
                        .putInt(KEY_MORNING_START_HOUR, selectedHour)
                        .putInt(KEY_MORNING_START_MIN, selectedMinute)
                        .apply()
                } else {
                    prefs.edit()
                        .putInt(KEY_EVENING_START_HOUR, selectedHour)
                        .putInt(KEY_EVENING_START_MIN, selectedMinute)
                        .apply()
                }
                refreshPhotoListUI()
                Toast.makeText(
                    this,
                    "Đã cập nhật mốc giờ: Sáng (${getSlotTimeRangeLabel(TimeSlot.MORNING)}) | Tối (${getSlotTimeRangeLabel(TimeSlot.EVENING)})",
                    Toast.LENGTH_SHORT
                ).show()
            },
            currentH,
            currentM,
            true
        )
        dialog.setTitle(title)
        dialog.show()
    }

    /**
     * Kiểm tra khung giờ hiện tại dựa theo mốc giờ Sáng / Tối do người dùng cài đặt
     */
    private fun getActiveTimeSlotByCurrentClock(): TimeSlot {
        val cal = Calendar.getInstance()
        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val morningStart = getMorningStartHour() * 60 + getMorningStartMin()
        val eveningStart = getEveningStartHour() * 60 + getEveningStartMin()

        val isMorning = if (morningStart < eveningStart) {
            nowMinutes in morningStart until eveningStart
        } else if (morningStart > eveningStart) {
            nowMinutes >= morningStart || nowMinutes < eveningStart
        } else {
            true
        }

        return if (isMorning) TimeSlot.MORNING else TimeSlot.EVENING
    }

    private fun getSlotDir(slot: TimeSlot): File {
        return File(filesDir, slot.folderName).apply {
            if (!exists()) mkdirs()
        }
    }

    private fun migrateLegacyPhotos() {
        val morningDir = getSlotDir(TimeSlot.MORNING)

        val legacySingleFile = File(filesDir, "preset_photo.jpg")
        if (legacySingleFile.exists()) {
            val target = File(morningDir, "photo_migrated_v1.jpg")
            legacySingleFile.copyTo(target, overwrite = true)
            legacySingleFile.delete()
        }

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
                "Chọn ảnh cho kho ${currentEditingSlot.title} (${getSlotTimeRangeLabel(currentEditingSlot)})"
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
            "Giờ hiện tại: $nowTimeStr → Chụp lúc này sẽ lấy kho ${activeSlotNow.title} (${getSlotTimeRangeLabel(activeSlotNow)})"

        morningTimeBtn.text = "Mốc giờ Sáng: ${formatTimeHM(getMorningStartHour(), getMorningStartMin())}"
        eveningTimeBtn.text = "Mốc giờ Tối: ${formatTimeHM(getEveningStartHour(), getEveningStartMin())}"

        val morningCount = getSavedPhotos(TimeSlot.MORNING).size
        val eveningCount = getSavedPhotos(TimeSlot.EVENING).size

        morningTabBtn.text = "Buổi Sáng ($morningCount)\n${getSlotTimeRangeLabel(TimeSlot.MORNING)}"
        eveningTabBtn.text = "Buổi Tối ($eveningCount)\n${getSlotTimeRangeLabel(TimeSlot.EVENING)}"

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

        isUpdatingDriveEditText = true
        driveLinkInput.setText(prefs.getString(currentEditingSlot.driveUrlKey, "") ?: "")
        driveLinkInput.hint = "Dán link folder Google Drive cho ${currentEditingSlot.title}..."
        isUpdatingDriveEditText = false

        syncDriveBtn.text = "Sync ảnh từ Google Drive cho ${currentEditingSlot.title}"

        val photos = getSavedPhotos(currentEditingSlot)
        val usedSet = getUsedPhotoNames(currentEditingSlot)
        val remainingCount = (photos.size - usedSet.size).coerceAtLeast(0)

        statusText.text =
            "Đang quản lý: KHO ẢNH ${currentEditingSlot.title} (${photos.size} ảnh)"

        cycleInfoText.text = if (photos.isNotEmpty()) {
            "Vòng lặp ${currentEditingSlot.title}: Đã dùng ${usedSet.size}/${photos.size} ảnh (Còn $remainingCount ảnh chưa xuất hiện)"
        } else {
            "Kho ${currentEditingSlot.title} đang trống. Hãy Sync từ Google Drive hoặc chọn ảnh từ máy."
        }

        replaceBtn.text = "Chọn danh sách mới từ máy cho ${currentEditingSlot.title} (Thay thế)"
        appendBtn.text = "Thêm ảnh từ máy vào kho ${currentEditingSlot.title}"
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

    /**
     * Trích xuất Folder ID từ link chia sẻ Google Drive bất kỳ
     */
    private fun extractDriveFolderId(urlOrId: String): String? {
        val trimmed = urlOrId.trim()
        if (trimmed.isEmpty()) return null

        val patterns = listOf(
            Regex("/folders/([a-zA-Z0-9_-]{15,})"),
            Regex("[?&]id=([a-zA-Z0-9_-]{15,})"),
            Regex("^([a-zA-Z0-9_-]{20,})$")
        )
        for (regex in patterns) {
            val match = regex.find(trimmed)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    /**
     * Quét danh sách File ID nằm trong Folder Google Drive được chia sẻ công khai
     */
    private fun fetchFileIdsFromPublicDriveFolder(folderId: String): List<String> {
        val foundIds = LinkedHashSet<String>()

        // Nguồn 1: Giao diện embeddedfolderview của Google Drive (chỉ chứa đúng các file con trong folder)
        val embeddedUrl = "https://drive.google.com/embeddedfolderview?id=$folderId#list"
        val embeddedHtml = downloadTextContent(embeddedUrl)
        if (embeddedHtml.isNotEmpty()) {
            val entryRegex = Regex("id=\"entry-([a-zA-Z0-9_-]{20,})\"")
            entryRegex.findAll(embeddedHtml).forEach { m ->
                val id = m.groupValues[1]
                if (id != folderId) foundIds.add(id)
            }

            val fileLinkRegex = Regex("/file/d/([a-zA-Z0-9_-]{20,})")
            fileLinkRegex.findAll(embeddedHtml).forEach { m ->
                val id = m.groupValues[1]
                if (id != folderId) foundIds.add(id)
            }
        }

        // Nguồn 2 (Dự phòng): Quét trang folder gốc nếu embeddedfolderview trống
        if (foundIds.isEmpty()) {
            val folderUrl = "https://drive.google.com/drive/folders/$folderId"
            val folderHtml = downloadTextContent(folderUrl)
            if (folderHtml.isNotEmpty()) {
                val dataIdRegex = Regex("data-id=\"([a-zA-Z0-9_-]{20,})\"")
                dataIdRegex.findAll(folderHtml).forEach { m ->
                    val id = m.groupValues[1]
                    if (id != folderId) foundIds.add(id)
                }

                if (foundIds.isEmpty()) {
                    val escapedJsonRegex = Regex("\\\\?\"([a-zA-Z0-9_-]{25,44})\\\\?\",\\s*\\[\\\\?\"$folderId\\\\?\"")
                    escapedJsonRegex.findAll(folderHtml).forEach { m ->
                        val id = m.groupValues[1]
                        if (id != folderId) foundIds.add(id)
                    }
                }
            }
        }

        return foundIds.toList()
    }

    private fun downloadTextContent(urlStr: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
                )
            }
            if (conn.responseCode in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use {
                    it.readText()
                }
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        } finally {
            conn?.disconnect()
        }
    }

    private fun downloadDriveFileToTemp(fileId: String, tempFile: File): Boolean {
        val candidateUrls = listOf(
            "https://drive.google.com/uc?export=download&id=$fileId",
            "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
        )

        for (urlStr in candidateUrls) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 20000
                    instanceFollowRedirects = true
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
                    )
                }
                if (conn.responseCode in 200..299) {
                    val contentType = conn.contentType?.lowercase(Locale.US) ?: ""
                    // Bỏ qua nếu trả về trang HTML báo lỗi / đăng nhập thay vì dữ liệu nhị phân
                    if (!contentType.contains("text/html")) {
                        conn.inputStream.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (tempFile.exists() && tempFile.length() > 100) {
                            return true
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }
        }
        return false
    }

    private fun normalizeTempFileToJpeg(rawFile: File, destFile: File): Boolean {
        return try {
            var rotationDegrees = 0f
            try {
                val exif = ExifInterface(rawFile.absolutePath)
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

            val decodedBitmap = BitmapFactory.decodeFile(rawFile.absolutePath) ?: return false

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
            destFile.exists() && destFile.length() > 0
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Đồng bộ lại toàn bộ danh sách ảnh từ các link Google Drive Folder đã nhập
     */
    private fun syncPhotosFromGoogleDrive(slotsToSync: List<TimeSlot>) {
        val validSlots = slotsToSync.mapNotNull { slot ->
            val rawUrl = prefs.getString(slot.driveUrlKey, "")?.trim() ?: ""
            val folderId = extractDriveFolderId(rawUrl)
            if (folderId != null) Pair(slot, folderId) else null
        }

        if (validSlots.isEmpty()) {
            Toast.makeText(
                this,
                "Vui lòng dán link chia sẻ Folder Google Drive hợp lệ trước khi Sync!",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val statusView = TextView(this).apply {
            text = "Đang kết nối Google Drive..."
            setPadding(48, 48, 48, 48)
            textSize = 15f
        }

        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Đang đồng bộ ảnh từ Google Drive")
            .setView(statusView)
            .setCancelable(false)
            .create()

        progressDialog.show()

        Thread {
            val summaryMessages = mutableListOf<String>()

            for ((slot, folderId) in validSlots) {
                runOnUiThread {
                    statusView.text = "Đang quét danh sách file trong folder ${slot.title}..."
                }

                val fileIds = fetchFileIdsFromPublicDriveFolder(folderId)
                if (fileIds.isEmpty()) {
                    summaryMessages.add("${slot.title}: Không tìm thấy ảnh (Kiểm tra quyền 'Bất kỳ ai có liên kết')")
                    continue
                }

                val stagingDir = File(cacheDir, "drive_sync_${slot.id}").apply {
                    if (exists()) deleteRecursively()
                    mkdirs()
                }

                var downloadedCount = 0
                fileIds.forEachIndexed { idx, fileId ->
                    runOnUiThread {
                        statusView.text =
                            "Kho ${slot.title}: Đang tải ảnh ${idx + 1}/${fileIds.size}..."
                    }
                    val rawTemp = File(cacheDir, "raw_drive_${slot.id}_$idx.tmp")
                    val normalizedTemp = File(stagingDir, "photo_${System.currentTimeMillis()}_$idx.jpg")
                    try {
                        if (downloadDriveFileToTemp(fileId, rawTemp)) {
                            if (normalizeTempFileToJpeg(rawTemp, normalizedTemp)) {
                                downloadedCount++
                            }
                        }
                    } finally {
                        if (rawTemp.exists()) rawTemp.delete()
                    }
                }

                if (downloadedCount > 0) {
                    // Xóa danh sách ảnh cũ của kho và thay bằng danh sách ảnh mới vừa sync
                    clearAllSavedImages(slot)
                    val targetSlotDir = getSlotDir(slot)
                    stagingDir.listFiles()?.forEach { syncedFile ->
                        val dest = File(targetSlotDir, syncedFile.name)
                        syncedFile.copyTo(dest, overwrite = true)
                    }
                    summaryMessages.add("${slot.title}: Đã đồng bộ $downloadedCount ảnh mới")
                } else {
                    summaryMessages.add("${slot.title}: Không tải được file ảnh hợp lệ nào")
                }

                stagingDir.deleteRecursively()
            }

            runOnUiThread {
                if (progressDialog.isShowing) {
                    progressDialog.dismiss()
                }
                refreshPhotoListUI()
                Toast.makeText(
                    this,
                    summaryMessages.joinToString("\n"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
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
     * Logic chuẩn hóa JPEG gốc từ bản v4.0
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