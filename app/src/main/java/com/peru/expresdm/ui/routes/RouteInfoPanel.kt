package com.peru.expresdm.ui.routes

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.peru.expresdm.R
import com.peru.expresdm.ai.ModerationService
import com.peru.expresdm.ai.ModerationVerdict
import com.peru.expresdm.data.BusRoute
import com.peru.expresdm.data.FareTable
import com.peru.expresdm.data.Premium
import com.peru.expresdm.data.RouteCommunity
import com.peru.expresdm.data.RouteCommunityRepository
import com.peru.expresdm.data.SavedRoutes
import com.peru.expresdm.data.SharedLink
import com.peru.expresdm.data.net.AccountRepository
import com.peru.expresdm.data.net.ApiClient
import com.peru.expresdm.data.net.ApiFailure
import com.peru.expresdm.data.net.ApiResult
import com.peru.expresdm.databinding.DialogRateRouteBinding
import com.peru.expresdm.databinding.ItemReviewBinding
import com.peru.expresdm.databinding.ViewRouteInfoBinding
import com.peru.expresdm.ui.AlertStyle
import com.peru.expresdm.ui.RouteAccent
import com.peru.expresdm.ui.RouteImages
import com.peru.expresdm.ui.account.PremiumSheet
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * La ficha de un micro: su foto, cuándo pasa, cuánto cuesta y lo que la gente
 * dice de él.
 *
 * Es la misma en el panel de la ruta elegida en el mapa y en la pantalla propia
 * de la ficha, a la que se llega por un enlace compartido. Vive lo que la vista
 * de [fragment]: hay que crearla en `onViewCreated`.
 *
 * @param showMessage cómo enseña mensajes quien la aloja.
 * @param onShare si se da, sustituye a compartir la ficha: en el mapa se
 *   comparte el viaje, que dice además dónde se baja uno.
 */
