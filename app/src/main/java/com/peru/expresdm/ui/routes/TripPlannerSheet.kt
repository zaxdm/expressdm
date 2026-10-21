package com.peru.expresdm.ui.routes

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.getSystemService
import androidx.core.os.bundleOf
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.divider.MaterialDividerItemDecoration
import com.google.android.material.snackbar.Snackbar
import com.peru.expresdm.R
import com.peru.expresdm.data.Place
import com.peru.expresdm.data.Premium
import com.peru.expresdm.data.RecentPlaceStore
import com.peru.expresdm.data.SavedPlaceStore
import com.peru.expresdm.databinding.ItemPerfilFavoriteTileBinding
import com.peru.expresdm.databinding.ItemPlaceBinding
import com.peru.expresdm.databinding.SheetTripPlannerBinding
import com.peru.expresdm.ui.SavedPlaceStyle
import com.peru.expresdm.ui.account.PremiumSheet
import com.peru.expresdm.ui.assistant.AssistantSheet
import com.peru.expresdm.ui.themeColor
import com.peru.expresdm.ui.formatDistance
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "¿A dónde vas?": se elige de dónde se sale y a dónde se va, y el inicio
 * muestra cómo llegar.
 *
 * Por defecto se sale desde donde está el usuario; el origen solo hay que
 * tocarlo para planear un viaje desde otro sitio. Mientras se escribe aparecen
 * lugares del mapa (centros comerciales, calles, universidades…), no rutas:
 * qué micro tomar se calcula al elegir el lugar correcto.
 */
class TripPlannerSheet : BottomSheetDialogFragment() {

    private enum class Field { ORIGIN, DESTINATION }

    private var _binding: SheetTripPlannerBinding? = null
    private val binding get() = _binding!!

    /** El mismo ViewModel que el mapa del inicio: el viaje elegido se dibuja allí. */
    private val viewModel: RoutesViewModel by viewModels({ requireParentFragment() })

    private val adapter by lazy { PlaceAdapter(onPlaceClick = { place -> onPlacePicked(place) }) }

    /** `null`: se sale desde donde está el usuario. */
    private var origin: Place? = null
    private var destination: Place? = null
    private var activeField = Field.DESTINATION

    private var lookupJob: Job? = null

    /** Al escribir el nombre de un lugar elegido no hay que tratarlo como texto nuevo. */
    private var settingText = false

    /** Favorito que se está guardando (casa, trabajo…): el próximo lugar elegido va ahí. */
    private var savingKind: SavedPlaceStore.Kind? = null

    /** Si se está cambiando el lugar de un favorito que ya existía, cuál. */
    private var replacing: SavedPlaceStore.Favorite? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetTripPlannerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.resultsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.resultsRecycler.adapter = adapter
        // Una línea fina entre lugar y lugar, como en las demás tarjetas.
        binding.resultsRecycler.addItemDecoration(
            MaterialDividerItemDecoration(requireContext(), MaterialDividerItemDecoration.VERTICAL).apply {
                isLastItemDecorated = false
                dividerColor = requireContext().themeColor(com.google.android.material.R.attr.colorOutline)
                val inset = (DIVIDER_INSET_DP * resources.displayMetrics.density).toInt()
                dividerInsetStart = inset
                dividerInsetEnd = inset
            }
        )

        // Al reabrir con un viaje en curso, se parte de él para poder ajustarlo.
        (viewModel.homeState.value.search as? PlaceSearchState.Chosen)?.let { current ->
            origin = current.origin
            destination = current.place
        }
        showPlaces()

        setUpField(binding.originInput, Field.ORIGIN)
        setUpField(binding.destinationInput, Field.DESTINATION)

