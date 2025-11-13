package com.yadhuChoudhary.MyRuns3

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class ProfileActivity : AppCompatActivity() {
    private lateinit var Name: EditText
    private lateinit var Email: EditText
    private lateinit var Ph: EditText
    private lateinit var Eg: EditText
    private lateinit var Major: EditText
    private lateinit var Rgroup: RadioGroup
    private lateinit var Female: RadioButton
    private lateinit var Male: RadioButton
    private lateinit var buttonSave: Button
    private lateinit var buttonCancel: Button
    private lateinit var buttonChange: Button
    private lateinit var imageView: ImageView
    private lateinit var sharedPref: SharedPreferences
    private lateinit var picturesDir: File
    private lateinit var imageFile: File
    private lateinit var tempImageFile: File
    private lateinit var imageFileURI: Uri
    private lateinit var tempImageFileURI: Uri
    private lateinit var cameraResult: ActivityResultLauncher<Intent>
    private lateinit var requestCameraPermission: ActivityResultLauncher<String>
    private lateinit var requestStoragePermissions: ActivityResultLauncher<Array<String>>
    private lateinit var galleryResult: ActivityResultLauncher<Intent>
    private val PREFS = "MyRuns1Prefs"
    private val KEY_NAME = "name"
    private val KEY_EMAIL = "email"
    private val KEY_PHONE = "phone"
    private val KEY_CLASS = "class"
    private val KEY_MAJOR = "major"
    private val KEY_GENDER_ID = "gender_id"
    private val KEY_PHOTO_URI = "profile_image"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        supportActionBar?.title = "MyRuns4"

        buttonSave = findViewById(R.id.Save)
        buttonCancel = findViewById(R.id.Cancel)
        buttonChange = findViewById(R.id.Change)
        Name = findViewById(R.id.Your_Name)
        Email = findViewById(R.id.Your_Email)
        Ph = findViewById(R.id.Your_Ph)
        Eg = findViewById(R.id.Eg)
        Major = findViewById(R.id.Your_Major)
        Rgroup = findViewById(R.id.Radio_Group)
        Female = findViewById(R.id.Female)
        Male = findViewById(R.id.Male)
        imageView = findViewById(R.id.imageView)

        sharedPref = getSharedPreferences(PREFS, MODE_PRIVATE)

        setupFilesAndUris()
        setupActivityResultLaunchers()
        loadSavedData()

        buttonChange.setOnClickListener { showPickImageDialog() }
        buttonSave.setOnClickListener { saveAll() }
        buttonCancel.setOnClickListener { finish() }
    }

    private fun setupFilesAndUris() {
        picturesDir = File(getExternalFilesDir(null), "Pictures").apply { mkdirs() }
        imageFile = File(picturesDir, "myProfilePhoto.jpg")
        tempImageFile = File(picturesDir, "tempProfilePhoto.jpg")

        val authority = "com.yadhuChoudhary.myruns1.fileprovider"
        imageFileURI = FileProvider.getUriForFile(this, authority, imageFile)
        tempImageFileURI = FileProvider.getUriForFile(this, authority, tempImageFile)
    }

    private fun setupActivityResultLaunchers() {
        cameraResult = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val bmp = BitmapFactory.decodeFile(tempImageFile.absolutePath)
                imageView.setImageBitmap(bmp)
            } else {
                Toast.makeText(this, "No picture taken", Toast.LENGTH_SHORT).show()
            }
        }

        requestCameraPermission = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                openCamera()
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            }
        }

        requestStoragePermissions = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                openGallery()
            } else {
                Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show()
            }
        }

        galleryResult = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                val selectedImageUri: Uri? = data?.data
                if (selectedImageUri != null) {
                    imageView.setImageURI(selectedImageUri)
                    sharedPref.edit().putString(KEY_PHOTO_URI, selectedImageUri.toString()).apply()
                }
            } else {
                Toast.makeText(this, "No picture selected", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPickImageDialog() {
        val options = arrayOf("Take Photo", "Choose from Gallery")

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Pick Profile Picture")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchCameraOrRequestPermission()
                    1 -> launchGalleryOrRequestPermission()
                }
            }
            .show()
    }

    private fun launchCameraOrRequestPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            openCamera()
        }
    }

    private fun launchGalleryOrRequestPermission() {
        val permissions = getRequiredStoragePermissions()

        if (hasStoragePermissions()) {
            openGallery()
        } else {
            requestStoragePermissions.launch(permissions)
        }
    }

    private fun getRequiredStoragePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            // Android 12 and below
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasStoragePermissions(): Boolean {
        val permissions = getRequiredStoragePermissions()
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun openCamera() {
        tempImageFile.parentFile?.mkdirs()
        if (!tempImageFile.exists()) tempImageFile.createNewFile()

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, tempImageFileURI)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri("output", tempImageFileURI)
        }
        cameraResult.launch(intent)
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        galleryResult.launch(intent)
    }

    private fun loadSavedData() {
        Name.setText(sharedPref.getString(KEY_NAME, ""))
        Email.setText(sharedPref.getString(KEY_EMAIL, ""))
        Ph.setText(sharedPref.getString(KEY_PHONE, ""))
        Eg.setText(sharedPref.getString(KEY_CLASS, ""))
        Major.setText(sharedPref.getString(KEY_MAJOR, ""))

        val savedGenderId = sharedPref.getInt(KEY_GENDER_ID, -1)
        if (savedGenderId != -1) Rgroup.check(savedGenderId) else Rgroup.clearCheck()

        sharedPref.getString(KEY_PHOTO_URI, null)?.let { saved ->
            runCatching { Uri.parse(saved) }.getOrNull()?.let { imageView.setImageURI(it) }
        }
    }

    private fun saveAll() {
        val editor = sharedPref.edit()
        editor.putString(KEY_NAME, Name.text.toString())
        editor.putString(KEY_EMAIL, Email.text.toString())
        editor.putString(KEY_PHONE, Ph.text.toString())
        editor.putString(KEY_CLASS, Eg.text.toString())
        editor.putString(KEY_MAJOR, Major.text.toString())
        editor.putInt(KEY_GENDER_ID, Rgroup.checkedRadioButtonId)

        if (tempImageFile.exists() && tempImageFile.length() > 0L) {
            tempImageFile.copyTo(imageFile, overwrite = true)
            editor.putString(KEY_PHOTO_URI, imageFileURI.toString())
        }
        editor.apply()
        Toast.makeText(this, "Profile saved successfully!", Toast.LENGTH_SHORT).show()
        finish()
    }
}