package com.example.skinidchatbot2

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
import androidx.core.graphics.drawable.toBitmap
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.skinidchatbot2.databinding.ActivityMainBinding
import com.example.skinidchatbot2.ml.ClassificationModel
import com.google.firebase.database.FirebaseDatabase
import com.theartofdev.edmodo.cropper.CropImage
import com.theartofdev.edmodo.cropper.CropImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream


class MainActivity : AppCompatActivity() {

    lateinit var mainActivity: ActivityMainBinding

    lateinit var imageUploadCapturePopup: PopupWindow

    private val CAMERA_REQUEST_CODE = 100
    private val GALLERY_REQUEST_CODE = 200

    var prediction: Pair<String, Float> = "" to 0.0f

    val target_height = 224
    val target_width = 224

    private val AGREEMENT_KEY = "privacy_agreement_accepted"

    var croppedUri: Uri? = null

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
    *   PRIVACY AGREEMENT
    * */

    private fun isPrivacyAgreementAccepted(): Boolean {
        val sharedPreferences = getPreferences(Context.MODE_PRIVATE)
        val isAccepted = sharedPreferences.getBoolean(AGREEMENT_KEY, false)
        Log.d("PrivacyAgreement", "Is Privacy Agreement Accepted: $isAccepted")
        return isAccepted
    }

