package com.example.execu_chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(
    private val onClick: (ChatThread) -> Unit,
    private val onDelete: (ChatThread) -> Unit
) : ListAdapter<ChatThread, ChatAdapter.ViewHolder>(DiffCallback) {

    object DiffCallback : DiffUtil.ItemCallback<ChatThread>() {
        override fun areItemsTheSame(old: ChatThread, new: ChatThread): Boolean = old.id == new.id
        override fun areContentsTheSame(old: ChatThread, new: ChatThread): Boolean = old == new
    }

    class ViewHolder(
        itemView: View,
        val onClick: (ChatThread) -> Unit,
        private val onDelete: (ChatThread) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.threadTitle)
        private val subtitle: TextView = itemView.findViewById(R.id.threadSubtitle)
        private val deleteBtn: ImageButton = itemView.findViewById(R.id.deleteBtn)
        private var current: ChatThread? = null

        init {
            //Click on item opens chat
            itemView.setOnClickListener {
                current?.let { onClick(it) }
            }
            deleteBtn.setOnClickListener {
                current?.let { onDelete(it) }
            }
        }

        fun bind(thread: ChatThread) {
            current = thread
            title.text = thread.title
            subtitle.text = thread.preview
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_thread, parent, false)
        return ViewHolder(v, onClick, onDelete)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
