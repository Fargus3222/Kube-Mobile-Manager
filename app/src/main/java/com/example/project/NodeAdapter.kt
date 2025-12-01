package com.example.project

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class NodeAdapter(
    private var items: List<NodeItem>
) : RecyclerView.Adapter<NodeAdapter.NodeViewHolder>() {

    class NodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tv_node_name)
        val tvRoles: TextView = itemView.findViewById(R.id.tv_node_roles)
        val tvStatus: TextView = itemView.findViewById(R.id.tv_node_status)
        val tvUsage: TextView = itemView.findViewById(R.id.tv_node_usage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NodeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_node, parent, false)
        return NodeViewHolder(view)
    }

    override fun onBindViewHolder(holder: NodeViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name
        holder.tvRoles.text = "roles: ${item.roles}"
        holder.tvStatus.text = item.status
        holder.tvUsage.text = item.usage
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<NodeItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}