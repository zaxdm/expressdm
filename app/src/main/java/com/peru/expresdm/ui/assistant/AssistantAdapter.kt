package com.peru.expresdm.ui.assistant

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.peru.expresdm.R
import com.peru.expresdm.data.BusRoute
import com.peru.expresdm.databinding.ItemAssistantMessageBinding
import com.peru.expresdm.ui.themeColor

/**
 * Conversación del asistente: los mensajes del usuario van a la derecha en
 * tinta, los del asistente a la izquierda en burbujas blancas.
 */
class AssistantAdapter(
    private val onRouteClick: (BusRoute) -> Unit
) : ListAdapter<ChatMessage, AssistantAdapter.MessageViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val binding = ItemAssistantMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return MessageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MessageViewHolder(
        private val binding: ItemAssistantMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(message: ChatMessage) {
            val context = binding.root.context

            binding.messageText.text = message.text
            binding.messageRow.gravity =
                if (message.fromUser) Gravity.END else Gravity.START

            // La burbuja no debe ocupar todo el ancho: deja ver de quién es.
            (binding.bubble.layoutParams as LinearLayout.LayoutParams).apply {
                width = LinearLayout.LayoutParams.WRAP_CONTENT
                marginStart = if (message.fromUser) context.dp(56) else 0
                marginEnd = if (message.fromUser) 0 else context.dp(56)
            }

            when {
                message.fromUser -> {
                    // Lo del usuario, en tinta con letra blanca (de noche, al revés).
                    binding.bubble.setCardBackgroundColor(ContextCompat.getColor(context, R.color.alertas_ink))
                    binding.messageText.setTextColor(ContextCompat.getColor(context, R.color.alertas_on_ink))
                }

                message.isError -> {
                    binding.bubble.setCardBackgroundColor(
                        context.themeColor(com.google.android.material.R.attr.colorErrorContainer)
                    )
                    binding.messageText.setTextColor(
                        context.themeColor(com.google.android.material.R.attr.colorOnErrorContainer)
                    )
                }

                else -> {
                    binding.bubble.setCardBackgroundColor(
                        context.themeColor(com.google.android.material.R.attr.colorSurface)
                    )
                    binding.messageText.setTextColor(
                        context.themeColor(com.google.android.material.R.attr.colorOnSurface)
                    )
                }
            }

            val buttons = binding.routeButtons
            buttons.removeAllViews()
            buttons.visibility = if (message.suggestedRoutes.isEmpty()) View.GONE else View.VISIBLE
            message.suggestedRoutes.forEach { route ->
                val button = LayoutInflater.from(context)
                    .inflate(R.layout.item_assistant_route, buttons, false) as MaterialButton
                button.text = context.getString(R.string.assistant_see_route, "${route.letter} · ${route.name}")
                button.setOnClickListener { onRouteClick(route) }
                buttons.addView(button)
            }
        }

        private fun android.content.Context.dp(value: Int): Int =
            (value * resources.displayMetrics.density).toInt()
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(old: ChatMessage, new: ChatMessage) = old.id == new.id
            override fun areContentsTheSame(old: ChatMessage, new: ChatMessage) = old == new
        }
    }
}
