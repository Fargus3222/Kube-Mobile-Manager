package com.example.project

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ResourceAdapter(
    private var items: List<ResourceItem>
) : RecyclerView.Adapter<ResourceAdapter.ResourceViewHolder>() {

    class ResourceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tv_resource_name)
        val tvNamespace: TextView = itemView.findViewById(R.id.tv_resource_namespace)
        val tvType: TextView = itemView.findViewById(R.id.tv_resource_type)
        val tvStatus: TextView = itemView.findViewById(R.id.tv_resource_status)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResourceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_res, parent, false)   // layout, который ты показал
        return ResourceViewHolder(view)
    }

    override fun onBindViewHolder(holder: ResourceViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name
        holder.tvNamespace.text = "namespace: ${item.namespace}"
        holder.tvType.text = item.type
        holder.tvStatus.text = item.status
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<ResourceItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