        binding.btnClose.setOnClickListener { dismiss() }
        binding.btnSearch.setOnClickListener { search(activeInput().text?.toString().orEmpty()) }
        binding.btnSwap.setOnClickListener { swap() }
        binding.btnOriginHere.setOnClickListener {
            origin = null
            showPlaces()
            showIdle()
        }
        binding.btnPlan.setOnClickListener { finish() }
        binding.btnPickOnMap.setOnClickListener {
            setFragmentResult(REQUEST_KEY, bundleOf(RESULT_ACTION to ACTION_PICK_ON_MAP))
            dismiss()
        }
        // ¿No sabe la dirección? Se lo pregunta al asistente, que conoce los lugares.
        binding.btnAskAssistant.setOnClickListener {
            hideKeyboard()
            AssistantSheet.show(parentFragmentManager)
            dismiss()
        }
        binding.btnClearRecents.setOnClickListener { RecentPlaceStore.get(requireContext()).clear() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.recentPlaces.collect { renderRecents(it) } }
                launch { SavedPlaceStore.get(requireContext()).favorites.collect { renderFavorites(it) } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // El teclado encoge la hoja en lugar de tapar los resultados.
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        sheet.layoutParams = sheet.layoutParams.apply {
            height = (resources.displayMetrics.heightPixels * SHEET_HEIGHT_RATIO).toInt()
        }
        BottomSheetBehavior.from(sheet).apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    // --- Campos ---

    private fun setUpField(input: EditText, field: Field) {
        input.setOnFocusChangeListener { _, focused ->
            if (focused) {
                activeField = field
                onTextChanged(input.text?.toString().orEmpty())
            }
        }
        input.doAfterTextChanged { text ->
            if (settingText || activeField != field) return@doAfterTextChanged
            // Cambiar el texto deja de referirse al lugar que estaba elegido.
            if (field == Field.ORIGIN) origin = null else destination = null
            renderActions()
            onTextChanged(text?.toString().orEmpty())
        }
        input.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enterPressed) {
                search(input.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
    }

    private fun activeInput(): EditText =
        if (activeField == Field.ORIGIN) binding.originInput else binding.destinationInput

    /** Escribe en los campos los lugares elegidos, sin disparar sugerencias. */
    private fun showPlaces() {
        settingText = true
        binding.originInput.setText(origin?.name.orEmpty())
        binding.destinationInput.setText(destination?.name.orEmpty())
        settingText = false
        renderActions()
    }

    private fun renderActions() {
        val binding = _binding ?: return
        binding.btnOriginHere.isVisible = origin != null
        binding.btnPlan.isVisible = destination != null
    }

    // --- Sugerencias y búsqueda ---

    /**
     * Mientras se escribe: lugares del mapa que coinciden. Se espera a que el
     * usuario pare un momento, para no preguntar al buscador con cada letra.
     */
    private fun onTextChanged(text: String) {
        lookupJob?.cancel()
        binding.searchProgress.isInvisible = true
        val chosen = if (activeField == Field.ORIGIN) origin else destination
        if (text.isBlank() || chosen != null) {
            showIdle()
            return
        }
        if (text.trim().length < MIN_QUERY_LENGTH) {
            showResults(R.string.planner_typing_label, getString(R.string.search_too_short), emptyList())
            return
        }
        lookUp(text, delayMs = TYPING_PAUSE_MS)
    }

    /** Al pulsar buscar: lo mismo, pero ya, y se guarda el teclado para ver la lista. */
    private fun search(text: String) {
        if (text.trim().length < MIN_QUERY_LENGTH) {
            Snackbar.make(binding.root, R.string.search_too_short, Snackbar.LENGTH_SHORT).show()
            return
        }
        hideKeyboard()
        lookUp(text, delayMs = 0)
    }

    private fun lookUp(text: String, delayMs: Long) {
        lookupJob?.cancel()
        // Los sitios conocidos salen al instante, mientras se pregunta al mapa.
        val known = viewModel.knownPlaces(text)
        if (known.isNotEmpty()) {
            showResults(R.string.planner_places_label, getString(R.string.planner_places_hint), known)
        }
        lookupJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(delayMs)
            _binding?.searchProgress?.isInvisible = false
            val found = viewModel.suggestPlaces(text)
            val binding = _binding ?: return@launch
            binding.searchProgress.isInvisible = true
            val hint = when {
                found.offline -> getString(R.string.planner_places_offline)
                found.places.isEmpty() -> getString(R.string.planner_places_none)
                else -> getString(R.string.planner_places_hint)
            }
            showResults(R.string.planner_places_label, hint, found.places)
        }
    }

    private fun showIdle() {
        val binding = _binding ?: return
        binding.idleContent.isVisible = true
        binding.resultsContent.isVisible = false
        binding.btnAskAssistant.isVisible = true
        adapter.submitList(emptyList())
    }

    private fun showResults(@StringRes label: Int, hint: String, places: List<Place>) {
        val binding = _binding ?: return
        binding.idleContent.isVisible = false
        binding.resultsContent.isVisible = true
        // Mientras se eligen resultados, la fila del asistente solo quitaría sitio.
        binding.btnAskAssistant.isVisible = false
        binding.resultsLabel.setText(label)
        binding.resultsHint.text = hint
        adapter.submitList(places.map { PlaceResult(it, viewModel.distanceTo(it)) })
    }

    // --- Elegir ---

    private fun onPlacePicked(place: Place) {
        // Se estaba guardando un favorito: el lugar va ahí, no al viaje.
        savingKind?.let { kind ->
            val saved = SavedPlaceStore.get(requireContext()).save(kind, place, replacing)
            savingKind = null
            replacing = null
            hideKeyboard()
            showPlaces()
            showIdle()
            if (saved) {
                Snackbar
                    .make(binding.root, getString(R.string.saved_place_done, getString(labelOf(kind))), Snackbar.LENGTH_SHORT)
                    .show()
            }
            return
        }
        if (activeField == Field.ORIGIN) {
            origin = place
            showPlaces()
            showIdle()
            // Con el destino ya puesto, cambiar el origen completa el viaje.
            if (destination != null) finish() else binding.destinationInput.requestFocus()
        } else {
            destination = place
            finish()
        }
    }

    /** Lo que era salida pasa a ser destino, y al revés. */
    private fun swap() {
        val newOrigin = destination
        // "Desde donde estoy" solo puede ser destino si se sabe dónde es.
        val newDestination = origin ?: viewModel.currentPlace()
        if (newOrigin == null && origin == null) return
        if (newDestination == null) {
            Snackbar.make(binding.root, R.string.destination_needs_location, Snackbar.LENGTH_LONG).show()
            return
        }
        origin = newOrigin
        destination = newDestination
        binding.root.requestFocus()
        hideKeyboard()
        showPlaces()
        showIdle()
    }

    private fun finish() {
        val target = destination ?: return
        hideKeyboard()
        viewModel.planTrip(origin, target)
        setFragmentResult(REQUEST_KEY, bundleOf(RESULT_ACTION to ACTION_PLANNED))
        dismiss()
    }

    // --- Atajos ---

    private fun renderRecents(places: List<Place>) {
        val binding = _binding ?: return
        binding.recentsSection.isVisible = places.isNotEmpty()
        binding.recentsContainer.removeAllViews()
        places.forEach { place ->
            val row = ItemPlaceBinding.inflate(layoutInflater, binding.recentsContainer, false)
            row.placeIcon.setImageResource(R.drawable.ic_clock)
            row.placeName.text = place.name
            row.placeDetail.text = place.detail
            row.placeDetail.isVisible = place.detail.isNotBlank()
            val distance = viewModel.distanceTo(place)
            row.placeDistance.isVisible = distance != null
            distance?.let { row.placeDistance.text = requireContext().formatDistance(it) }
            row.root.setOnClickListener {
                activeField = Field.DESTINATION
                onPlacePicked(place)
            }
            binding.recentsContainer.addView(row.root)
        }
    }

    /** Una casilla de "Destinos favoritos": qué enseña y qué hace al tocarla. */
    private class FavoriteTile(
        @DrawableRes val icon: Int,
        val label: String,
        val detail: String?,
        val onClick: () -> Unit,
        val onLongClick: (() -> Unit)? = null
    )

    /**
     * Los destinos favoritos, en casillas de tres en fila como en la maqueta:
     * los guardados con su icono (casa, trabajo, pareja…), casa y trabajo
     * esperando su lugar con un "+", y "Otro" para cualquier otro. Cuando la
     * cuenta gratuita llega a su tope, la última casilla lleva a Premium.
     */
    private fun renderFavorites(favorites: List<SavedPlaceStore.Favorite>) {
        val binding = _binding ?: return
        val premium = Premium.active
        val tiles = mutableListOf<FavoriteTile>()

        favorites.forEach { favorite ->
            val label = getString(labelOf(favorite.kind))
            tiles += FavoriteTile(
                icon = iconOf(favorite.kind),
                label = label,
                detail = favorite.place.name,
                onClick = {
                    savingKind = null
                    replacing = null
                    activeField = Field.DESTINATION
                    onPlacePicked(favorite.place)
                },
                onLongClick = { manageFavorite(favorite, label) }
            )
        }

        if (favorites.size < Premium.favoritePlacesLimit(premium)) {
            val used = favorites.map { it.kind }.toSet()
            listOf(SavedPlaceStore.Kind.HOME, SavedPlaceStore.Kind.WORK)
                .filter { it !in used }
                .forEach { kind ->
                    tiles += FavoriteTile(R.drawable.ic_add, getString(labelOf(kind)), null, { saveOnMap(kind) })
                }
            tiles += FavoriteTile(R.drawable.ic_add, getString(R.string.saved_place_other), null, { pickKind(favorites) })
        } else {
            // La cuenta gratuita llegó a su tope: lo que sigue es de Premium.
            tiles += FavoriteTile(
                R.drawable.ic_star, getString(R.string.perfil_planner_more), null,
                { PremiumSheet.show(parentFragmentManager) }
            )
        }

        val container = binding.favoritesContainer
        container.removeAllViews()
        val gap = (TILE_GAP_DP * resources.displayMetrics.density).toInt()
        tiles.chunked(TILES_PER_ROW).forEachIndexed { index, row ->
            val line = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            row.forEachIndexed { position, tile -> line.addView(tileView(line, tile, position, gap)) }
            // Huecos invisibles para que las casillas de la última fila midan igual.
            repeat(TILES_PER_ROW - row.size) {
                line.addView(
                    View(requireContext()),
                    LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = gap }
                )
            }
            container.addView(
                line,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (index > 0) topMargin = gap }
            )
        }

