package com.example.project

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class PodItem(
    val name: String,
    val namespace: String,
    val status: String
)

class PodAdapter(
    private var items: List<PodItem>
) : RecyclerView.Adapter<PodAdapter.PodViewHolder>() {

    class PodViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tv_pod_name)
        val tvNamespace: TextView = itemView.findViewById(R.id.tv_pod_namespace)
        val tvStatus: TextView = itemView.findViewById(R.id.tv_pod_status)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PodViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pod_on_node, parent, false)
        return PodViewHolder(view)
    }

    override fun onBindViewHolder(holder: PodViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name
        holder.tvNamespace.text = "namespace: ${item.namespace}"
        holder.tvStatus.text = item.status
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<PodItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
