package com.peru.expresdm.ui.routes

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.peru.expresdm.R
import com.peru.expresdm.data.Journey
import com.peru.expresdm.data.JourneyLeg
import com.peru.expresdm.data.RidePoint
import com.peru.expresdm.databinding.ItemJourneyBinding
import com.peru.expresdm.databinding.ItemJourneyCardBinding
import com.peru.expresdm.databinding.ItemJourneyStepBinding
import com.peru.expresdm.ui.RouteAccent
import com.peru.expresdm.ui.RouteImages
import com.peru.expresdm.ui.formatDistance
import com.peru.expresdm.ui.themeColor
import java.util.Locale

/**
 * Las formas de llegar al lugar buscado.
 *
 * Con la hoja en reposo son un carrusel de tarjetas compactas (qué micro, cuánto
 * se tarda, cuánto se camina y cuánto cuesta) para comparar de un vistazo sin
 * tapar el mapa; al subirla, la lista con las instrucciones paso a paso.
 */
class JourneyAdapter(
    private val onJourneyClick: (Journey) -> Unit
) : ListAdapter<Journey, RecyclerView.ViewHolder>(DIFF) {

    /** `true`: tarjetas de carrusel; `false`: lista con las instrucciones. */
    var carousel: Boolean = false
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /** El viaje dibujado en el mapa, por los micros que usa; se marca con un borde. */
    var selectedKey: List<String>? = null
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    override fun getItemViewType(position: Int): Int = if (carousel) TYPE_CARD else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_CARD) {
            CardViewHolder(ItemJourneyCardBinding.inflate(inflater, parent, false))
        } else {
            JourneyViewHolder(ItemJourneyBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is CardViewHolder -> holder.bind(getItem(position))
            is JourneyViewHolder -> holder.bind(getItem(position))
        }
    }

    /** Tarjeta del carrusel: lo justo para elegir entre una opción y otra. */
    inner class CardViewHolder(
        private val binding: ItemJourneyCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(journey: Journey) {
            val context = binding.root.context
            val rides = journey.rides
            val first = rides.firstOrNull()

            // El color y la foto son los del primer micro; andando, el de la marca.
            binding.journeyCard.setCardBackgroundColor(accentOf(context, first))
            RouteImages.show(
                binding.journeyImage, first?.route?.imageUrl, first?.route?.let { it.vehicleName.ifBlank { it.name } }.orEmpty()
            )

            fillChain(binding.journeyChain, rides)

            binding.journeyTime.text = context.getString(R.string.duration_minutes, journey.totalMinutes)
            binding.journeyTitle.text = titleOf(context, journey)
            binding.journeyBoard.text = if (first == null) {
                context.getString(R.string.journey_card_walk_detail)
            } else {
                context.getString(R.string.journey_card_board, pointLabel(context, first.fromKind, first.from.name, Role.BOARD))
            }
            binding.journeyWalk.text =
                context.getString(R.string.journey_total_walk, context.formatDistance(journey.walkMeters))
            binding.journeyFare.isVisible = first != null
            binding.journeyFare.text = String.format(Locale.US, context.getString(R.string.fare_format), journey.fare)

            binding.journeyCard.strokeWidth = if (journey.key == selectedKey) dp(3) else 0
            binding.journeyCard.contentDescription =
                listOf(binding.journeyTitle.text, binding.journeyTime.text, stepsText(context, journey)).joinToString(". ")
            binding.journeyCard.setOnClickListener { onJourneyClick(journey) }
        }

        private fun dp(value: Int) = (value * binding.root.resources.displayMetrics.density).toInt()
    }

    /**
     * Fila de la lista: la misma cabecera de color que la tarjeta del carrusel
     * y, debajo, el viaje paso a paso.
     */
    inner class JourneyViewHolder(
        private val binding: ItemJourneyBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(journey: Journey) {
            val context = binding.root.context

            val first = journey.rides.firstOrNull()
            binding.journeyHeader.setBackgroundColor(accentOf(context, first))
            // La foto del primer micro va en la cabecera; con ella, la cabecera es más alta para que se vea.
            val headerPhoto = first?.route?.imageUrl?.takeIf { it.startsWith("https://") }
            binding.journeyHeader.minimumHeight = if (headerPhoto != null) dp(150) else 0
            RouteImages.show(
                binding.journeyHeaderImage,
                headerPhoto,
                first?.route?.let { it.vehicleName.ifBlank { it.name } }.orEmpty()
            )
            fillChain(binding.journeyChain, journey.rides)
            binding.journeyTitle.text = titleOf(context, journey)
            binding.journeyTime.text = context.getString(R.string.duration_minutes, journey.totalMinutes)
            binding.journeyWalk.text =
                context.getString(R.string.journey_total_walk, context.formatDistance(journey.walkMeters))
            binding.journeyFare.isVisible = !journey.walkingOnly
            binding.journeyFare.text =
                String.format(Locale.US, context.getString(R.string.fare_format), journey.fare)

            binding.journeyTransfers.isVisible = journey.transfers > 0
            if (journey.transfers > 0) {
                binding.journeyTransfers.text = context.resources.getQuantityString(
                    R.plurals.journey_transfers, journey.transfers, journey.transfers
                )
            }

            buildSteps(journey)
            binding.journeyPhotos.removeAllViews()
            // Abajo, solo las fotos de los micros del transbordo: la del primero ya está en la cabecera.
            journey.rides.drop(1).filter { !it.route.imageUrl.isNullOrBlank() }.forEach { ride ->
                val image = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120))
                        .apply { bottomMargin = dp(10) }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                binding.journeyPhotos.addView(image)
                RouteImages.show(image, ride.route.imageUrl, ride.route.vehicleName.ifBlank { ride.route.name })
            }
            binding.journeyCard.strokeWidth = dp(if (journey.key == selectedKey) 2 else 1)
            binding.journeyCard.strokeColor = context.themeColor(
                if (journey.key == selectedKey) com.google.android.material.R.attr.colorPrimary
                else com.google.android.material.R.attr.colorOutline
            )
            binding.journeyCard.contentDescription =
                listOf(binding.journeyTitle.text, binding.journeyTime.text, stepsText(context, journey)).joinToString(". ")
            binding.journeyCard.setOnClickListener { onJourneyClick(journey) }
        }

        /** Los pasos, unidos por una línea: caminar, subir al micro, bajar… y llegar. */
        private fun buildSteps(journey: Journey) {
            val context = binding.root.context
            val rides = journey.rides
            binding.journeySteps.removeAllViews()

            journey.legs.forEach { leg ->
                when (leg) {
                    is JourneyLeg.Walk -> addStep(
                        title = context.getString(R.string.journey_walk_to_stop, context.formatDistance(leg.meters)),
                        minutes = leg.minutes
                    ).stepIcon.setImageResource(R.drawable.ic_walk)

                    is JourneyLeg.Ride -> {
                        val accent = accentOf(context, leg)
                        val boardRole = if (leg === rides.first()) Role.BOARD else Role.TRANSFER
                        val exitRole = if (leg === rides.last()) Role.EXIT else Role.TRANSFER
                        addStep(
                            title = context.getString(
                                R.string.journey_step_ride, leg.route.vehicleName.ifBlank { leg.route.name }
                            ),
                            detail = context.getString(
                                R.string.journey_card_board, pointLabel(context, leg.fromKind, leg.from.name, boardRole)
                            ),
                            minutes = leg.minutes
                        ).apply {
                            stepBadge.backgroundTintList = ColorStateList.valueOf(accent)
                            stepIcon.isVisible = false
                            stepLetter.isVisible = true
                            stepLetter.text = leg.route.letter
                        }
                        // Dónde bajar: un punto del color del micro, más pequeño que el de subir.
                        addStep(
                            title = context.getString(
                                R.string.journey_exit, pointLabel(context, leg.toKind, leg.to.name, exitRole)
                            )
                        ).apply {
                            stepBadge.backgroundTintList = ColorStateList.valueOf(accent)
                            stepIcon.isVisible = false
                            stepBadge.layoutParams = (stepBadge.layoutParams as LinearLayout.LayoutParams).apply {
                                width = dp(12)
                                height = dp(12)
                                topMargin = dp(8)
                                bottomMargin = dp(5)
                            }
                        }
                    }
                }
            }
            addStep(title = context.getString(R.string.journey_arrive)).apply {
                stepIcon.setImageResource(R.drawable.ic_place)
                stepRail.isVisible = false
            }
        }

        /** Añade un paso con el aspecto neutro (caminar, llegar); quien llama lo ajusta si es otra cosa. */
        private fun addStep(title: String, detail: String? = null, minutes: Int? = null): ItemJourneyStepBinding {
            val context = binding.root.context
            val step = ItemJourneyStepBinding.inflate(LayoutInflater.from(context), binding.journeySteps, true)
            step.stepBadge.backgroundTintList =
                ColorStateList.valueOf(context.themeColor(com.google.android.material.R.attr.colorSecondaryContainer))
            step.stepIcon.imageTintList =
                ColorStateList.valueOf(context.themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer))
            step.stepTitle.text = title
            step.stepDetail.isVisible = detail != null
            step.stepDetail.text = detail
            step.stepTime.isVisible = minutes != null
            minutes?.let { step.stepTime.text = context.getString(R.string.duration_minutes, it) }
            return step
        }

        private fun dp(value: Int) =
            (value * binding.root.resources.displayMetrics.density).toInt()
    }

    /** El color del micro; andando, el de la marca. */
    private fun accentOf(context: Context, ride: JourneyLeg.Ride?): Int =
        ride?.let { ContextCompat.getColor(context, RouteAccent.colorFor(it.route.accentIndex)) }
            ?: context.themeColor(com.google.android.material.R.attr.colorPrimary)

    private fun titleOf(context: Context, journey: Journey): String =
        if (journey.walkingOnly) {
            context.getString(R.string.journey_card_walk)
        } else {
            journey.rides.joinToString(" + ") { it.route.vehicleName.ifBlank { it.route.name } }
        }

    /** Las letras de los micros en blanco, en el orden en que se toman; andando, la figura que camina. */
    private fun fillChain(chain: LinearLayout, rides: List<JourneyLeg.Ride>) {
        val context = chain.context
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
        chain.removeAllViews()
        if (rides.isEmpty()) {
            chain.addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_walk)
                    imageTintList = ColorStateList.valueOf(context.getColor(R.color.white))
                    layoutParams = ViewGroup.LayoutParams(dp(24), dp(24))
                    importantForAccessibility = ViewGroup.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
            )
        }
        rides.forEachIndexed { index, ride ->
            chain.addView(
                TextView(context).apply {
                    text = ride.route.letter
                    setTextColor(ContextCompat.getColor(context, RouteAccent.colorFor(ride.route.accentIndex)))
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_chip_white)
                    minWidth = dp(30)
                    setPadding(dp(8), 0, dp(8), 0)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30))
                        .apply { if (index > 0) marginStart = dp(6) }
                }
            )
        }
    }

    /** Para qué se nombra un punto del recorrido: cambia cómo se dice cuando no es una parada. */
    private enum class Role { BOARD, EXIT, TRANSFER }

    /**
     * Cómo se llama el sitio donde se sube o se baja. Una parada tiene nombre;
     * un punto cualquiera del recorrido se describe por lo que tiene cerca.
     */
    private fun pointLabel(context: Context, kind: RidePoint, stopName: String, role: Role): String = when (kind) {
        RidePoint.STOP -> stopName
        RidePoint.NEAR_STOP -> context.getString(R.string.journey_point_near, stopName)
        RidePoint.ON_ROUTE -> context.getString(
            when (role) {
                Role.BOARD -> R.string.journey_point_board
                Role.EXIT -> R.string.journey_point_exit
                Role.TRANSFER -> R.string.journey_point_transfer
            }
        )
    }

    /** Las instrucciones en una frase por tramo: dónde subir, dónde cambiar, dónde bajar. */
    private fun stepsText(context: Context, journey: Journey): String {
        if (journey.walkingOnly) {
            val walk = journey.legs.filterIsInstance<JourneyLeg.Walk>().firstOrNull()
            return context.getString(
                R.string.journey_walk_only,
                context.formatDistance(walk?.meters ?: journey.walkMeters)
            )
        }

        val rides = journey.rides
        return journey.legs.mapIndexed { index, leg ->
            val text = when (leg) {
                is JourneyLeg.Walk -> context.getString(
                    R.string.journey_walk_step, context.formatDistance(leg.meters), leg.minutes
                )
                is JourneyLeg.Ride -> context.getString(
                    R.string.journey_ride_step,
                    leg.route.vehicleName.ifBlank { leg.route.name },
                    pointLabel(context, leg.fromKind, leg.from.name, if (leg === rides.first()) Role.BOARD else Role.TRANSFER),
                    pointLabel(context, leg.toKind, leg.to.name, if (leg === rides.last()) Role.EXIT else Role.TRANSFER),
                    leg.minutes
                )
            }
            "${index + 1}. $text"
        }.joinToString("\n")
    }

    private companion object {
        const val TYPE_ROW = 0
        const val TYPE_CARD = 1

        val DIFF = object : DiffUtil.ItemCallback<Journey>() {
            override fun areItemsTheSame(old: Journey, new: Journey) = old.key == new.key

            override fun areContentsTheSame(old: Journey, new: Journey) = old == new
        }
    }
}
