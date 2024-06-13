package com.example.skinidchatbot2

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter for displaying different sections (causes, symptoms, treatment) in a popup.
 *
 * @param causes list of causes to display.
 * @param symptoms list of symptoms to display.
 * @param treatment list of treatments to display.
 */
class DiagnosisPopupAdapter(
    private val causes: List<String>,
    private val symptoms: List<String>,
    private val treatment: List<String>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        // Constants representing the different sections
        const val SECTION_CAUSES = 0
        const val SECTION_SYMPTOMS = 1
        const val SECTION_TREATMENT = 2
    }

    /**
     * Called when RecyclerView needs a new [RecyclerView.ViewHolder] of the given type to represent
     * an item.
     *
     * @param parent the parent ViewGroup into which the new View will be added.
     * @param viewType the view type of the new View.
     * @return a new ViewHolder that holds a View of the given view type.
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            SECTION_CAUSES, SECTION_SYMPTOMS, SECTION_TREATMENT -> {
                // Inflate the layout for the section
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.diagnosis_popup_section, parent, false)
                SectionViewHolder(view)
            }

            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    /**
     * Called by RecyclerView to display the data at the specified position
     *
     * @param holder the ViewHolder which should be updated to represent the contents of the item.
     * @param position the position of the item within the adapter's data set.
     */
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is SectionViewHolder -> {
                // Setup the RecyclerView for the specific section
                when (holder.itemViewType) {
                    SECTION_CAUSES -> setupRecyclerView(holder.sectionRecyclerView, causes)
                    SECTION_SYMPTOMS -> setupRecyclerView(holder.sectionRecyclerView, symptoms)
                    SECTION_TREATMENT -> setupRecyclerView(holder.sectionRecyclerView, treatment)
                }
            }
        }
    }

    /**
     * Returns the total number of items in the data held by the adapter.
     *
     * @return the total number of items in this chapter.
     */
    override fun getItemCount(): Int {
        return 3 // Number of sections (causes, symptoms, treatment)
    }

    /**
     * Returns the view type of the item at position for the purposes of view recycling.
     *
     * @param position the position of the item within the adapter's data set.
     * @return the view type of the item at position.
     */
    override fun getItemViewType(position: Int): Int {
        return position
    }

    /**
     * ViewHolder class for holding the section view
     *
     * @param itemView the view of the section.
     */
    class SectionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val sectionRecyclerView: RecyclerView = itemView.findViewById(R.id.sectionRecyclerView)
    }

    /**
     * Sets up the RecyclerView for a specific section with its respective data
     *
     * @param recyclerView the RecyclerView to set up
     * @param itemList the list of items to display in the RecyclerView.
     */
    private fun setupRecyclerView(recyclerView: RecyclerView, itemList: List<String>) {
        recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
        recyclerView.adapter = DiagnosisItemAdapter(itemList)
    }
}