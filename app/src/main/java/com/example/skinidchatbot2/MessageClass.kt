package com.example.skinidchatbot2
import android.graphics.Bitmap

class MessageClass(var message: String? = "", var sender: Int = 0, val timestamp: Long, val imageBitmap: Bitmap? = null) {
}