class RouteInfoPanel(
    private val fragment: Fragment,
    private val binding: ViewRouteInfoBinding,
    private val showMessage: (String) -> Unit,
    private val onShare: ((BusRoute) -> Unit)? = null
) {

    private var route: BusRoute? = null
    private var community: RouteCommunity? = null
    private var loadJob: Job? = null

    private val context get() = binding.root.context

    init {
        adoptHostCards()
        binding.btnShare.setOnClickListener { route?.let { onShare?.invoke(it) ?: share(it) } }
        binding.btnSaveOffline.setOnClickListener { route?.let { toggleSaved(it) } }
        binding.btnRate.setOnClickListener { route?.let { askForRating(it) } }
    }

    /**
     * Pone en sus huecos de la ficha las tarjetas que trae quien la aloja
     * (caminar y aviso de llegada en el inicio; "Por dónde pasa" en los dos
     * sitios), para que todo quede en el orden del diseño: casillas, caminar,
     * aviso, compartir, paradas, tarifas y calificaciones.
     */
    private fun adoptHostCards() {
        val host = binding.root.parent as? ViewGroup ?: return
        moveInto(host.findViewById(R.id.fichaTripCards), binding.tripSlot)
        moveInto(host.findViewById(R.id.fichaStopsCard), binding.stopsSlot)
    }

    private fun moveInto(view: View?, slot: ViewGroup) {
        if (view == null || view.parent === slot) return
        (view.parent as? ViewGroup)?.removeView(view)
        slot.addView(view)
    }

    /**
     * Enseña la ficha de [next]. Con [countView] se apunta, para las
     * estadísticas, que alguien la abrió; se cuenta una vez por ruta.
     */
    fun show(next: BusRoute, countView: Boolean = true) {
        val previous = route
        if (previous == next) return
        route = next
        render(next)
        renderSaved(next)
        // La misma ruta con algún dato cambiado: sus calificaciones siguen valiendo.
        if (previous?.id == next.id) return

        community = null
        loadJob?.cancel()
        binding.communitySection.isVisible = ApiClient.isConfigured
        if (!ApiClient.isConfigured) return

        if (countView) RouteCommunityRepository.recordView(next.id)
        renderCommunity()
        loadJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
            val result = RouteCommunityRepository.load(next.id)
            if (result is ApiResult.Success) {
                community = result.data
                renderCommunity()
            }
        }
    }

    private fun render(route: BusRoute) {
        val color = ContextCompat.getColor(context, RouteAccent.colorFor(route.accentIndex))

        // La foto solo sale si alguien la subió; sin ella, la ficha va directa a los datos.
        binding.detailImageCard.setCardBackgroundColor(color)
        binding.detailImageCard.isVisible = !route.imageUrl.isNullOrBlank()
        if (binding.detailImage.tag != route.imageUrl) {
            RouteImages.show(binding.detailImage, route.imageUrl, route.vehicleName.ifBlank { route.name })
        }

        val description = listOf(route.vehicleName, route.description)
            .filter { it.isNotBlank() }.joinToString("\n")
        binding.detailDescription.text = description
        binding.detailDescription.isVisible = description.isNotEmpty()

        val noData = context.getString(R.string.ficha_no_data)
        binding.detailSchedule.text = route.schedule.ifBlank { noData }
        binding.detailDuration.text = if (route.durationMinutes > 0) {
            context.getString(R.string.duration_minutes, route.durationMinutes)
        } else {
            noData
        }
        binding.detailFare.text = if (route.fare > 0) formatFare(route.fare) else noData
        binding.detailFrequency.isVisible = route.frequencyMinutes != null
        route.frequencyMinutes?.let {
            binding.detailFrequency.text = context.getString(R.string.route_detail_frequency, it)
        }
        binding.detailAlertsWeek.isVisible = false

        // Los pasajes reducidos solo se enseñan cuando alguien los cargó en el panel.
        val fares = FareTable.of(route)
        binding.rowFareSchool.isVisible = fares.school != null
        binding.rowFareUniversity.isVisible = fares.university != null
        fares.school?.let { binding.fareSchool.text = formatFare(it) }
        fares.university?.let { binding.fareUniversity.text = formatFare(it) }
        binding.fareRegular.text = formatFare(fares.regular)
        // Sin ninguno de los dos, una sola fila que dice que aún no se sabe.
        binding.rowFareReduced.isVisible = fares.school == null && fares.university == null
    }

    private fun formatFare(amount: Double): String =
        String.format(Locale.US, context.getString(R.string.fare_format), amount)

    // --- Compartir ---

    /** Manda la ruta por la app que elija el usuario: qué micro es, cuánto cuesta y por dónde pasa. */
    private fun share(route: BusRoute) {
        val places = route.stops.joinToString(", ") { it.name }
        val text = context.getString(
            R.string.route_share_text,
            route.name,
            route.subtitle,
            formatFare(route.fare),
            route.schedule,
            places
        )
        // Con el enlace, quien tiene Subo abre esta ficha; quien no, la web.
        val link = SharedLink.forRoute(route.id)
        val message = if (link == null) text else "$text\n$link"
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
        fragment.startActivity(Intent.createChooser(intent, context.getString(R.string.route_detail_share)))
    }

    // --- Guardar sin conexión ---

    private fun toggleSaved(route: BusRoute) {
        val saved = SavedRoutes.get(context)
        when {
            saved.isSaved(route.id) -> {
                saved.remove(route.id)
                renderSaved(route)
            }

            // Sin sitio: con Premium es que llegó al tope; sin él, es lo que Premium da de más.
            !saved.hasRoom ->
                if (Premium.active) {
                    showMessage(context.getString(R.string.route_save_limit, saved.limit))
                } else {
                    PremiumSheet.show(fragment.childFragmentManager)
                }

            else -> {
                binding.btnSaveOffline.isEnabled = false
                binding.btnSaveOffline.setText(R.string.route_save_downloading)
                saved.save(route) { downloaded ->
                    // La vista pudo irse, o enseñar ya otra ruta, mientras se descargaba.
                    if (fragment.view == null) return@save
                    binding.btnSaveOffline.isEnabled = true
                    this.route?.let { renderSaved(it) }
                    showMessage(
                        context.getString(if (downloaded) R.string.route_save_done else R.string.route_save_partial)
                    )
                }
            }
        }
    }

    private fun renderSaved(route: BusRoute) {
        val saved = SavedRoutes.get(context)
        binding.btnSaveOffline.setText(
            if (saved.isSaved(route.id)) R.string.route_detail_saved else R.string.ficha_offline
        )
        binding.saveOfflineNote.text = if (Premium.active) {
            context.getString(R.string.route_save_count, saved.ids.value.size, saved.limit)
        } else {
            context.resources.getQuantityString(
                R.plurals.route_save_count_free, saved.limit, saved.limit, Premium.PREMIUM_SAVED_ROUTES
            )
        }
    }

    // --- Calificaciones ---

    private fun renderCommunity() {
        if (fragment.view == null) return
        val data = community
        val resources = context.resources

        val average = data?.ratingAverage
        binding.ratingSummary.text = if (data == null || average == null) {
            context.getString(R.string.ficha_no_ratings)
        } else {
            resources.getQuantityString(
                R.plurals.route_detail_rating_summary, data.ratingCount,
                String.format(Locale.US, "%.1f", average), data.ratingCount
            )
        }
        binding.btnRate.setText(
            if (data?.myStars == null) R.string.route_detail_rate else R.string.route_detail_rate_change
        )

        binding.reviewsContainer.removeAllViews()
        data?.reviews.orEmpty().forEach { review ->
            val item = ItemReviewBinding.inflate(fragment.layoutInflater, binding.reviewsContainer, false)
            item.reviewHeader.text =
                context.getString(R.string.route_review_header, stars(review.stars), review.userName)
            item.reviewComment.text = review.comment
            binding.reviewsContainer.addView(item.root)
        }

        // Lo más reportado de este micro en la semana, si hubo algo.
        val reports = data?.alertsThisWeek.orEmpty().filterValues { it > 0 }
        binding.detailAlertsWeek.isVisible = reports.isNotEmpty()
        if (reports.isNotEmpty()) {
            val parts = reports.entries.sortedByDescending { it.value }.joinToString(", ") { (type, count) ->
                context.getString(
                    R.string.route_detail_alert_count, count, context.getString(AlertStyle.label(type)).lowercase()
                )
            }
            binding.detailAlertsWeek.text = context.getString(R.string.route_detail_alerts_week, parts)
        }
    }

    private fun stars(count: Int): String = "★".repeat(count.coerceIn(0, 5)) + "☆".repeat((5 - count).coerceIn(0, 5))

    private fun askForRating(route: BusRoute) {
        if (!AccountRepository.isSignedIn) {
            showMessage(context.getString(R.string.route_rate_needs_account))
            return
        }
        val dialogBinding = DialogRateRouteBinding.inflate(fragment.layoutInflater)
        dialogBinding.ratingStars.rating = (community?.myStars ?: 0).toFloat()
        dialogBinding.ratingComment.setText(community?.myComment.orEmpty())

        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.route_rate_title, route.name))
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val stars = dialogBinding.ratingStars.rating.toInt()
                val comment = dialogBinding.ratingComment.text?.toString().orEmpty().trim()
                if (stars < 1) {
                    showMessage(context.getString(R.string.route_rate_needs_stars))
                } else {
                    rate(route, stars, comment)
                }
            }
            .show()
    }

    private fun rate(route: BusRoute, stars: Int, comment: String) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            // Lo escrito pasa por la misma moderación que los comentarios de las alertas.
            when (val verdict = if (comment.isEmpty()) ModerationVerdict.Allowed else ModerationService.review(comment)) {
                ModerationVerdict.Allowed -> Unit
                is ModerationVerdict.RejectedLocally -> {
                    showMessage(context.getString(R.string.moderation_local_reason, verdict.word))
                    return@launch
                }
                is ModerationVerdict.RejectedByModel -> {
                    showMessage(verdict.reason)
                    return@launch
                }
            }

            when (val result = RouteCommunityRepository.rate(route.id, stars, comment, community)) {
                is ApiResult.Success -> {
                    // Mientras se enviaba se pudo abrir otra ruta: su ficha no es esta.
                    if (this@RouteInfoPanel.route?.id == route.id) {
                        community = result.data
                        renderCommunity()
                    }
                    showMessage(context.getString(R.string.route_rate_saved))
                }
                is ApiResult.Failure -> showMessage(
                    when (result.reason) {
                        ApiFailure.UNAUTHORIZED -> context.getString(R.string.route_rate_needs_account)
                        ApiFailure.NETWORK -> context.getString(R.string.publish_failed)
                        else -> result.message ?: context.getString(R.string.publish_failed)
                    }
                )
            }
        }
    }
}
