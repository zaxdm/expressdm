package com.peru.expresdm.social

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.peru.expresdm.R

class SocialAdapter(private var items: List<SocialPost>) : RecyclerView.Adapter<SocialAdapter.VH>() {
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val user: TextView = v.findViewById(R.id.postUser)
        val content: TextView = v.findViewById(R.id.postContent)
        val time: TextView = v.findViewById(R.id.postTime)
        val image: ImageView = v.findViewById(R.id.postImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_social_post, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.user.text = item.user
        holder.content.text = item.content
        holder.time.text = item.time
        if (item.imageRes != null) {
            holder.image.visibility = View.VISIBLE
            holder.image.setImageResource(item.imageRes)
        } else {
            holder.image.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(list: List<SocialPost>) {
        items = list
        notifyDataSetChanged()
    }
}
