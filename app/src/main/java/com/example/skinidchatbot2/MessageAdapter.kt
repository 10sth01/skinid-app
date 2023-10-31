package com.example.skinidchatbot2

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import android.widget.ImageView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class MessageAdapter(var context:Context,var messageList:MutableList<MessageClass>):RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val USER_LAYOUT = 0
    private val BOT_LAYOUT = 1
    private val USER_IMAGE_LAYOUT = 2

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.message_box, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {

        val currentMessage = messageList[position]
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val formattedTime = timeFormat.format(Date(currentMessage.timestamp))

        if(currentMessage.sender.equals(USER_LAYOUT)){
            holder.leftChatView.setVisibility(View.GONE)
            holder.rightChatView.setVisibility(View.VISIBLE)
            holder.rightTextView.setText(currentMessage.message)
            holder.user_time_view.text = formattedTime
        } else if(currentMessage.sender.equals(BOT_LAYOUT)){
            holder.rightChatView.setVisibility(View.GONE)
            holder.leftChatView.setVisibility(View.VISIBLE)
            holder.leftTextView.setText(currentMessage.message)
            holder.bot_time_view.text = formattedTime
        } else if(currentMessage.sender.equals(USER_IMAGE_LAYOUT)) {
            holder.leftChatView.visibility = View.GONE
            holder.rightChatView.visibility = View.VISIBLE
            holder.imageView.setImageBitmap(currentMessage.imageBitmap)
            holder.imageView.visibility = View.VISIBLE
            //holder.user_time_view.text = formattedTime
        }
    }

    override fun getItemCount(): Int {
        return messageList.size
    }

    override fun getItemViewType(position: Int): Int {
        super.getItemViewType(position)
        val view = messageList[position]
        if(view.sender.equals(USER_LAYOUT)){
            return USER_LAYOUT
        } else{
            return BOT_LAYOUT
        }
    }

    class MessageViewHolder(view:View): RecyclerView.ViewHolder(view){

        val bot_time_view = itemView.findViewById<TextView>(R.id.bot_time_tv)
        val user_time_view = itemView.findViewById<TextView>(R.id.user_time_tv)

        val leftChatView = itemView.findViewById<LinearLayout>(R.id.bot_message)
        val rightChatView = itemView.findViewById<LinearLayout>(R.id.user_message)
        val leftTextView = itemView.findViewById<TextView>(R.id.bot_message_tv)
        val rightTextView = itemView.findViewById<TextView>(R.id.user_message_tv)

        val imageView = view.findViewById<ImageView>(R.id.user_image_iv)

    }

}