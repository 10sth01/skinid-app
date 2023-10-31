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
    lateinit var mainActivity: ActivityMainBinding
    lateinit var imagepopup: PopupWindow
    private lateinit var messageList:ArrayList<MessageClass>
    private val USER = 0
    private val BOT = 1
    private lateinit var adapter: MessageAdapter
    private val CAMERA_REQUEST_CODE = 100
    private val GALLERY_REQUEST_CODE = 200

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
        val classNames = listOf("Acne", "Eczema", "Healthy", "Psoriasis", "Rosacea", "Warts")

        // Pair each class label with its probability
        val classProbabilities = mutableListOf<Triple<String, Float, String>>()

        for (i in predictions.indices) {
            val className = classNames.getOrNull(i) ?: "Unknown"
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

    fun sendMessage(message:String, type:Int){

        val typeText = 0
        val typImage = 1
        var userMessage = MessageClass(timestamp = System.currentTimeMillis())

        // checks if input is null
        if(message.isEmpty()){
            Toast.makeText(this,"Please type your message",Toast.LENGTH_SHORT).show()
        }

        // executes if input is not null
        else{
            if (type == typeText) {
                userMessage = com.example.skinidchatbot2.MessageClass(
                message,
                USER,
                java.lang.System.currentTimeMillis()
                )
                messageList.add(userMessage)
                adapter.notifyDataSetChanged() }

        }
        val okHttpClient = OkHttpClient()
        val retrofit = Retrofit.Builder().baseUrl("https://b9ae-2001-4451-b07-3000-89ee-7026-e8b-bcf0.ngrok-free.app/webhooks/rest/").client(okHttpClient).addConverterFactory(GsonConverterFactory.create()).build()
        val messengerSender = retrofit.create(MessageSender::class.java)
        val response = messengerSender.messageSender(userMessage)
        response.enqueue(object: Callback<ArrayList<BotResponse>>{
            override fun onResponse(
                call: Call<ArrayList<BotResponse>>,
                response: Response<ArrayList<BotResponse>>
            ) {
                if(response.body() != null || response.body()?.size != 0){
                    val message = response.body()!![0]
                    messageList.add(MessageClass(message.text, BOT, System.currentTimeMillis()))
                    adapter.notifyDataSetChanged()
                } else {
                    val errorMessage = "Error processing response. Please try again."
                    messageList.add(MessageClass(errorMessage, BOT, System.currentTimeMillis()))
                    adapter.notifyDataSetChanged()
                }
            }

            override fun onFailure(call: Call<ArrayList<BotResponse>>, t: Throwable) {
                val message = "Check your connection"
                messageList.add(MessageClass(message,BOT, System.currentTimeMillis()))
            }

        })
    }

    fun sendImgMessage(imageBitmap: Bitmap) {
        val userMessage = MessageClass(sender = 2, timestamp = System.currentTimeMillis(), imageBitmap = imageBitmap, )
        messageList.add(userMessage)
        adapter.notifyDataSetChanged()

        // Classify the image to get the top 2 classes
        val classLabels = classifyImage(this,imageBitmap)

        // Send the top 2 classes to the chatbot without displaying them in the UI

        val message = "start_symptom_query " + (classLabels.joinToString(", "))

        sendMessage(message, 1)

    }
}