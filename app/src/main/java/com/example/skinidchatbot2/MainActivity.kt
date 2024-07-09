package com.example.skinidchatbot2

// Android Framework Imports
import android.Manifest
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager

// Third-Party Library Imports
import com.google.firebase.database.FirebaseDatabase
import com.theartofdev.edmodo.cropper.CropImage
import com.theartofdev.edmodo.cropper.CropImageView

// Kotlin Coroutines Imports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// Tensorflow Lite Imports
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

// Java Utility Imports
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream

// Project-Specific Imports
import com.example.skinidchatbot2.databinding.ActivityMainBinding
import com.example.skinidchatbot2.ml.ClassificationModel

class MainActivity : AppCompatActivity() {

    private lateinit var mainActivity: ActivityMainBinding

    // Popup window for image upload and capture options
    private lateinit var imageUploadCapturePopup: PopupWindow

    // Prediction result (class name and confidence score)
    var prediction: Pair<String, Float> = "" to 0.0f

    private companion object {
        // Request codes for permissions
        const val CAMERA_REQUEST_CODE = 100
        const val GALLERY_REQUEST_CODE = 200

        // Target dimensions for image resizing
        const val TARGET_HEIGHT = 224
        const val TARGET_WIDTH = 224

        // Key for storing privacy agreement acceptance state
        const val LOG_TAG = "PrivacyAgreement"
        const val AGREEMENT_KEY = "privacy_agreement_accepted"
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        mainActivity = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mainActivity.root)

        val linearLayoutManager = LinearLayoutManager(this)
        linearLayoutManager.orientation = LinearLayoutManager.VERTICAL

        mainActivity.cameraButton.setOnClickListener {
            showPopup()
        }

