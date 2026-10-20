package com.peru.expresdm.ui.routes

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.peru.expresdm.R
import com.peru.expresdm.data.BusRoute
import com.peru.expresdm.data.NearbyRoute
import com.peru.expresdm.data.NearbyRoutes
import com.peru.expresdm.databinding.ItemRouteBinding
import com.peru.expresdm.ui.RouteAccent
import com.peru.expresdm.ui.formatDistance
import java.util.Locale

/** Una ruta tal como la pinta su tarjeta y, si se conoce, lo cerca que pasa del usuario. */
data class RouteListItem(
    val route: BusRoute,
    val nearby: NearbyRoute? = null
)

/**
 * Listado de rutas, una tarjeta debajo de otra (el inicio y el perfil). Usa
 * [ListAdapter] con DiffUtil para que moverse unos metros repinte solo las
 * tarjetas que cambian.
 */
class RouteAdapter(
    private val onRouteClick: (BusRoute) -> Unit
) : ListAdapter<RouteListItem, RouteAdapter.RouteViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RouteViewHolder =
        RouteViewHolder(ItemRouteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: RouteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RouteViewHolder(
        private val binding: ItemRouteBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RouteListItem) {
            val route = item.route
            val context = binding.root.context

            binding.routeBadge.text = route.letter
            binding.routeBadge.backgroundTintList = ContextCompat.getColorStateList(
                context, RouteAccent.colorFor(route.accentIndex)
            )

            binding.routeName.text = route.name
            binding.routeSubtitle.text = route.subtitle.ifBlank { route.vehicleName }
            binding.routeDuration.text = context.getString(R.string.duration_minutes, route.durationMinutes)
            binding.routeFare.text = String.format(Locale.US, context.getString(R.string.fare_format), route.fare)

            // La franja de la caminata: "4 min caminando · 290 m · cerca de …"
            val nearby = item.nearby
            binding.nearbyRow.isVisible = nearby != null
            if (nearby != null) {
                val distance = context.formatDistance(nearby.distanceMeters)
                binding.nearbyText.text = if (nearby.distanceMeters <= NearbyRoutes.WALKABLE_METERS) {
                    context.getString(R.string.rutas_walk_line, nearby.walkingMinutes, distance, context.boardingName(nearby))
                } else {
                    context.getString(R.string.nearby_far, distance, context.boardingName(nearby))
                }
            }

            binding.routeCard.contentDescription = listOfNotNull(
                route.name,
                route.subtitle,
                binding.routeFare.text,
                binding.routeDuration.text,
                binding.nearbyText.text.takeIf { nearby != null }
            ).joinToString(". ")
            binding.routeCard.setOnClickListener { onRouteClick(route) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<RouteListItem>() {
            override fun areItemsTheSame(old: RouteListItem, new: RouteListItem) =
                old.route.id == new.route.id

            override fun areContentsTheSame(old: RouteListItem, new: RouteListItem) =
                old == new
        }
    }
}

/**
 * Por dónde se sube, en palabras: el punto de referencia si se sube en él, y si
 * no, el sitio de la calle cerca de esa referencia.
 */
internal fun Context.boardingName(nearby: NearbyRoute): String = when {
    nearby.distanceMeters <= NearbyRoutes.ON_ROUTE_METERS -> getString(R.string.nearby_here)
    nearby.boardsAtStop || nearby.nearestStop.name.isBlank() -> nearby.nearestStop.name
    else -> getString(R.string.journey_point_near, nearby.nearestStop.name)
}
