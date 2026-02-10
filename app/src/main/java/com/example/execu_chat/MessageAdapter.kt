package com.example.execu_chat

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MessageAdapter : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private val items = mutableListOf<ChatMessage>()

    fun setItems(newItems: List<ChatMessage>) {
        val oldSize = items.size
        items.clear()
        items.addAll(newItems)
        //notifyDataSetChanged() BAD use specific says last resort
        when {
            //initial load
            oldSize == 0 && newItems.isNotEmpty() -> {
                notifyItemRangeInserted(0, newItems.size)
            }
            // removed items
            oldSize > 0 && newItems.isEmpty() -> {
                notifyItemRangeRemoved(0, oldSize)
            }
            else -> {
                val minSize = minOf(oldSize, newItems.size)
                if (minSize > 0) {
                    notifyItemRangeChanged(0, minSize)
                }
                when {
                    newItems.size > oldSize -> {
                        notifyItemRangeInserted(oldSize, newItems.size - oldSize)
                    }
                    newItems.size < oldSize -> {
                        notifyItemRangeRemoved(newItems.size, oldSize - newItems.size)
                    }
                }
            }
        }

    }
    fun updateItem(index: Int, newItem: ChatMessage) {
        if (index in items.indices) {
            items[index] = newItem
            notifyItemChanged(index)
        }
    }
    fun addItemAndReturnIndex(item: ChatMessage): Int {
        items.add(item)
        val idx = items.lastIndex
        notifyItemInserted(idx)
        return idx
    }

    fun addItem(item: ChatMessage) {
        items.add(item)
        notifyItemInserted(items.lastIndex)
    }
    fun removeAt(index: Int) {
        if (index in items.indices) {
            items.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.messageText)
        private val messageContainer: LinearLayout = itemView.findViewById(R.id.messageContainer)

        fun bind(message: ChatMessage) {
            messageText.text = message.text
            val lp = messageContainer.layoutParams as FrameLayout.LayoutParams
            lp.gravity = if (message.isUser) Gravity.END else Gravity.START
            messageContainer.layoutParams = lp
            if (message.isUser) {
                lp.gravity = Gravity.END
                messageContainer.setBackgroundResource(R.drawable.bg_message_user)
                messageText.setTextColor(0xFFFFFFFF.toInt())
            } else {
                lp.gravity = Gravity.START
                messageContainer.setBackgroundResource(R.drawable.bg_message_assistant)
                messageText.setTextColor(0xFF000000.toInt())
            }
            messageContainer.layoutParams = lp
        }
    }
}
