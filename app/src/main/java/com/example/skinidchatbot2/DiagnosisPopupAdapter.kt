package com.example.skinidchatbot2

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class DiagnosisPopupAdapter(
    private val causes: List<String>,
    private val symptoms: List<String>,
    private val treatment: List<String>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val SECTION_CAUSES = 0
        const val SECTION_SYMPTOMS = 1
        const val SECTION_TREATMENT = 2
        // Add more sections if needed
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            SECTION_CAUSES, SECTION_SYMPTOMS, SECTION_TREATMENT -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.diagnosis_popup_section, parent, false)
                SectionViewHolder(view)
            }
            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is SectionViewHolder -> {
                when (holder.itemViewType) {
                    SECTION_CAUSES -> setupRecyclerView(holder.sectionRecyclerView, causes)
                    SECTION_SYMPTOMS -> setupRecyclerView(holder.sectionRecyclerView, symptoms)
                    SECTION_TREATMENT -> setupRecyclerView(holder.sectionRecyclerView, treatment)
                    // Handle more sections if needed
                }
            }
        }
    }

    override fun getItemCount(): Int {
        return 3 // Number of sections (causes, symptoms, treatment)
    }

    override fun getItemViewType(position: Int): Int {
        return position
    }

    class SectionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val sectionRecyclerView: RecyclerView = itemView.findViewById(R.id.sectionRecyclerView)
    }

    private fun setupRecyclerView(recyclerView: RecyclerView, itemList: List<String>) {
        recyclerView.layoutManager = LinearLayoutManager(recyclerView.context)
        recyclerView.adapter = DiagnosisItemAdapter(itemList)
    }
}