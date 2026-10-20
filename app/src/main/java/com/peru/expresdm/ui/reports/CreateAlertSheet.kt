package com.peru.expresdm.ui.reports

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.peru.expresdm.R
import com.peru.expresdm.ai.ModerationService
import com.peru.expresdm.ai.ModerationVerdict
import com.peru.expresdm.data.AlertRepository
import com.peru.expresdm.data.AlertType
import com.peru.expresdm.data.AppUpdates
import com.peru.expresdm.data.PlaceSearch
import com.peru.expresdm.data.PublishResult
import com.peru.expresdm.data.RoadAlert
import com.peru.expresdm.data.RouteRepository
import com.peru.expresdm.data.net.AccountRepository
import com.peru.expresdm.data.net.ApiClient
import com.peru.expresdm.data.net.ApiResult
import com.peru.expresdm.data.net.ImageUploader
import com.peru.expresdm.databinding.AlertasItemTypeTileBinding
import com.peru.expresdm.databinding.SheetCreateAlertBinding
import com.peru.expresdm.ui.AlertStyle
import com.peru.expresdm.ui.currentLocation
import com.peru.expresdm.ui.hasAnyLocationPermission
import com.peru.expresdm.ui.isLocationEnabled
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Hoja para publicar una alerta.
 *
 * La alerta se marca donde está el usuario: quien avisa de un choque o de un
 * atasco lo está viendo. El comentario, si lo hay, pasa por la moderación
 * antes de publicarse.
 */
class CreateAlertSheet : BottomSheetDialogFragment() {

    private enum class LocationProblem { NO_PERMISSION, SERVICES_OFF, NO_FIX }

    private var _binding: SheetCreateAlertBinding? = null
    private val binding get() = _binding!!

    private var selectedType: AlertType? = null

    /** La casilla de cada tipo, para marcar la elegida. */
    private val typeTiles = mutableMapOf<AlertType, MaterialCardView>()
    private var location: Location? = null
    private var placeName: String? = null
    private var locationProblem: LocationProblem? = null
    private var locatingJob: Job? = null
    private var publishing = false

    /** Micro del que habla la alerta, cuando el tipo lo pide. */
    private var selectedRouteId: String? = null
    private var photo: Uri? = null