    // Mark the privacy agreement as accepted in shared preferences
    private fun markPrivacyAgreementAccepted() {
        val sharedPreferences = getPreferences(Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putBoolean(AGREEMENT_KEY, true)
        editor.apply()
    }

    // Show the privacy agreement dialog to the user
    private fun showPrivacyAgreementDialog() {
        val dialogText = getString(R.string.privacy_agreement)
        val dialog = AlertDialog.Builder(this)
            .setMessage(dialogText)
            .setCancelable(false)
            .setPositiveButton("Accept") { _: DialogInterface, _: Int ->
                // Update SharedPreferences whether the agreement is accepted
                markPrivacyAgreementAccepted()
            }
            .create()

        dialog.show()
    }

    private fun getImageUri(inContext: Context?, inImage: Bitmap): Uri {

        val tempFile = File.createTempFile("temprentpk", ".png")
        val bytes = ByteArrayOutputStream()
        inImage.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        val bitmapData = bytes.toByteArray()

        val fileOutPut = FileOutputStream(tempFile)
        fileOutPut.write(bitmapData)
        fileOutPut.flush()
        fileOutPut.close()
        return Uri.fromFile(tempFile)
    }

    fun Context.getBitmap(uri: Uri): Bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ImageDecoder.decodeBitmap(ImageDecoder.createSource(this.contentResolver, uri))
        else MediaStore.Images.Media.getBitmap(this.contentResolver, uri)

    private fun cropBitmap(originalBitmap: Bitmap) {
        val imageUri = getImageUri(this, originalBitmap)

        CropImage.activity(imageUri)
            .setGuidelines(CropImageView.Guidelines.ON)
            .setAspectRatio(1, 1)
            .start(this)
    }

    private fun cropUri(originalUri: Uri) {
        CropImage.activity(originalUri)
            .setGuidelines(CropImageView.Guidelines.ON)
            .setAspectRatio(1, 1)
            .start(this)
    }

    private fun resizeBitmap(originalBitmap: Bitmap): Bitmap {
        return Bitmap.createScaledBitmap(originalBitmap, target_width, target_height, true)
    }

    // Classify image using TensorFlow
    private fun classifyImage(context: Context, bitmap: Bitmap): Pair<String, Float> {
        val tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(bitmap)

        val model = ClassificationModel.newInstance(context)

        // Creates inputs for reference.
        val inputFeature0 =
            TensorBuffer.createFixedSize(intArrayOf(1, 224, 224, 3), DataType.FLOAT32)
        inputFeature0.loadBuffer(tensorImage.buffer)

        // Runs model inference and gets result.
        val outputs = model.process(inputFeature0)
        val outputFeature0 = outputs.outputFeature0AsTensorBuffer

        // Releases model resources if no longer used.
        model.close()

        // Extract prediction result
        val predictions = outputFeature0.floatArray

        val confidenceThreshold = 0.9f

        // List of class names
        val classNames = resources.getStringArray(R.array.classes)

        // Pair each class label with its probability
        val categorizedPredictions = mutableListOf<Pair<String, Float>>()

        for (i in predictions.indices) {
            val className = classNames.getOrNull(i) ?: "unknown"
            val confidence = predictions[i]

            if (confidence >= confidenceThreshold) {
                categorizedPredictions.add(Pair(className, confidence))
            } else {
                categorizedPredictions.add(Pair("None", 0.0f))
            }
        }

        model.close() // Release model resources

        val orderedPredictions = categorizedPredictions.sortedByDescending { it.second }

        val firstPrediction = orderedPredictions[0]
        val className = firstPrediction.first
        val confidence = firstPrediction.second

        return Pair(className, confidence)
    }

    // Fetch data for the specified condition from the Firebase database
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

    // Build a formatted string for a list of items
    private fun <T> buildSectionText(items: List<T>, textExtractor: (T) -> String): String {
        val stringBuilder = StringBuilder()
        for (item in items) {
            stringBuilder.append(" - ${textExtractor(item)}\n")
        }
        return stringBuilder.toString()
    }

    // Show a popup with the diagnosis information
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

    private fun imageUriPreprocessor(ImageUri: Uri) {
        // Open a file descriptor for the selected image
        val parcelFileDescriptor =
            contentResolver.openFileDescriptor(ImageUri, "r")
        val fileDescriptor: FileDescriptor = parcelFileDescriptor!!.fileDescriptor
        // Decode the file descriptor into a Bitmap
        val image = BitmapFactory.decodeFileDescriptor(fileDescriptor)
        parcelFileDescriptor.close()

        cropUri(ImageUri)
    }

    private fun capturedImagePreprocessor(result: ActivityResult) {
        if (result.resultCode == RESULT_OK) {

            val imageBitmap = result.data?.extras?.get("data") as Bitmap
            cropBitmap(imageBitmap)
        }
    }

    private fun displayPrediction(prediction: Pair<String, Float>) {
        if (prediction.first != "None") {
            CoroutineScope(Dispatchers.Main).launch {

                val conditionData = fetchConditionData(prediction.first)

                if (conditionData != null) {
                    showDiagnosisPopup(prediction.first, conditionData)
                    mainActivity.questionTextView.text = getString(R.string.home_greeting)
                } else {
                    mainActivity.questionTextView.text = getString(R.string.detected_false)
                }
                // Clear the ImageView after displaying the diagnosis
                mainActivity.imageDisplay.setImageBitmap(null)

            }
        } else {
            mainActivity.questionTextView.text = getString(R.string.detected_false)
        }
    }

    // Hide the image popup window if showing
    private fun hidePopup() {
        if (::imageUploadCapturePopup.isInitialized && imageUploadCapturePopup.isShowing) {
            imageUploadCapturePopup.dismiss()
        }
    }

    // Register activity result launcher for the camera
    private val cameraActivityResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            capturedImagePreprocessor(result)
        }

    // Register activity result launcher for the gallery
    private val galleryActivityResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->

            if (result.resultCode == RESULT_OK) {

                // Get the selected image URI from the gallery
                val selectedImageUri = result.data?.data

                if (selectedImageUri != null) {
                    imageUriPreprocessor(selectedImageUri)
                    displayPrediction(prediction)
                    hidePopup()
                } else {
                    Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
                }
            }
        }

    private fun launchGallery() {
        val galleryIntent =
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        galleryActivityResultLauncher.launch(galleryIntent)
    }

    private fun launchCamera() {
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        cameraActivityResultLauncher.launch(cameraIntent)
    }

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

    // Show a popup window with options to capture or upload an image
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

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CropImage.CROP_IMAGE_ACTIVITY_REQUEST_CODE) {
            val result = CropImage.getActivityResult(data)
            if (resultCode == RESULT_OK) {
                val resultBitmap = getBitmap(result.uri)
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

}
