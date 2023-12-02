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
    lateinit var imagepopup: PopupWindow
    private val CAMERA_REQUEST_CODE = 100
    private val GALLERY_REQUEST_CODE = 200
    var class_1_vote = 0
    var class_2_vote = 0
    var buttonsClicked = false
    var initialPrediction: List<String> = listOf()

    val dbSkinConditions = FirebaseDatabase.getInstance().getReference("conditions");
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainActivity = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mainActivity.root)
        val linearLayoutManager = LinearLayoutManager(this)
        linearLayoutManager.orientation = LinearLayoutManager.VERTICAL

        // upload/capture image button
        mainActivity.imageBtn.setOnClickListener{
            showPopup()
        }

        //resetPrivacyAgreementStatus() //for checking only

        // Check if the agreement has been accepted
        if (!isPrivacyAgreementAccepted()) {
            showPrivacyAgreementDialog()
        }
    }

    private val AGREEMENT_KEY = "privacy_agreement_accepted"

    private fun isPrivacyAgreementAccepted(): Boolean {
        val sharedPreferences = getPreferences(Context.MODE_PRIVATE)
        val isAccepted = sharedPreferences.getBoolean(AGREEMENT_KEY, false)
        Log.d("PrivacyAgreement", "Is Privacy Agreement Accepted: $isAccepted")
        return isAccepted
    }

    private fun markPrivacyAgreementAccepted() {
        val sharedPreferences = getPreferences(Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putBoolean(AGREEMENT_KEY, true)
        editor.apply()
    }

    //for resetting sharedPreferences to check pop up. checking purposes only. can be deleted.
    private fun resetPrivacyAgreementStatus() {
        val sharedPreferences = getPreferences(Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putBoolean("privacy_agreement_accepted", false)
        editor.apply()
    }

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


    private fun resizeBitmap(originalBitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        return Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true)
    }

    private fun classifyImage(context: Context, bitmap: Bitmap): List<String> {
        var tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(bitmap)

        val model = ClassificationModel.newInstance(context)

        // Creates inputs for reference.
        val inputFeature0 = TensorBuffer.createFixedSize(intArrayOf(1, 224, 224, 3), DataType.FLOAT32)
        inputFeature0.loadBuffer(tensorImage.buffer)

        // Runs model inference and gets result.
        val outputs = model.process(inputFeature0)
        val outputFeature0 = outputs.outputFeature0AsTensorBuffer

        // Releases model resources if no longer used.
        model.close()

        // Extract prediction result
        val predictions = outputFeature0.floatArray

        // List of class names
        val classNames = listOf("acne", "eczema", "healthy", "psoriasis", "rosacea", "warts")

        // Pair each class label with its probability
        val classProbabilities = mutableListOf<Triple<String, Float, String>>()

        for (i in predictions.indices) {
            val className = classNames.getOrNull(i) ?: "unknown"
            classProbabilities.add(Triple("Class $i", predictions[i], className))
        }

        // Sort the probabilities in descending order
        val sortedProbabilities = classProbabilities.sortedByDescending { it.second }

        // Take the top 2 probable classes
        val top2Classes = sortedProbabilities.take(2)

        val classLabels = top2Classes.map { it.third}

        return classLabels
    }

    private suspend fun fetchConditionData(conditionName: String): Condition? {
        val database = FirebaseDatabase.getInstance().getReference("conditions")
        val snapshot = database.child(conditionName).get().await()

        return if (snapshot.exists()) {
            val causes = snapshot.child("causes").children.map { Cause(it.value as String) }
            val description = snapshot.child("description").value as String
            val symptoms = snapshot.child("symptoms").children.map { Symptom(it.value as String) }
            val treatment = snapshot.child("treatment").children.map { Treatment(it.value as String) }

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

        // Show the popup
        val diagnosisPopup = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        // Close the popup
        val closeButton: Button = popupView.findViewById(R.id.closeButton)
        closeButton.setOnClickListener {
            diagnosisPopup.dismiss()
            mainActivity.questionTextView.text = "Hey there! I'm Skin ID. I can spot acne, eczema, psoriasis, rosacea, and warts. Snap a pic to begin!"
            mainActivity.imageBtn.visibility = View.VISIBLE
        }

        diagnosisPopup.animationStyle = com.google.android.material.R.style.Animation_Design_BottomSheetDialog

        diagnosisPopup.showAtLocation(
            mainActivity.root,
            Gravity.CENTER,
            0,
            0
        )
    }

    private fun finalPrediction(initialPrediction:List<String>, class_1_vote:Int, class_2_vote:Int): String {

        var finalPrediction: String

        if ("healthy" in initialPrediction) {
            if (initialPrediction.elementAt(0) == "healthy") {
                var overall_score = class_2_vote
                if (overall_score > 2) {
                    finalPrediction = initialPrediction.elementAt(1)
                } else {
                    finalPrediction = "You do not have a skin lesion"
                }
            } else {
                var overall_score = class_1_vote
                if (overall_score > 2) {
                    finalPrediction = initialPrediction.elementAt(0)
                } else {
                    finalPrediction = "You do not have a skin lesion"
                }
            }
        } else {
            if (class_1_vote > class_2_vote) {
                finalPrediction = initialPrediction.elementAt(0)
            } else if (class_2_vote > class_1_vote) {
                finalPrediction = initialPrediction.elementAt(1)
            } else {
                finalPrediction = initialPrediction.elementAt(0)
            }
        }
        return finalPrediction
    }
    private suspend fun queryUser(initialPrediction: List<String>) {
        var top_class_1 = initialPrediction.elementAt(0)
        var top_class_2 = initialPrediction.elementAt(1)
        class_1_vote = 0
        class_2_vote = 0
        var questionIndex = 1

        for (i in 0..1) {
            questionIndex = 1
            val skinCondition = initialPrediction.elementAt(i)
            if (initialPrediction.elementAt(i) == "healthy") {
                continue
            } else {

                while (questionIndex < 6) {

                    val database = FirebaseDatabase.getInstance().getReference("conditions")
                    val snapshot = database.child(initialPrediction.elementAt(i)).get().await()

                    if (questionIndex < 5) {

                        if (snapshot.exists()) {
                            val question = snapshot.child("questions").child("question$questionIndex").value
                            mainActivity.questionTextView.text = ""
                            mainActivity.questionTextView.text = question.toString()
                            mainActivity.yesButton.visibility = View.VISIBLE
                            mainActivity.noButton.visibility = View.VISIBLE
                        }
                    }

                    buttonsClicked = false
                    mainActivity.yesButton.setOnClickListener() {
                        if (!buttonsClicked) {
                            if (i == 0) {
                                class_1_vote += 1
                            } else {
                                class_2_vote += 1
                            }
                            buttonsClicked = true
                            questionIndex += 1
                            if (questionIndex < 6) {
                                if (snapshot.exists()) {
                                    val question = snapshot.child("questions").child("question$questionIndex").value
                                    mainActivity.questionTextView.text = ""
                                    mainActivity.questionTextView.text = question.toString()
                                    mainActivity.yesButton.visibility = View.VISIBLE
                                    mainActivity.noButton.visibility = View.VISIBLE
                                    buttonsClicked = false
                                }
                            }
                        }
                    }

                    mainActivity.noButton.setOnClickListener() {
                        if (!buttonsClicked) {
                            buttonsClicked = true
                            questionIndex += 1
                            if (questionIndex < 6) {
                                if (snapshot.exists()) {
                                    val question = snapshot.child("questions").child("question$questionIndex").value
                                    mainActivity.questionTextView.text = ""
                                    mainActivity.questionTextView.text = question.toString()
                                    mainActivity.yesButton.visibility = View.VISIBLE
                                    mainActivity.noButton.visibility = View.VISIBLE
                                    buttonsClicked = false
                                }
                            }
                        }
                    }
                }
                continue
            }
        }
        val conditionName = finalPrediction(initialPrediction, class_1_vote, class_2_vote)
        val conditionData = fetchConditionData(conditionName)

        if (conditionData != null) {
            showDiagnosisPopup(conditionName, conditionData)
            mainActivity.questionTextView.text = "Hey there! I'm Skin ID. I can spot acne, eczema, psoriasis, rosacea, and warts. Snap a pic to begin!"
        } else {
            mainActivity.questionTextView.text = "You do not have a skin lesion"
        }
        mainActivity.yesButton.visibility = View.GONE
        mainActivity.noButton.visibility = View.GONE

        mainActivity.imageDisplay.setImageBitmap(null)
    }

    private val cameraActivityResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val imageBitmap = result.data?.extras?.get("data") as Bitmap
                val resizedBitmap = resizeBitmap(imageBitmap, 224, 224)
                mainActivity.imageDisplay.setImageBitmap(resizedBitmap)
                initialPrediction = classifyImage(this, resizedBitmap)
                CoroutineScope(Dispatchers.Main).launch {
                    queryUser(initialPrediction)
                }
                hidePopup()
            }
        }

    private val galleryActivityResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val selectedImageUri = result.data?.data
                if (selectedImageUri != null) {
                    val parcelFileDescriptor = contentResolver.openFileDescriptor(selectedImageUri, "r")
                    val fileDescriptor: FileDescriptor = parcelFileDescriptor!!.fileDescriptor
                    val image = BitmapFactory.decodeFileDescriptor(fileDescriptor)
                    parcelFileDescriptor.close()
                    val resizedBitmap = resizeBitmap(image, 224, 224)
                    mainActivity.imageDisplay.setImageBitmap(resizedBitmap)
                    initialPrediction = classifyImage(this, resizedBitmap)
                    CoroutineScope(Dispatchers.Main).launch {
                        queryUser(initialPrediction)
                    }
                    hidePopup()
                } else {
                    Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            GALLERY_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permission granted, launch gallery intent
                    val galleryIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                    galleryActivityResultLauncher.launch(galleryIntent)
                } else {
                    // Permission denied, handle accordingly
                    Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun hidePopup() {
        if (::imagepopup.isInitialized && imagepopup.isShowing) {
            imagepopup.dismiss()
        }
    }

    private fun showPopup() {
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val popupView:View = inflater.inflate(R.layout.imagepopup, null)

        val uploadButton: Button = popupView.findViewById(R.id.upload_button)
        val captureButton: Button = popupView.findViewById(R.id.capture_button)

        uploadButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                if ((ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) ||
                    (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED)){
                    // launch gallery intent
                    val galleryIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                    galleryActivityResultLauncher.launch(galleryIntent)
                } else {
                    // Request gallery permission
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.READ_MEDIA_IMAGES), GALLERY_REQUEST_CODE)
                    if ((ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) ||
                        (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED)){
                        // launch gallery intent
                        val galleryIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                        galleryActivityResultLauncher.launch(galleryIntent)
                    }
                }
            }
            else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    // launch gallery intent
                    val galleryIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                    galleryActivityResultLauncher.launch(galleryIntent)
                } else {
                    // Request gallery permission
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), GALLERY_REQUEST_CODE)
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                        // launch gallery intent
                        val galleryIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                        galleryActivityResultLauncher.launch(galleryIntent)
                    }
                }
            }
        }

        captureButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                // launch camera intent
                val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                cameraActivityResultLauncher.launch(cameraIntent)
            } else {
                // request camera intent
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST_CODE)
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    cameraActivityResultLauncher.launch(cameraIntent)
                }
            }
        }

        imagepopup = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        imagepopup.animationStyle = com.google.android.material.R.style.Animation_Design_BottomSheetDialog

        imagepopup.showAtLocation(
            mainActivity.root,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            0,
            0
        )
    }
}