        if (!isPrivacyAgreementAccepted()) {
            showPrivacyAgreementDialog()
        }
    }

    /*
       PRIVACY AGREEMENT
    */

    private fun isPrivacyAgreementAccepted(): Boolean {
        val sharedPreferences = getPreferences(Context.MODE_PRIVATE)
        val isAccepted = sharedPreferences.getBoolean(AGREEMENT_KEY, false)
        Log.d(LOG_TAG, "Is Privacy Agreement Accepted: $isAccepted")
        return isAccepted
    }

    private fun markPrivacyAgreementAccepted() {
        val sharedPreferences = getPreferences(Context.MODE_PRIVATE)
        sharedPreferences.edit().apply {
            putBoolean(AGREEMENT_KEY, true)
            apply()
        }
    }

    private fun showPrivacyAgreementDialog() {
        val dialogText = getString(R.string.privacy_agreement)
        val dialog = AlertDialog.Builder(this)
            .setMessage(dialogText)
            .setCancelable(false)
            .setPositiveButton("Accept") { _: DialogInterface, _: Int ->
                markPrivacyAgreementAccepted()
            }
            .create()

        dialog.show()
    }

    /*
        IMAGE PROCESSING
    */

    private fun bitmapToUri(inContext: Context?, bitmap: Bitmap): Uri {
        val tempFile = File.createTempFile("temp", ".png")
        FileOutputStream(tempFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return Uri.fromFile(tempFile)
    }

    private fun Context.uriToBitmap(uri: Uri): Bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(this.contentResolver, uri))
        } else {
            MediaStore.Images.Media.getBitmap(this.contentResolver, uri)
        }

    private fun cropImage(originalBitmap: Bitmap) {
        val imageUri = bitmapToUri(this, originalBitmap)
        CropImage.activity(imageUri)
            .setGuidelines(CropImageView.Guidelines.ON)
            .setAspectRatio(1, 1)
            .start(this)
    }

    private fun cropImage(originalUri: Uri) {
        CropImage.activity(originalUri)
            .setGuidelines(CropImageView.Guidelines.ON)
            .setAspectRatio(1, 1)
            .start(this)
    }

    private fun resizeBitmap(originalBitmap: Bitmap): Bitmap {
        return Bitmap.createScaledBitmap(originalBitmap, TARGET_WIDTH, TARGET_HEIGHT, true)
    }

    private fun preprocessImageUri(imageUri: Uri) {
        contentResolver.openFileDescriptor(imageUri, "r")?.use { parcelFileDescriptor ->
            val fileDescriptor: FileDescriptor = parcelFileDescriptor!!.fileDescriptor
            val image = BitmapFactory.decodeFileDescriptor(fileDescriptor)
            cropImage(imageUri)
        }
    }

    private fun preprocessCapturedImage(result: ActivityResult) {
        if (result.resultCode == RESULT_OK) {
            (result.data?.extras?.get("data") as? Bitmap)?.let { imageBitmap ->
                cropImage(imageBitmap)
            }
        }
    }

    private fun displayPrediction(prediction: Pair<String, Float>) {
        val (condition, confidence) = prediction

        if (condition != "None") {
            CoroutineScope(Dispatchers.Main).launch {

                val conditionData = fetchConditionData(condition)

                if (conditionData != null) {
                    showDiagnosisPopup(condition, conditionData)
                    mainActivity.questionTextView.text = getString(R.string.home_greeting)
                } else {
                    mainActivity.questionTextView.text = getString(R.string.detected_false)
                }
                mainActivity.imageDisplay.setImageBitmap(null)

            }
        } else {
            mainActivity.questionTextView.text = getString(R.string.detected_false)
        }
    }

    /*
        IMAGE CLASSIFICATION
    */

    private fun classifyImage(context: Context, bitmap: Bitmap): Pair<String, Float> {
        val tensorImage = TensorImage(DataType.FLOAT32).apply { load(bitmap)}

        val model = ClassificationModel.newInstance(context)

        // Creates inputs for reference.
        val inputFeature = TensorBuffer.createFixedSize(intArrayOf(1, 224, 224, 3), DataType.FLOAT32)
        inputFeature.loadBuffer(tensorImage.buffer)

        // Runs model inference and gets result.
        val outputFeature = model.process(inputFeature).outputFeature0AsTensorBuffer

        // Extract prediction result
        val predictions = outputFeature.floatArray

        // Releases model resources if no longer used.
        model.close()

        // Load class names
        val classNames = resources.getStringArray(R.array.classes)
        val confidenceThreshold = 0.9f

        // Pair each class label with its probability
        val categorizedPredictions = predictions.mapIndexed { index, confidence ->
            val className = classNames.getOrNull(index) ?: "unknown"
            if (confidence >= confidenceThreshold) Pair(className, confidence) else Pair("None", 0.0f)
        }

        val bestPrediction = categorizedPredictions.maxByOrNull { it.second } ?: Pair("None", 0.0f)

        return bestPrediction
    }

    /*
        DATA FETCHING AND DISPLAY
    */

    private suspend fun fetchConditionData(conditionName: String): Condition? {
        val database = FirebaseDatabase.getInstance().getReference("conditions")
        val snapshot = database.child(conditionName).get().await()

        return if (snapshot.exists()) {
            val causes = snapshot.child("causes").children.map { Cause(it.value as String) }
            val description = snapshot.child("description").value as String
            val symptoms = snapshot.child("symptoms").children.map { Symptom(it.value as String) }
            val treatment =
                snapshot.child("treatment").children.map { Treatment(it.value as String) }

            Condition(causes, description, symptoms, treatment)
        } else {
            null
        }
    }

    private fun <T> buildSectionText(items: List<T>, textExtractor: (T) -> String): String {
        val stringBuilder = StringBuilder()
        for (item in items) {
            stringBuilder.append(" - ${textExtractor(item)}\n")
        }
        return stringBuilder.toString()
    }

    private fun showDiagnosisPopup(conditionName: String, condition: Condition) {
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val popupView: View = inflater.inflate(R.layout.diagnosis_popup, mainActivity.root, false)

        // Populate the popup layout with data from the Condition object
        val titleTextView: TextView = popupView.findViewById(R.id.popupTitle)
        titleTextView.text = conditionName

        val descriptionTextView: TextView = popupView.findViewById(R.id.descriptionTextView)
        descriptionTextView.text = condition.description

        // Populate Causes
        val causesTextView: TextView = popupView.findViewById(R.id.causesTextView)
        causesTextView.text = buildSectionText(condition.causes) { it.cause }

        // Populate Symptoms
        val symptomsTextView: TextView = popupView.findViewById(R.id.symptomsTextView)
        symptomsTextView.text = buildSectionText(condition.symptoms) { it.symptom }

        // Populate Treatment
        val treatmentTextView: TextView = popupView.findViewById(R.id.treatmentTextView)
        treatmentTextView.text = buildSectionText(condition.treatment) { it.treatment }

        // Show the diagnosis popup
        val diagnosisPopup = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        // Close the diagnosis popup
        val closeButton: Button = popupView.findViewById(R.id.closeButton)
        closeButton.setOnClickListener {
            diagnosisPopup.dismiss()
            mainActivity.questionTextView.text = getString(R.string.home_greeting)
            mainActivity.cameraButton.visibility = View.VISIBLE
        }

        diagnosisPopup.animationStyle =
            com.google.android.material.R.style.Animation_Design_BottomSheetDialog

        diagnosisPopup.showAtLocation(
            mainActivity.root,
            Gravity.CENTER,
            0,
            0
        )
    }

    /*
        ACTIVITY RESULT HANDLERS
    */

    // Register activity result launcher for the camera
    private val cameraActivityResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            preprocessCapturedImage(result)
        }

    // Register activity result launcher for the gallery
    private val galleryActivityResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->

            if (result.resultCode == RESULT_OK) {

                // Get the selected image URI from the gallery
                val selectedImageUri = result.data?.data

                if (selectedImageUri != null) {
                    preprocessImageUri(selectedImageUri)
                } else {
                    Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
                }
            }
        }

    /*
        GALLERY AND CAMERA LAUNCH
     */

    private fun launchGallery() {
        val galleryIntent =
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        galleryActivityResultLauncher.launch(galleryIntent)
    }

    private fun launchCamera() {
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        cameraActivityResultLauncher.launch(cameraIntent)
    }

    /*
        PERMISSION REQUEST HANDLERS
     */

    // Handle permission request results
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            GALLERY_REQUEST_CODE -> {
                if (grantResults.isNotEmpty()
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    launchGallery()
                } else {
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
                }
            }

            CAMERA_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    launchCamera()
                } else {
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
                }

            }
        }
    }

    private fun requestGalleryPermission() {

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                launchGallery()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_MEDIA_IMAGES),
                    GALLERY_REQUEST_CODE
                )
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                launchGallery()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    GALLERY_REQUEST_CODE
                )
            }
        }
    }

    private fun requestCameraPermission() {

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == CAMERA_REQUEST_CODE
        ) {
            launchCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_REQUEST_CODE
            )
        }
    }

    /*
        APP CONTROLS
     */

    private fun showPopup() {
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val popupView: View = inflater.inflate(R.layout.imagepopup, mainActivity.root, false)

        // Set up the upload button
        val uploadButton: Button = popupView.findViewById(R.id.upload_button)
        val captureButton: Button = popupView.findViewById(R.id.capture_button)

        // Handle upload button click
        uploadButton.setOnClickListener {
            requestGalleryPermission()
        }

        // Handle capture button click
        captureButton.setOnClickListener {
            requestCameraPermission()
        }

        // Initialize and show the popup window
        imageUploadCapturePopup = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        imageUploadCapturePopup.animationStyle =
            com.google.android.material.R.style.Animation_Design_BottomSheetDialog

        imageUploadCapturePopup.showAtLocation(
            mainActivity.root,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            0,
            0
        )
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CropImage.CROP_IMAGE_ACTIVITY_REQUEST_CODE) {
            val result = CropImage.getActivityResult(data)
            if (resultCode == RESULT_OK) {
                val resultBitmap = uriToBitmap(result.uri)

                mainActivity.imageDisplay.setImageBitmap(resultBitmap)

                val resizedBitmap = resizeBitmap(resultBitmap)
                val argbBitmap = resizedBitmap.copy(Bitmap.Config.ARGB_8888, true)

                prediction = classifyImage(this, resizeBitmap(argbBitmap))
                displayPrediction(prediction)
                hidePopup()

            } else if (resultCode == CropImage.CROP_IMAGE_ACTIVITY_RESULT_ERROR_CODE) {
                val error = result.error
            }
        }
    }

    // Hide the image popup window if showing
    private fun hidePopup() {
        if (::imageUploadCapturePopup.isInitialized && imageUploadCapturePopup.isShowing) {
            imageUploadCapturePopup.dismiss()
        }
    }

}
