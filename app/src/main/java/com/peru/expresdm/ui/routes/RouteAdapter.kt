package com.peru.expresdm.ui.routes

import android.annotation.SuppressLint
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
import com.peru.expresdm.databinding.ItemRouteCardBinding
import com.peru.expresdm.ui.RouteAccent
import com.peru.expresdm.ui.RouteImages
import com.peru.expresdm.ui.formatDistance
import java.util.Locale

/** Una ruta tal como la pinta su tarjeta y, si se conoce, lo cerca que pasa del usuario. */
data class RouteListItem(
    val route: BusRoute,
    val nearby: NearbyRoute? = null
)

/**
 * Listado de rutas. Usa [ListAdapter] con DiffUtil para que moverse unos
 * metros repinte solo las tarjetas que cambian.
 *
 * Tiene tres presentaciones de los mismos datos, ver [Style]: filas para
 * "Todas" y tarjetas con foto para las que pasan cerca.
 */
class RouteAdapter(
    private val onRouteClick: (BusRoute) -> Unit
) : ListAdapter<RouteListItem, RecyclerView.ViewHolder>(DIFF) {

    enum class Style {
        /** Filas sin foto: la lista de todas las rutas. */
        ROWS,

        /** Tarjetas con foto que se deslizan de lado: el inicio con la hoja en reposo. */
        CAROUSEL,

        /** Las mismas tarjetas con foto, a lo ancho y una debajo de otra: la hoja arriba. */
        CARDS
    }

    var style: Style = Style.ROWS
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            if (field == value) return
            field = value
            // Cambia el diseño de todas las tarjetas, no sus datos.
            notifyDataSetChanged()
        }

    override fun getItemViewType(position: Int): Int = style.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (Style.entries[viewType]) {
            Style.ROWS -> RouteViewHolder(ItemRouteBinding.inflate(inflater, parent, false))
            Style.CAROUSEL -> CardViewHolder(ItemRouteCardBinding.inflate(inflater, parent, false), wide = false)
            Style.CARDS -> {
                val binding = ItemRouteCardBinding.inflate(inflater, parent, false)
                // La tarjeta del carrusel, a todo el ancho y algo más alta.
                val density = parent.resources.displayMetrics.density
                binding.root.layoutParams = (binding.root.layoutParams as ViewGroup.MarginLayoutParams).apply {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = (WIDE_CARD_HEIGHT_DP * density).toInt()
                    topMargin = (WIDE_CARD_GAP_DP * density).toInt()
                    marginEnd = 0
                }
                CardViewHolder(binding, wide = true)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is CardViewHolder -> holder.bind(getItem(position))
            is RouteViewHolder -> holder.bind(getItem(position))
        }
    }

    /**
     * Tarjeta con la foto o el color de la ruta. En el carrusel lleva lo justo
     * para decidir; a lo ancho ([wide]) cabe además cuánto dura el recorrido y
     * a qué distancia queda la parada.
     */
    inner class CardViewHolder(
        private val binding: ItemRouteCardBinding,
        private val wide: Boolean
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RouteListItem) {
            val route = item.route
            val context = binding.root.context
            val accent = ContextCompat.getColor(context, RouteAccent.colorFor(route.accentIndex))

            binding.routeCard.setCardBackgroundColor(accent)
            binding.routeBadge.text = route.letter
            binding.routeBadge.setTextColor(accent)
            binding.routeWatermark.text = route.letter

            binding.routeName.text = route.vehicleName.ifBlank { route.name }
            binding.routeSubtitle.text = route.subtitle
            val fare = String.format(Locale.US, context.getString(R.string.fare_format), route.fare)
            val duration = context.getString(R.string.duration_minutes, route.durationMinutes)
            binding.routeFare.text = if (wide) context.getString(R.string.route_card_duration_fare, duration, fare) else fare
            RouteImages.show(binding.routeImage, route.imageUrl, route.vehicleName.ifBlank { route.name })

            val nearby = item.nearby
            val walkable = nearby != null && nearby.distanceMeters <= NearbyRoutes.WALKABLE_METERS
            binding.routeWalk.isVisible = nearby != null
            if (nearby != null) {
                binding.routeWalk.text = if (walkable) {
                    context.getString(R.string.duration_minutes, nearby.walkingMinutes)
                } else {
                    context.formatDistance(nearby.distanceMeters)
                }
            }
            binding.routeStop.text = when {
                // Sin ubicación no se sabe dónde subir: se dice cuánto dura el recorrido.
                nearby == null -> if (wide) null else duration
                wide -> context.getString(
                    R.string.nearby_far, context.formatDistance(nearby.distanceMeters), context.boardingName(nearby)
                )
                else -> context.boardingName(nearby)
            }
            binding.routeStop.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (nearby != null) R.drawable.ic_place_small else 0, 0, 0, 0
            )

            binding.routeCard.contentDescription = listOfNotNull(
                route.name,
                route.subtitle,
                nearby?.let {
                    context.getString(
                        R.string.nearby_walk,
                        context.formatDistance(it.distanceMeters), it.walkingMinutes, context.boardingName(it)
                    )
                }
            ).joinToString(". ")
            binding.routeCard.setOnClickListener { onRouteClick(route) }
        }
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
        const val WIDE_CARD_HEIGHT_DP = 168
        const val WIDE_CARD_GAP_DP = 12

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
