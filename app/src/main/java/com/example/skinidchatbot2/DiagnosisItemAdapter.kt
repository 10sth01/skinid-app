package com.example.skinidchatbot2

// Android framework imports
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

import androidx.recyclerview.widget.RecyclerView

/**
 *  Adapter class for displaying a list of diagnosis items in a RecyclerView.
 *
 *  @param itemList the list of diagnosis items to display.
 */
class DiagnosisItemAdapter(private val itemList: List<String>) : RecyclerView.Adapter<DiagnosisItemAdapter.ViewHolder>() {

    /**
     * Called when RecyclerView needs a new [ViewHolder] of the given type to represent an item.
     *
     * @param parent the parent viewGroup into which the new View will be added.
     * @param viewType the view type of the new View.
     * @return a new ViewHolder that holds a View of the given view type.
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // Inflate the layout for an individual item
        val view = LayoutInflater.from(parent.context).inflate(R.layout.layout_item, parent, false)
        return ViewHolder(view)
    }

    /**
     * Called by RecyclerView to display the data at the specified position.
     *
     * @param holder the viewHolder which should be updated to present the contents of the item.
     * @param position the position of the item within the adapter's data set.
     */
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // Blind the data to the TextView in the ViewHolder
        holder.itemTextView.text = itemList[position]
    }

    /**
     * Returns the total number of items in the data set held by the adapter.
     *
     * @return the total number of items in this adapter.
     */
    override fun getItemCount(): Int {
        return itemList.size
    }

    /**
     * ViewHolder class for holding the item view.
     *
     * @param itemView of the view of the item.
     */
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // Reference to the TextView in the item view
        val itemTextView: TextView = itemView.findViewById(R.id.infoItem)
    }
}
