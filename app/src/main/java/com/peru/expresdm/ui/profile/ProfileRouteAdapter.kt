package com.peru.expresdm.ui.profile

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.peru.expresdm.R
import com.peru.expresdm.data.BusRoute
import com.peru.expresdm.databinding.ItemPerfilRouteBinding
import com.peru.expresdm.ui.RouteAccent
import com.peru.expresdm.ui.routes.RouteListItem
import java.util.Locale

/**
 * Las rutas favoritas del perfil, como filas dentro de una sola tarjeta: la
 * insignia cuadrada con la letra, el nombre, por dónde va, el pasaje y los
 * minutos. Entre fila y fila, una línea fina.
 */
class ProfileRouteAdapter(
    private val onRouteClick: (BusRoute) -> Unit
) : ListAdapter<RouteListItem, ProfileRouteAdapter.RowHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
        RowHolder(ItemPerfilRouteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        holder.bind(getItem(position).route)
    }

    inner class RowHolder(private val binding: ItemPerfilRouteBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(route: BusRoute) {
            val context = binding.root.context
            binding.routeBadge.text = route.letter
            binding.routeBadge.backgroundTintList =
                ContextCompat.getColorStateList(context, RouteAccent.colorFor(route.accentIndex))
            binding.routeName.text = route.name
            binding.routeSubtitle.text = route.subtitle
            binding.routeFare.text = String.format(Locale.US, context.getString(R.string.fare_format), route.fare)
            binding.routeDuration.text = context.getString(R.string.duration_minutes, route.durationMinutes)
            binding.root.setOnClickListener { onRouteClick(route) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<RouteListItem>() {
            override fun areItemsTheSame(old: RouteListItem, new: RouteListItem) = old.route.id == new.route.id
            override fun areContentsTheSame(old: RouteListItem, new: RouteListItem) = old == new
        }
    }
}
