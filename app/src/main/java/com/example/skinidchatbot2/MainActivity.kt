package com.example.skinidchatbot2

import android.Manifest
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.skinidchatbot2.databinding.ActivityMainBinding
import com.example.skinidchatbot2.ml.ClassificationModel
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.image.TensorImage
import java.io.FileDescriptor

class MainActivity : AppCompatActivity() {

    lateinit var mainActivity: ActivityMainBinding

    // Popup window for image options
    lateinit var imagepopup: PopupWindow

    // Request codes for camera and gallery
    private val CAMERA_REQUEST_CODE = 100
    private val GALLERY_REQUEST_CODE = 200

    var initialPrediction: Pair<String, Float> = "" to 0.0f

    val dbSkinConditions = FirebaseDatabase.getInstance().getReference("conditions");
    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        mainActivity = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mainActivity.root)

        val linearLayoutManager = LinearLayoutManager(this)
        linearLayoutManager.orientation = LinearLayoutManager.VERTICAL

        // upload/capture image button
        mainActivity.imageBtn.setOnClickListener {
            showPopup()
        }

        // Show privacy agreement dialog if privacy agreement was rejected
        if (!isPrivacyAgreementAccepted()) {
            showPrivacyAgreementDialog()
        }
    }

    private val AGREEMENT_KEY = "privacy_agreement_accepted"

    // Check if the privacy agreement has been accepted
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
        val dialogText =
            "Welcome to Skin ID!\n" +
                    "\n" +
                    "Before you start using our skin lesion identification services, please take a moment to review our privacy agreement. Your privacy and the security of your data are our top priorities.\n" +
                    "\n" +
                    "By using Skin ID, you consent to the processing of images of skin lesions for the purpose of identification. Your information will not be shared with third parties.\n" +
                    "\n" +
                    "Thank you for choosing Skin ID!"

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

    // Resize a given bitmap to the target width and height
    private fun resizeBitmap(originalBitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        return Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true)
    }

    // Classify image using TensorFlow
    private fun classifyImage(context: Context, bitmap: Bitmap): Pair<String, Float> {
        var tensorImage = TensorImage(DataType.FLOAT32)
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
        val classNames = listOf(
            "acne", "alopecia areata", "eczema", "psoriasis",
            "rosacea", "vitiligo", "warts"
        )

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
        val popupView: View = inflater.inflate(R.layout.diagnosis_popup, null)

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
            mainActivity.questionTextView.text = "Hey there! I'm Skin ID. I can spot acne, " +
                    "alopecia areata, eczema, psoriasis, Raynaud's syndrome, rosacea, vitiligo " +
                    "and warts. Snap a pic to begin!"
            mainActivity.imageBtn.visibility = View.VISIBLE
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

    // Register activity result launcher for the camera
    private val cameraActivityResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // Get the captured image as a Bitmap
                val imageBitmap = result.data?.extras?.get("data") as Bitmap
                // Resize image to required dimensions
                val resizedBitmap = resizeBitmap(imageBitmap, 224, 224)
                // Display resized image in the ImageView
                mainActivity.imageDisplay.setImageBitmap(resizedBitmap)
                // Classify image and store the initial prediction
                initialPrediction = classifyImage(this, resizedBitmap)
                // If a prediction is made, fetch the condition data and show diagnosis popup
                if (initialPrediction.first != "None") {
                    CoroutineScope(Dispatchers.Main).launch {

                        val conditionData = fetchConditionData(initialPrediction.first);

                        if (conditionData != null) {
                            showDiagnosisPopup(initialPrediction.first, conditionData)
                            mainActivity.questionTextView.text =
                                "Hey there! I'm Skin ID. I can spot acne, " +
                                        "alopecia areata, eczema, psoriasis, Raynaud's syndrome, rosacea, vitiligo " +
                                        "and warts. Snap a pic to begin!"
                        } else {
                            mainActivity.questionTextView.text = "No skin lesion detected."
                        }

                        // Clear the ImageView after displaying the diagnosis
                        mainActivity.imageDisplay.setImageBitmap(null)

                    }
                } else {
                    mainActivity.questionTextView.text = "No skin lesion detected."
                }
                hidePopup()
            }
        }

    // Register activity result launcher for the gallery
    private val galleryActivityResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // Get the selected image URI from the gallery
                val selectedImageUri = result.data?.data
                if (selectedImageUri != null) {
                    // Open a file descriptor for the selected image
                    val parcelFileDescriptor =
                        contentResolver.openFileDescriptor(selectedImageUri, "r")
                    val fileDescriptor: FileDescriptor = parcelFileDescriptor!!.fileDescriptor
                    // Decode the file descriptor into a Bitmap
                    val image = BitmapFactory.decodeFileDescriptor(fileDescriptor)
                    parcelFileDescriptor.close()
                    // Resize the image to the required dimensions for the model
                    val resizedBitmap = resizeBitmap(image, 224, 224)
                    // Display the resized image in the ImageView
                    mainActivity.imageDisplay.setImageBitmap(resizedBitmap)
                    initialPrediction = classifyImage(this, resizedBitmap)
                    // If a prediction is made, fetch the condition data and show diagnosis popup
                    if (initialPrediction.first != "None") {
                        CoroutineScope(Dispatchers.Main).launch {

                            val conditionData = fetchConditionData(initialPrediction.first);

                            if (conditionData != null) {
                                showDiagnosisPopup(initialPrediction.first, conditionData)
                                mainActivity.questionTextView.text =
                                    "Hey there! I'm Skin ID. I can spot acne, " +
                                            "alopecia areata, eczema, psoriasis, Raynaud's syndrome, rosacea, vitiligo " +
                                            "and warts. Snap a pic to begin!"
                            } else {
                                mainActivity.questionTextView.text = "No skin lesion detected."
                            }
                            // Clear the ImageView after displaying the diagnosis
                            mainActivity.imageDisplay.setImageBitmap(null)

                        }
                    } else {
                        mainActivity.questionTextView.text = "No skin lesion detected."
                    }
                    hidePopup()
                } else {
                    Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
                }
            }
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
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted, launch gallery intent
                    val galleryIntent =
                        Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                    galleryActivityResultLauncher.launch(galleryIntent)
                } else {
                    // Permission denied, handle accordingly
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Hide the image popup window if showing
    private fun hidePopup() {
        if (::imagepopup.isInitialized && imagepopup.isShowing) {
            imagepopup.dismiss()
        }
    }

    // Show a popup window with options to capture or upload an image
    private fun showPopup() {
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val popupView: View = inflater.inflate(R.layout.imagepopup, null)

        // Set up the upload button
        val uploadButton: Button = popupView.findViewById(R.id.upload_button)
        val captureButton: Button = popupView.findViewById(R.id.capture_button)

        // Handle upload button click
        uploadButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                if ((ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED) ||
                    (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.READ_MEDIA_IMAGES
                    ) == PackageManager.PERMISSION_GRANTED)
                ) {
                    // launch gallery intent
                    val galleryIntent =
                        Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                    galleryActivityResultLauncher.launch(galleryIntent)
                } else {
                    // Request gallery permission
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.READ_MEDIA_IMAGES
                        ),
                        GALLERY_REQUEST_CODE
                    )
                    if ((ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        ) == PackageManager.PERMISSION_GRANTED) ||
                        (ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.READ_MEDIA_IMAGES
                        ) == PackageManager.PERMISSION_GRANTED)
                    ) {
                        // launch gallery intent
                        val galleryIntent =
                            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                        galleryActivityResultLauncher.launch(galleryIntent)
                    }
                }
            } else {
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    // launch gallery intent
                    val galleryIntent =
                        Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                    galleryActivityResultLauncher.launch(galleryIntent)
                } else {
                    // Request gallery permission
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                        GALLERY_REQUEST_CODE
                    )
                    if (ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        // launch gallery intent
                        val galleryIntent =
                            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                        galleryActivityResultLauncher.launch(galleryIntent)
                    }
                }
            }
        }

        // Handle capture button click
        captureButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                // launch camera intent
                val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                cameraActivityResultLauncher.launch(cameraIntent)
            } else {
                // request camera intent
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_REQUEST_CODE
                )
                if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    cameraActivityResultLauncher.launch(cameraIntent)
                }
            }
        }

        // Initialize and show the popup window
        imagepopup = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        imagepopup.animationStyle =
            com.google.android.material.R.style.Animation_Design_BottomSheetDialog

        imagepopup.showAtLocation(
            mainActivity.root,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            0,
            0
        )
    }
}