        val note = if (premium) {
            getString(R.string.saved_place_add_detail_premium)
        } else {
            getString(R.string.saved_place_add_detail_free, Premium.FREE_FAVORITE_PLACES)
        }
        binding.favoritesNote.text = if (favorites.isEmpty()) {
            note
        } else {
            getString(R.string.perfil_planner_note_manage, note, getString(R.string.saved_place_manage_hint))
        }
    }

    private fun tileView(parent: ViewGroup, tile: FavoriteTile, position: Int, gap: Int): View {
        val item = ItemPerfilFavoriteTileBinding.inflate(layoutInflater, parent, false)
        item.tileIcon.setImageResource(tile.icon)
        item.tileLabel.text = tile.label
        item.tileDetail.isVisible = tile.detail != null
        item.tileDetail.text = tile.detail
        item.tileCard.contentDescription =
            tile.detail?.let { getString(R.string.saved_place_go, tile.label, it) } ?: tile.label
        item.tileCard.setOnClickListener { tile.onClick() }
        tile.onLongClick?.let { action ->
            item.tileCard.setOnLongClickListener {
                action()
                true
            }
        }
        (item.root.layoutParams as LinearLayout.LayoutParams).apply {
            width = 0
            weight = 1f
            if (position > 0) marginStart = gap
        }
        return item.root
    }

    /** Mantener presionado un favorito: cambiar su lugar o quitarlo. */
    private fun manageFavorite(favorite: SavedPlaceStore.Favorite, label: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.saved_place_title, label, favorite.place.name))
            .setItems(
                arrayOf(getString(R.string.saved_place_change), getString(R.string.saved_place_remove))
            ) { _, which ->
                if (which == 0) {
                    startSaving(favorite.kind, favorite)
                } else {
                    SavedPlaceStore.get(requireContext()).remove(favorite)
                }
            }
            .show()
    }

    /** Antes de buscar el lugar se dice qué es: de ahí salen su nombre y su icono. */
    private fun pickKind(favorites: List<SavedPlaceStore.Favorite>) =
        SavedPlaceStyle.pickKind(requireContext(), favorites) { saveOnMap(it) }

    /** Un favorito nuevo se marca tocando el mapa: casi nadie sabe escribir la dirección de su casa. */
    private fun saveOnMap(kind: SavedPlaceStore.Kind) {
        hideKeyboard()
        setFragmentResult(REQUEST_KEY, bundleOf(RESULT_ACTION to ACTION_SAVE_ON_MAP, RESULT_KIND to kind.name))
        dismiss()
    }

    /**
     * A partir de aquí, el próximo lugar que se elija se guarda como [kind];
     * con [replace], en el sitio de ese favorito en vez de añadirse.
     */
    private fun startSaving(kind: SavedPlaceStore.Kind, replace: SavedPlaceStore.Favorite? = null) {
        savingKind = kind
        replacing = replace
        activeField = Field.DESTINATION
        binding.destinationInput.requestFocus()
        requireContext().getSystemService<InputMethodManager>()
            ?.showSoftInput(binding.destinationInput, InputMethodManager.SHOW_IMPLICIT)
        Snackbar.make(binding.root, getString(R.string.saved_place_hint, getString(labelOf(kind))), Snackbar.LENGTH_LONG)
            .show()
    }

    @StringRes
    private fun labelOf(kind: SavedPlaceStore.Kind): Int = SavedPlaceStyle.label(kind)

    @DrawableRes
    private fun iconOf(kind: SavedPlaceStore.Kind): Int = SavedPlaceStyle.icon(kind)

    private fun hideKeyboard() {
        val binding = _binding ?: return
        requireContext().getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    override fun onDestroyView() {
        lookupJob?.cancel()
        binding.resultsRecycler.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "TripPlannerSheet"
        private const val SHEET_HEIGHT_RATIO = 0.94
        private const val MIN_QUERY_LENGTH = 2

        /** Cuánto se espera sin teclear antes de buscar. */
        private const val TYPING_PAUSE_MS = 450L
        private const val TILES_PER_ROW = 3
        private const val TILE_GAP_DP = 8
        private const val DIVIDER_INSET_DP = 12

        /** Con qué clave avisa esta hoja a quien la abrió, y de qué. */
        const val REQUEST_KEY = "trip_planner"
        const val RESULT_ACTION = "action"

        /** Hay un viaje elegido: toca enseñar cómo llegar. */
        const val ACTION_PLANNED = "planned"

        /** El usuario prefiere marcar el destino tocando el mapa. */
        const val ACTION_PICK_ON_MAP = "pick_on_map"

        /** El usuario va a tocar el mapa para guardar un favorito de la clase [RESULT_KIND]. */
        const val ACTION_SAVE_ON_MAP = "save_on_map"
        const val RESULT_KIND = "kind"

        fun show(manager: FragmentManager) {
            if (manager.findFragmentByTag(TAG) == null) TripPlannerSheet().show(manager, TAG)
        }
    }
}
