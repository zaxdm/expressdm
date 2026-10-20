package com.peru.expresdm.ui.reports

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.peru.expresdm.R
import com.peru.expresdm.data.AlertVote
import com.peru.expresdm.data.Reputation
import com.peru.expresdm.data.RoadAlert
import com.peru.expresdm.data.RouteRepository
import com.peru.expresdm.databinding.ItemAlertBinding
import com.peru.expresdm.ui.AlertStyle
import com.peru.expresdm.ui.RouteImages
import com.peru.expresdm.ui.formatDistance
import com.peru.expresdm.ui.formatTimeAgo

/** Lista de alertas vigentes, con sus votos de "sigue ahí" y "ya no está". */
class AlertAdapter(
    private val onShowOnMap: (RoadAlert) -> Unit,
    private val onVote: (RoadAlert, AlertVote) -> Unit
) : ListAdapter<AlertItem, AlertAdapter.AlertViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlertViewHolder {
        val binding = ItemAlertBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AlertViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AlertViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AlertViewHolder(
        private val binding: ItemAlertBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AlertItem) {
            val alert = item.alert
            val context = binding.root.context
            val color = ContextCompat.getColor(context, AlertStyle.color(alert.type))

            binding.alertIconBg.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(context, AlertStyle.softColor(alert.type)))
            binding.alertIcon.setImageResource(AlertStyle.icon(alert.type))
            binding.alertIcon.imageTintList = ColorStateList.valueOf(color)
            // Si la alerta es de un micro, se dice de cuál.
            val routeName = alert.routeId?.let { RouteRepository.byId(it)?.name }
            binding.alertType.text = if (routeName == null) {
                context.getString(AlertStyle.label(alert.type))
            } else {
                context.getString(R.string.alert_type_on_route, context.getString(AlertStyle.label(alert.type)), routeName)
            }

            binding.alertMeta.text = listOfNotNull(
                alert.placeName ?: context.getString(R.string.alert_unknown_place),
                context.formatTimeAgo(item.minutesAgo),
                item.distanceMeters?.let { context.getString(R.string.alert_distance, context.formatDistance(it)) },
                context.getString(R.string.alert_created_by_me).takeIf { alert.createdByMe }
            ).joinToString(" · ")

            binding.alertComment.isVisible = alert.comment != null
            binding.alertComment.text = alert.comment

            RouteImages.show(binding.alertPhoto, alert.imageUrl, context.getString(R.string.create_alert_photo))

            val author = listOfNotNull(
                alert.authorName?.takeUnless { alert.createdByMe },
                context.getString(R.string.alert_author_trusted).takeIf { alert.authorReputation == Reputation.TRUSTED }
            ).joinToString(" · ")
            binding.alertAuthor.text = author
            binding.alertAuthor.isVisible = author.isNotEmpty()

            val confirmed = alert.myVote == AlertVote.STILL_THERE
            binding.btnStillThere.text = when {
                confirmed -> context.getString(R.string.alert_confirmed_count, alert.confirmations)
                alert.confirmations > 0 -> context.getString(R.string.alert_still_there, alert.confirmations)
                else -> context.getString(R.string.alert_still_there_short)
            }
            binding.btnStillThere.isEnabled = !confirmed

            binding.btnStillThere.setOnClickListener { onVote(alert, AlertVote.STILL_THERE) }
            binding.btnGone.setOnClickListener { onVote(alert, AlertVote.GONE) }
            binding.btnShowOnMap.setOnClickListener { onShowOnMap(alert) }
            binding.root.setOnClickListener { onShowOnMap(alert) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<AlertItem>() {
            override fun areItemsTheSame(old: AlertItem, new: AlertItem) = old.alert.id == new.alert.id
            override fun areContentsTheSame(old: AlertItem, new: AlertItem) = old == new
        }
    }
}
