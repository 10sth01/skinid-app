package com.example.skinidchatbot2

import android.Manifest
import android.content.Context
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
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.skinidchatbot2.databinding.ActivityMainBinding
import com.example.skinidchatbot2.ml.ClassificationModel
import okhttp3.OkHttpClient
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.image.TensorImage
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.FileDescriptor

class MainActivity : AppCompatActivity() {
    private val QUESTION_MARK = "?"
    lateinit var mainActivity: ActivityMainBinding
    lateinit var imagepopup: PopupWindow
    private lateinit var messageList:ArrayList<MessageClass>
    private val USER = 0
    private val BOT = 1
    private lateinit var adapter: MessageAdapter
    private val CAMERA_REQUEST_CODE = 100
    private val GALLERY_REQUEST_CODE = 200
    private lateinit var yesButton: Button
    private lateinit var noButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainActivity = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mainActivity.root)
        messageList = ArrayList<MessageClass>()
        val linearLayoutManager = LinearLayoutManager(this)
        linearLayoutManager.orientation = LinearLayoutManager.VERTICAL
        mainActivity.messageList.layoutManager = linearLayoutManager
        adapter = MessageAdapter(this,messageList)
        adapter.setHasStableIds(true)
        mainActivity.messageList.adapter = adapter

        // starting message
        val greetingMessages = listOf(
            "Hello, I'm Skin ID! How can I assist you today?",
            "Hi, I'm Skin ID! How can I help you with your skin concerns?",
            "Hi, I'm Skin ID! How can I help you today?",
            "Hello there, I'm Skin ID! How can I help you with your skin concerns?",
            "Hi! How can I help you with your skin concerns today?"
        )

        val randomGreeting = greetingMessages.random()

        val greetingMessage = MessageClass(
            randomGreeting,
            BOT,
            System.currentTimeMillis()
        )

        // List of skin diseases the chatbot can detect
        val skinDiseases = listOf("Acne", "Eczema", "Psoriasis", "Rosacea", "Warts")

        // Message about the skin diseases
        val diseasesMessage = MessageClass(
            "I can detect a variety of skin conditions, including ${skinDiseases.joinToString(", ")}. Simply upload an image, and let's get started!",
            BOT,
            System.currentTimeMillis()
        )

        messageList.addAll(listOf(greetingMessage, diseasesMessage))
        adapter.notifyDataSetChanged()

        // send button
        mainActivity.messageView.setEndIconOnClickListener{
            val msg = mainActivity.messageBox.text.toString()
            sendMessage(msg, 0)
            Toast.makeText(this, "Message sent", Toast.LENGTH_SHORT).show()
            mainActivity.messageBox.setText("")
        }

        // upload/capture image button
        mainActivity.messageView.setStartIconOnClickListener{
            showPopup()
        }

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

    private val cameraActivityResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val imageBitmap = result.data?.extras?.get("data") as Bitmap
                val resizedBitmap = resizeBitmap(imageBitmap, 224, 224)
                sendImgMessage(imageBitmap = resizedBitmap)
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
                    sendImgMessage(imageBitmap = resizedBitmap)
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

    fun onYesButtonClick() {
        sendMessage("Yes", 0)
        toggleInputBoxVisibility(true)
    }

    fun onNoButtonClick() {
        sendMessage("No", 0)
        toggleInputBoxVisibility(true)
    }

    fun sendMessage(message:String, type:Int){

        // constants for messages types
        val typeText = 0
        val typeImage = 1

        // create default user message
        var userMessage = MessageClass(timestamp = System.currentTimeMillis())

        // checks if input is null
        if(message.isEmpty()){
            Toast.makeText(this,"Please type your message",Toast.LENGTH_SHORT).show()
        } else{
            // executes if input is not null
            if (type == typeText) {
                userMessage = com.example.skinidchatbot2.MessageClass(
                message,
                USER,
                java.lang.System.currentTimeMillis()
                )
                messageList.add(userMessage)
                adapter.notifyDataSetChanged() }
            else {
                userMessage = com.example.skinidchatbot2.MessageClass(
                message,
                USER,
                java.lang.System.currentTimeMillis()
                )
            }
        }

        // set up retrofit for network communication
        val okHttpClient = OkHttpClient()
        val retrofit = Retrofit.Builder()
            .baseUrl("https://396d-2001-4451-b33-f200-b067-b544-73d8-cddc.ngrok-free.app/webhooks/rest/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        // create MessageSender interface instance
        val messengerSender = retrofit.create(MessageSender::class.java)

        // make asynchronouse network request to send the user message
        val response = messengerSender.messageSender(userMessage)

        response.enqueue(object: Callback<ArrayList<BotResponse>>{
            override fun onResponse(
                call: Call<ArrayList<BotResponse>>,
                response: Response<ArrayList<BotResponse>>
            ) {
                // check if the response body is not null and not empty
                if(response.body() != null || response.body()?.size != 0){

                    val utter_list = listOf(
                        "Hello! How can I assist you today?",
                        "Hi there! How can I help you with your skin concerns?",
                        "Hi! How can I help you today?",
                        "Hello there! How can I help you with your skin concerns?",
                        "Hi! How can I help you with your skin concerns today?",
                        "I'm sorry, I didn't quite understand your request. Could you please rephrase or provide more details?",
                        "Apologies, but I couldn't grasp your query. Could you try wording it differently?",
                        "I'm having trouble processing your request. Can you rephrase it in a different way?",
                        "I didn't catch that. Can you please provide more context or rephrase your statement?"
                    )

                    val message = response.body()?.get(0)

                    if (message != null) {
                        // adds the bot's response to the message list
                        val containsQuestion = message.text.contains("?") && message.text !in utter_list
                        messageList.add(MessageClass(message.text, BOT, System.currentTimeMillis(), containsQuestion = containsQuestion))
                        toggleInputBoxVisibility(false)
                    }

                    adapter.notifyDataSetChanged()

                } else {
                    // handles the case where the response body is null or empty
                    val errorMessage = "Error processing response. Please try again."
                    messageList.add(MessageClass(errorMessage, BOT, System.currentTimeMillis()))
                    adapter.notifyDataSetChanged()
                }
            }

            override fun onFailure(call: Call<ArrayList<BotResponse>>, t: Throwable) {
                // handle the case of network failure
                val message = "Check your connection"
                messageList.add(MessageClass(message,BOT, System.currentTimeMillis()))
            }

        }

        )
    }

    fun sendImgMessage(imageBitmap: Bitmap) {
        val userMessage = MessageClass(sender = 2, timestamp = System.currentTimeMillis(), imageBitmap = imageBitmap, )
        messageList.add(userMessage)
        adapter.notifyDataSetChanged()

        // Classify the image to get the top 2 classes
        val classLabels = classifyImage(this,imageBitmap)

        // Send the top 2 classes to the chatbot without displaying them in the UI

        val message = "start_symptom_query " + (classLabels.joinToString(","))

        sendMessage(message, 1)

    }
}