    /** El selector de fotos de Android: no pide permiso para leer la galería. */
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            photo = uri
            renderPhoto()
        }
    }

    /** Dónde va a dejar la cámara la foto que se está tomando. */
    private var cameraTarget: Uri? = null

    /** La cámara del teléfono: la foto se toma desde su app, así que no hace falta permiso. */
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val target = cameraTarget
        cameraTarget = null
        if (taken && target != null) {
            photo = target
            renderPhoto()
        }
    }

    private val requestLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { locate() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetCreateAlertBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        selectedType = savedInstanceState?.getString(STATE_TYPE)
            ?.let { name -> AlertType.entries.firstOrNull { it.name == name } }

        buildTypeGrid()
        renderPublishLabel()
        buildRouteChoices()
        renderRouteChoice()
        // Las fotos se revisan en el servidor: sin él o sin sesión no hay a quién mandarlas.
        binding.photoRow.isVisible = ApiClient.isConfigured && AccountRepository.isSignedIn
        // La app de la cámara puede hacer que el sistema cierre esta mientras se toma la foto.
        cameraTarget = savedInstanceState?.getString(STATE_CAMERA_TARGET)?.let(Uri::parse)
        binding.btnAddPhoto.setOnClickListener { askPhotoSource() }
        binding.btnRemovePhoto.setOnClickListener {
            photo = null
            renderPhoto()
        }
        renderPhoto()
        binding.btnEnableLocation.setOnClickListener { onLocationAction() }
        binding.btnClose.setOnClickListener { dismiss() }
        binding.shareNote.setText(
            if (AlertRepository.isShared) R.string.reports_shared_note else R.string.reports_local_note
        )
        binding.btnPublish.setOnClickListener { publish() }
        binding.commentInput.doAfterTextChanged { _binding?.commentLayout?.error = null }
    }

    /** Fondo arena, como en la maqueta: así las casillas blancas resaltan. */
    override fun getTheme(): Int = R.style.ThemeOverlay_Alertas_BottomSheetDialog

    override fun onStart() {
        super.onStart()
        // El teclado empuja el botón hacia arriba en lugar de taparlo.
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        BottomSheetBehavior.from(sheet).apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onResume() {
        super.onResume()
        // También al volver de los ajustes, por si el usuario activó la ubicación.
        if (location == null && locatingJob?.isActive != true) locate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TYPE, selectedType?.name)
        outState.putString(STATE_CAMERA_TARGET, cameraTarget?.toString())
    }

    /** Casillas de cada tipo, dos por fila; la elegida lleva el borde grueso en tinta. */
    private fun buildTypeGrid() {
        val context = requireContext()
        typeTiles.clear()
        binding.typeGrid.removeAllViews()
        val gap = resources.getDimensionPixelSize(R.dimen.alertas_tile_gap)

        AlertType.entries.chunked(2).forEachIndexed { index, pair ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (index > 0) topMargin = gap }
            }
            pair.forEachIndexed { column, type ->
                val tile = AlertasItemTypeTileBinding.inflate(layoutInflater, row, false)
                tile.tileIcon.setImageResource(AlertStyle.icon(type))
                tile.tileIcon.imageTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(context, AlertStyle.color(type)))
                tile.tileIcon.backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(context, AlertStyle.softColor(type)))
                tile.tileLabel.setText(AlertStyle.label(type))
                if (column > 0) (tile.root.layoutParams as LinearLayout.LayoutParams).marginStart = gap
                tile.root.setOnClickListener { selectType(type) }
                row.addView(tile.root)
                typeTiles[type] = tile.root
            }
            // Si la última fila tiene una sola casilla, un hueco ocupa el sitio de la otra.
            if (pair.size == 1) {
                row.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = gap })
            }
            binding.typeGrid.addView(row)
        }
        renderTypeSelection()
    }

    private fun selectType(type: AlertType) {
        selectedType = type
        _binding?.typeError?.isVisible = false
        renderTypeSelection()
        renderPublishLabel()
        renderRouteChoice()
    }

    private fun renderTypeSelection() {
        val thick = resources.getDimensionPixelSize(R.dimen.alertas_tile_stroke_selected)
        val thin = resources.getDimensionPixelSize(R.dimen.alertas_tile_stroke)
        typeTiles.forEach { (type, tile) ->
            tile.isChecked = type == selectedType
            tile.strokeWidth = if (type == selectedType) thick else thin
        }
    }

    /** "Publicar alerta de tráfico": el botón dice qué se va a publicar. */
    private fun renderPublishLabel() {
        val binding = _binding ?: return
        if (!publishing) binding.btnPublish.setText(AlertStyle.publishLabel(selectedType))
    }

    /** La lista de micros, para decir de cuál se habla. */
    private fun buildRouteChoices() {
        val routes = RouteRepository.all().sortedBy { it.name.lowercase() }
        val labels = routes.map { getString(R.string.create_alert_route_item, it.name, it.subtitle) }
        binding.routeInput.setSimpleItems(labels.toTypedArray())
        binding.routeInput.setOnItemClickListener { _, _, position, _ ->
            selectedRouteId = routes.getOrNull(position)?.id
        }
    }

    /** Solo se pregunta por el micro cuando la alerta es de un micro. */
    private fun renderRouteChoice() {
        val binding = _binding ?: return
        val asks = selectedType in RoadAlert.ROUTE_TYPES
        binding.routeLayout.isVisible = asks
        if (!asks) {
            selectedRouteId = null
            binding.routeInput.setText("", false)
        }
    }

    /** Quien avisa de algo lo tiene delante: lo normal es tomarle la foto en el momento. */
    private fun askPhotoSource() {
        MaterialAlertDialogBuilder(requireContext())
            .setItems(
                arrayOf(getString(R.string.create_alert_photo_take), getString(R.string.create_alert_photo_pick))
            ) { _, which ->
                if (which == 0) {
                    openCamera()
                } else {
                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
            .show()
    }

    private fun openCamera() {
        val context = requireContext()
        try {
            val folder = File(context.cacheDir, PHOTO_FOLDER).apply { mkdirs() }
            // Las de alertas anteriores ya se subieron o se descartaron; la que
            // se está enseñando sigue hasta que la nueva la sustituya.
            val shown = photo?.lastPathSegment
            folder.listFiles()?.filterNot { it.name == shown }?.forEach { it.delete() }
            val target = FileProvider.getUriForFile(
                context,
                "${context.packageName}${AppUpdates.FILES_AUTHORITY_SUFFIX}",
                File(folder, "alerta-${System.currentTimeMillis()}.jpg")
            )
            cameraTarget = target
            takePhoto.launch(target)
        } catch (e: Exception) {
            // Un teléfono sin app de cámara, o sin sitio donde dejar la foto.
            cameraTarget = null
            binding.photoError.setText(R.string.create_alert_photo_no_camera)
            binding.photoError.isVisible = true
        }
    }

    private fun renderPhoto() {
        val binding = _binding ?: return
        binding.photoPreview.setImageURI(photo)
        binding.photoPreview.isVisible = photo != null
        binding.btnRemovePhoto.isVisible = photo != null
        binding.btnAddPhoto.setText(
            if (photo == null) R.string.create_alert_photo_add else R.string.create_alert_photo_change
        )
        binding.photoError.isVisible = false
    }

    // --- Ubicación ---

    private fun locate() {
        val binding = _binding ?: return
        val context = requireContext()
        locatingJob?.cancel()

        locationProblem = when {
            !context.hasAnyLocationPermission() -> LocationProblem.NO_PERMISSION
            !context.isLocationEnabled() -> LocationProblem.SERVICES_OFF
            else -> null
        }
        if (locationProblem != null) {
            renderLocation()
            return
        }

        binding.locationText.setText(R.string.create_alert_locating)
        binding.btnEnableLocation.isVisible = false

        locatingJob = viewLifecycleOwner.lifecycleScope.launch {
            val found = context.currentLocation()
            location = found
            locationProblem = if (found == null) LocationProblem.NO_FIX else null
            renderLocation()

            if (found != null) {
                placeName = PlaceSearch.streetName(found.latitude, found.longitude)
                renderLocation()
            }
        }
    }

    private fun renderLocation() {
        val binding = _binding ?: return
        val problem = locationProblem

        binding.locationText.text = when (problem) {
            LocationProblem.NO_PERMISSION, LocationProblem.SERVICES_OFF -> getString(R.string.create_alert_no_location)
            LocationProblem.NO_FIX -> getString(R.string.create_alert_no_fix)
            null -> placeName?.let { getString(R.string.alertas_my_location_at, it) }
                ?: getString(R.string.alertas_my_location)
        }

        // Con la ubicación ya encontrada, el enlace la vuelve a pedir (por si se movió).
        binding.btnEnableLocation.isVisible = true
        binding.btnEnableLocation.setText(
            when (problem) {
                LocationProblem.NO_PERMISSION -> R.string.location_action_enable
                LocationProblem.SERVICES_OFF -> R.string.location_action_settings
                LocationProblem.NO_FIX -> R.string.create_alert_retry
                null -> R.string.alertas_refresh_location
            }
        )
    }

    private fun onLocationAction() {
        when (locationProblem) {
            LocationProblem.NO_PERMISSION -> requestLocation.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            LocationProblem.SERVICES_OFF -> startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            LocationProblem.NO_FIX -> locate()
            null -> {
                location = null
                placeName = null
                locate()
            }
        }
    }

    // --- Publicar ---

    private fun publish() {
        if (publishing) return

        val type = selectedType
        if (type == null) {
            binding.typeError.isVisible = true
            return
        }

        // Mientras se busca la ubicación el texto ya dice "Buscando…".
        val here = location ?: return

        val comment = binding.commentInput.text?.toString()?.trim().orEmpty()
        setPublishing(true)

        viewLifecycleOwner.lifecycleScope.launch {
            // La foto va primero: si el servidor no la acepta, no se publica nada
            // y se dice por qué.
            val imageUrl = photo?.let { uri ->
                when (val uploaded = ImageUploader.upload(requireContext().applicationContext, uri, ImageUploader.Purpose.ALERT)) {
                    is ApiResult.Success -> uploaded.data
                    is ApiResult.Failure -> {
                        val binding = _binding ?: return@launch
                        setPublishing(false)
                        binding.photoError.text = uploaded.message ?: getString(R.string.create_alert_photo_failed)
                        binding.photoError.isVisible = true
                        return@launch
                    }
                }
            }

            val verdict = if (comment.isEmpty()) ModerationVerdict.Allowed else ModerationService.review(comment)
            val binding = _binding ?: return@launch

            when (verdict) {
                ModerationVerdict.Allowed -> {
                    val published = AlertRepository.publish(
                        type = type,
                        latitude = here.latitude,
                        longitude = here.longitude,
                        comment = comment.ifEmpty { null },
                        placeName = placeName,
                        routeId = selectedRouteId,
                        imageUrl = imageUrl
                    )
                    when (published) {
                        is PublishResult.Published -> {
                            // Si aún no sabemos la calle, se completa en segundo plano.
                            AlertRepository.resolvePlaceName(published.alert.id)
                            parentFragmentManager.setFragmentResult(
                                RESULT_KEY,
                                bundleOf(RESULT_ALERT_ID to published.alert.id)
                            )
                            dismiss()
                        }

                        is PublishResult.Failed -> {
                            setPublishing(false)
                            binding.commentLayout.error = when {
                                published.needsSignIn -> getString(R.string.publish_needs_account)
                                // El servidor explica por qué no (por ejemplo, una cuenta bloqueada).
                                else -> published.message ?: getString(R.string.publish_failed)
                            }
                        }
                    }
                }

                is ModerationVerdict.RejectedLocally -> {
                    setPublishing(false)
                    binding.commentLayout.error = getString(R.string.moderation_local_reason, verdict.word)
                }

                is ModerationVerdict.RejectedByModel -> {
                    setPublishing(false)
                    binding.commentLayout.error = verdict.reason
                }
            }
        }
    }

    private fun setPublishing(busy: Boolean) {
        publishing = busy
        val binding = _binding ?: return
        binding.btnPublish.isEnabled = !busy
        binding.commentInput.isEnabled = !busy
        binding.btnAddPhoto.isEnabled = !busy
        binding.btnRemovePhoto.isEnabled = !busy
        binding.btnPublish.setText(if (busy) R.string.moderation_checking else AlertStyle.publishLabel(selectedType))
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "create_alert"
        private const val STATE_TYPE = "type"
        private const val STATE_CAMERA_TARGET = "camera_target"

        /** Tiene que coincidir con `file_paths.xml`. */
        private const val PHOTO_FOLDER = "fotos"

        const val RESULT_KEY = "alert_published"
        const val RESULT_ALERT_ID = "alert_id"

        fun show(fragmentManager: FragmentManager) {
            if (fragmentManager.findFragmentByTag(TAG) == null) {
                CreateAlertSheet().show(fragmentManager, TAG)
            }
        }
    }
}
