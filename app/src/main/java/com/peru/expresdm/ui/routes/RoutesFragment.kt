package com.peru.expresdm.ui.routes

import android.Manifest
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorInt
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.peru.expresdm.R
import com.peru.expresdm.data.AlertRepository
import com.peru.expresdm.data.AlertRules
import com.peru.expresdm.data.AlertVote
import com.peru.expresdm.data.AppSettings
import com.peru.expresdm.data.BusRoute
import com.peru.expresdm.data.DropOff
import com.peru.expresdm.data.DropOffPicker
import com.peru.expresdm.data.GeoCoordinate
import com.peru.expresdm.data.Journey
import com.peru.expresdm.data.JourneyLeg
import com.peru.expresdm.data.JourneyPlanner
import com.peru.expresdm.data.NearbyRoutes
import com.peru.expresdm.data.PathCleaner
import com.peru.expresdm.data.Place
import com.peru.expresdm.data.PlaceSearch
import com.peru.expresdm.data.RecordingDraft
import com.peru.expresdm.data.RidePoint
import com.peru.expresdm.data.RoadAlert
import com.peru.expresdm.data.RouteRepository
import com.peru.expresdm.data.SavedPlaceStore
import com.peru.expresdm.data.net.AccountRepository
import com.peru.expresdm.data.net.ApiClient
import com.peru.expresdm.data.net.ApiResult
import com.peru.expresdm.data.Premium
import com.peru.expresdm.ui.account.PremiumSheet
import com.peru.expresdm.data.SharedLink
import com.peru.expresdm.data.Stop
import com.peru.expresdm.data.StreetRouter
import com.peru.expresdm.data.UserPosition
import com.peru.expresdm.databinding.FragmentRoutesBinding
import com.peru.expresdm.databinding.ItemRouteStopBinding
import com.peru.expresdm.tracking.ArrivalAlarmService
import com.peru.expresdm.tracking.ArrivalState
import com.peru.expresdm.tracking.ArrivalTarget
import com.peru.expresdm.tracking.LiveTrip
import com.peru.expresdm.tracking.LiveTripService
import com.peru.expresdm.voice.Speaker
import com.peru.expresdm.voice.VoiceCommand
import com.peru.expresdm.voice.VoiceCommands
import com.peru.expresdm.voice.VoiceInput
import com.peru.expresdm.voice.VoiceOrbView
import com.peru.expresdm.voice.VoiceWake
import com.peru.expresdm.tracking.RecorderState
import com.peru.expresdm.tracking.RecorderStatus
import com.peru.expresdm.tracking.RouteRecorderService
import com.peru.expresdm.ui.AlertStyle
import com.peru.expresdm.ui.MapAlertRequests
import com.peru.expresdm.ui.MapMarkers
import com.peru.expresdm.ui.MapRouteRequests
import com.peru.expresdm.ui.RouteAccent
import com.peru.expresdm.ui.RouteImages
import com.peru.expresdm.ui.SavedPlaceStyle
import com.peru.expresdm.ui.StopMarkerStyle
import com.peru.expresdm.ui.TRUJILLO_CENTER
import com.peru.expresdm.ui.UserLocationOverlay
import com.peru.expresdm.ui.VisibleBand
import com.peru.expresdm.ui.applyDefaults
import com.peru.expresdm.ui.assistant.AssistantSheet
import com.peru.expresdm.ui.centerOnBand
import com.peru.expresdm.ui.formatDistance
import com.peru.expresdm.ui.formatTimeAgo
import com.peru.expresdm.ui.frameInBand
import com.peru.expresdm.ui.hasAnyLocationPermission
import com.peru.expresdm.ui.hasFineLocationPermission
import com.peru.expresdm.ui.isLocationEnabled
import com.peru.expresdm.ui.themeColor
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import java.util.Locale

/**
 * Pantalla de inicio: el mapa con la ubicación del usuario en tiempo real y,
 * encima, una hoja con lo que interesa en cada momento.
 *
 * - Sin buscar nada: las rutas que pasan cerca del usuario.
 * - Al buscar un lugar: los lugares encontrados y, al elegir uno, los micros
 *   que pasan por ahí.
 * - Al tocar una ruta: se traza en este mismo mapa, con un camino de puntos a
 *   pie hasta la parada donde subir. El mapa sigue siendo libre: se puede
 *   acercar, mover y el punto del usuario no desaparece.
 *
 * Encima del mapa viven también las alertas de los usuarios, la barra de
 * grabación de una ruta nueva y, al pedirlo su pestaña, el asistente.
 */
class RoutesFragment : Fragment() {

    private var _binding: FragmentRoutesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: RoutesViewModel by viewModels()

    // Capas del mapa, de abajo arriba. El punto del usuario se añade el último.
    private var routeLayer: FolderOverlay? = null
    private var recordingLayer: FolderOverlay? = null
    private var favoriteLayer: FolderOverlay? = null
    private var placeLayer: FolderOverlay? = null
    private var alertLayer: FolderOverlay? = null
    private var pickLayer: FolderOverlay? = null
    private var streetLayer: FolderOverlay? = null

    /** La vista de calle, en la mitad de arriba de la pantalla. */
    private var streetPane: StreetViewPane? = null
    private var locationOverlay: UserLocationOverlay? = null

    /** Elección en curso del punto de bajada; `null` cuando no se está eligiendo. */
    private var picking: PickSession? = null

    /** Se está marcando en el mapa dónde queda un favorito (casa, trabajo…): el próximo toque lo guarda. */
    private var savingFavorite: SavedPlaceStore.Kind? = null

    private var sheetBehavior: BottomSheetBehavior<LinearLayout>? = null

    private var lastState: HomeState? = null

    /** La ficha del micro, dentro del panel de la ruta elegida. */
    private var routeInfo: RouteInfoPanel? = null

    /** Los lugares favoritos del usuario, que se ven en el mapa. */
    private var favorites: List<SavedPlaceStore.Favorite> = emptyList()

    /** Un tramo a pie de "Cómo llegar" ya trazado por las calles. */
    private class StreetWalk(val from: GeoCoordinate, val to: GeoCoordinate, val points: List<GeoCoordinate>)

    private val streetWalks = ArrayDeque<StreetWalk>()

    /** Tramos ya pedidos al servicio de rutas: no se piden dos veces. */
    private val askedWalks = mutableSetOf<String>()
    private var lastRecorderStatus: RecorderStatus? = null

    /** Qué paradas hay pintadas en la hoja, para no rehacerlas en cada lectura del GPS. */
    private var renderedStopsKey: String? = null

    /** Lo ya dibujado en el mapa, para no regenerar marcadores sin cambios. */
    private var drawnAlerts: List<RoadAlert>? = null
    private var drawnPlacesKey: Any? = null

    /** Búsqueda ya encuadrada, para no volver a mover el mapa con cada lectura del GPS. */
    private var framedSearch: PlaceSearchState? = null

    /** Encuadre que espera a que la hoja termine de moverse. */
    private var pendingFrame: Runnable? = null

    /**
     * Cuándo movió el usuario el mapa con los dedos y cuándo eligió la ruta
     * actual. Si lo movió después de elegirla, ya no se le reencuadra.
     */
    private var lastGestureAt = 0L
    private var selectionStartedAt = 0L

    private val routeAdapter by lazy { RouteAdapter(onRouteClick = { route -> selectRoute(route) }) }

    /** La hoja está por encima de su posición de reposo: toca lista, no carrusel. */
    private var sheetRaised = false

    /** Cada deslizamiento del carrusel se detiene en una tarjeta entera. */
    private val carouselSnap = LinearSnapHelper()
    private val placeAdapter by lazy { PlaceAdapter(onPlaceClick = { place -> choosePlace(place) }) }
    private val journeyAdapter by lazy {
        JourneyAdapter(onJourneyClick = { journey -> viewModel.chooseJourney(journey) })
    }

    /** Viaje ya encuadrado en el mapa, para no reencuadrar en cada lectura del GPS. */
    private var framedJourney: Any? = null

    /**
     * El usuario eligió "Todas" en los filtros de la hoja. Sin ubicación se ven
     * todas igualmente, porque no se sabe cuáles pasan cerca.
     */
    private var showAllRoutes = false

    /** "Atrás" cierra primero la ruta elegida y después la búsqueda, antes de salir. */
    private val backHandler = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (streetPane?.isOpen == true) {
                closeStreetView()
                return
            }
            if (picking != null) {
                stopPicking()
                return
            }
            val state = lastState ?: return
            when {
                state.selected != null -> viewModel.clearSelection()
                state.search != PlaceSearchState.Idle -> clearSearch()
            }
        }
    }

    private val requestLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            viewModel.locationPermanentlyDenied = false
            startLocationUpdates()
        } else {
            // Cuando Android deja de mostrar el diálogo, solo queda mandar al
            // usuario a los ajustes de la app.
            viewModel.locationPermanentlyDenied =
                !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        refreshLocationAccess()
        lastState?.let { renderStatus(it) }
    }

    private val requestRecordingPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Las notificaciones son opcionales: sin ellas la grabación funciona
        // igual, solo que el aviso no se ve. La ubicación precisa no.
        if (requireContext().hasFineLocationPermission()) {
            startLocationUpdates()
            refreshLocationAccess()
            startRecorder()
        } else {
            showMessage(getString(R.string.recording_needs_location))
        }
    }

    /** Parada elegida para el aviso de llegada mientras se piden los permisos. */
    private var pendingArrival: ArrivalTarget? = null

    private val requestArrivalPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val target = pendingArrival ?: return@registerForActivityResult
        pendingArrival = null
        val context = context ?: return@registerForActivityResult
        if (!context.hasAnyLocationPermission()) {
            showLongMessage(getString(R.string.arrival_needs_location))
            return@registerForActivityResult
        }
        startLocationUpdates()
        refreshLocationAccess()
        startArrival(target)
    }

    /** Escucha «Subo» mientras el mapa está a la vista, si así se pidió en Ajustes. */
    private var voiceWake: VoiceWake? = null

    /**
     * Enciende o apaga la activación por voz según la cuenta, Ajustes y el
     * permiso del micrófono. Solo escucha con esta pantalla en primer plano.
     */
    private fun syncVoiceWake() {
        val context = context ?: return
        val wanted = isResumed && Premium.active && AppSettings.get(context).voiceWake &&
            VoiceInput.canListen(context)
        if (!wanted) {
            voiceWake?.stop()
            return
        }
        val wake = voiceWake ?: VoiceWake(
            context,
            // Con una hoja o un diálogo abierto no se escucha: o es el propio
            // asistente, o el usuario está en otra cosa.
            isBusy = {
                !isResumed || voiceTalking || childFragmentManager.fragments.any { it is DialogFragment }
            },
            // En cuanto se oye «Subo» sale la esfera: se ve que ya está atendiendo.
            onHearing = { soFar ->
                showVoiceBar(VoiceOrbView.Mode.LISTENING, soFar.ifBlank { getString(R.string.voice_listening) })
            },
            onLevel = { level -> _binding?.voiceBarOrb?.setLevel(level) },
            onWake = { said -> if (isResumed && !childFragmentManager.isStateSaved) onWake(said) else endMapVoice() }
        ).also { voiceWake = it }
        wake.start()
    }

    /** Escucha lo que se contesta a Subo cuando pregunta en el mapa. */
    private var mapVoice: VoiceInput? = null

    /** Se está hablando con Subo sobre el mapa: mientras, no se espera otro «Subo». */
    private var voiceTalking = false

    private val hideVoiceBar = Runnable { endMapVoice() }

    /**
     * Enseña la esfera pequeña del mapa con lo que se escucha o se dice. Se
     * quita sola pasado [timeoutMs], por si la voz se corta sin avisar.
     */
    private fun showVoiceBar(mode: VoiceOrbView.Mode, text: String, timeoutMs: Long = VOICE_BAR_TIMEOUT_MS) {
        val binding = _binding ?: return
        voiceTalking = true
        binding.voiceBarOrb.mode = mode
        binding.voiceBarText.text = text
        binding.voiceBar.isVisible = true
        binding.voiceBar.removeCallbacks(hideVoiceBar)
        binding.voiceBar.postDelayed(hideVoiceBar, timeoutMs)
    }

    /** Termina la conversación hablada del mapa y quita la esfera. */
    private fun endMapVoice() {
        voiceTalking = false
        mapVoice?.cancel()
        _binding?.voiceBar?.let { bar ->
            bar.removeCallbacks(hideVoiceBar)
            bar.isVisible = false
        }
    }

    /** Se dijo «Subo»: con una frase detrás se cumple; a secas, Subo pregunta a dónde se va. */
    private fun onWake(said: String) {
        if (said.isBlank()) {
            askDestination()
        } else {
            endMapVoice()
            onVoiceSaid(said)
        }
    }

    /** Pregunta en voz alta a dónde se quiere ir y escucha la respuesta, sin salir del mapa. */
    private fun askDestination() {
        val question = getString(R.string.voice_prompt)
        showVoiceBar(VoiceOrbView.Mode.SPEAKING, question)
        Speaker.speak(
            requireContext(),
            question,
            asAlarm = false,
            onWord = { _binding?.voiceBarOrb?.pulse() }
        ) {
            if (_binding != null && isResumed && voiceTalking) listenOnMap(answering = true) else endMapVoice()
        }
    }

    /**
     * Escucha lo que se contesta en el mapa. Con [answering] se contesta a
     * "¿a dónde quieres ir?"; con [arrivalOffered], a "¿te aviso al llegar?".
     */
    private fun listenOnMap(answering: Boolean = false, arrivalOffered: Boolean = false) {
        val input = mapVoice ?: VoiceInput(requireContext()).also { mapVoice = it }
        showVoiceBar(VoiceOrbView.Mode.LISTENING, getString(R.string.voice_listening))
        input.listen(
            onPartial = { heard -> _binding?.voiceBarText?.text = heard },
            onLevel = { level -> _binding?.voiceBarOrb?.setLevel(level) }
        ) { text, failed ->
            if (_binding == null) return@listen
            endMapVoice()
            when {
                text != null && arrivalOffered && VoiceCommands.isYes(text) -> onVoiceSaid(ARRIVAL_ON_PHRASE)
                text != null && arrivalOffered && VoiceCommands.isNo(text) -> say(getString(R.string.voice_ok))
                text != null -> onVoiceSaid(text, answering = answering)
                failed -> showLongMessage(getString(R.string.voice_failed))
                // Silencio: no quería nada.
                else -> Unit
            }
        }
    }

    /**
     * Hace lo que se pidió hablando. Las órdenes fijas (ir a un sitio, el aviso,
     * compartir) se cumplen aquí; cualquier otra frase es una pregunta para el
     * asistente, que se abre contestándola. Sin frase, se abre escuchando.
     *
     * Con [answering] la frase contesta a "¿a dónde quieres ir?": un lugar dicho
     * a secas es el destino.
     */
    private fun onVoiceSaid(said: String, answering: Boolean = false) {
        val rides = lastState?.chosenJourney?.rides.orEmpty()
        when (val command = if (answering) VoiceCommands.parseDestination(said) else VoiceCommands.parse(said)) {
            is VoiceCommand.Ask -> AssistantSheet.show(childFragmentManager, voice = true, question = command.question)
            is VoiceCommand.GoTo -> goToByVoice(command.place)
            VoiceCommand.ArrivalOn -> when {
                rides.isEmpty() -> say(getString(R.string.voice_no_trip))
                arrivalOn(rides) -> say(getString(R.string.voice_arrival_already))
                else -> onJourneyArrival()
            }
            VoiceCommand.ArrivalOff ->
                if (ArrivalAlarmService.state.value.target == null) {
                    say(getString(R.string.voice_no_arrival))
                } else {
                    ArrivalAlarmService.cancel(requireContext().applicationContext)
                    say(getString(R.string.arrival_cancelled))
                }
            VoiceCommand.ShareTrip -> if (rides.isEmpty()) say(getString(R.string.voice_no_trip)) else shareJourney()
            VoiceCommand.StopSharing ->
                if (LiveTripService.state.value == null) {
                    say(getString(R.string.voice_not_sharing))
                } else {
                    LiveTripService.stop(requireContext().applicationContext)
                    say(getString(R.string.live_trip_stopped))
                }
            VoiceCommand.WhereAmI -> whereAmI()
            VoiceCommand.Close -> {
                Speaker.stop()
                endMapVoice()
            }
        }
    }

    /**
     * Contesta a una orden: se lee en alto y, por si no se oye, también se
     * escribe junto a la esfera, que se mueve con cada palabra.
     *
     * Con [offersArrival] lo dicho termina preguntando si se quiere el aviso
     * de llegada: al acabar se escucha la respuesta, sin tener que volver a
     * llamar a Subo.
     */
    private fun say(text: String, offersArrival: Boolean = false) {
        showVoiceBar(VoiceOrbView.Mode.SPEAKING, text)
        Speaker.speak(
            requireContext(),
            text,
            asAlarm = false,
            onWord = { _binding?.voiceBarOrb?.pulse() }
        ) {
            if (offersArrival && _binding != null && isResumed && voiceTalking &&
                VoiceInput.canListen(requireContext())
            ) {
                listenOnMap(arrivalOffered = true)
                return@speak
            }
            // Se queda un momento más, para acabar de leerlo.
            _binding?.voiceBar?.let { bar ->
                bar.removeCallbacks(hideVoiceBar)
                bar.postDelayed(hideVoiceBar, VOICE_BAR_LINGER_MS)
            }
        }
    }

    /** "Llévame a casa", "llévame al Mall Plaza": busca el sitio y enseña cómo llegar. */
    private fun goToByVoice(spoken: String) {
        // Primero entre los favoritos: "casa", "el trabajo", "mi casa".
        val wanted = spoken.lowercase().removePrefix("mi ").removePrefix("la ").removePrefix("el ").trim()
        val favorite = favorites.firstOrNull { favorite ->
            getString(SavedPlaceStyle.label(favorite.kind)).lowercase() == wanted ||
                favorite.place.name.lowercase() == wanted
        }?.place
        // Buscar el sitio tarda: mientras, la esfera "piensa" con lo que se pidió.
        showVoiceBar(VoiceOrbView.Mode.THINKING, spoken)
        viewLifecycleOwner.lifecycleScope.launch {
            val place = favorite ?: viewModel.bestPlace(spoken)
            if (_binding == null) return@launch
            if (place == null) {
                say(getString(R.string.voice_place_not_found, spoken))
            } else {
                viewModel.planTrip(null, place)
                // La conversación sigue: se ofrece el aviso y se escucha la respuesta.
                val offer = Premium.active && VoiceInput.canListen(requireContext())
                say(
                    getString(if (offer) R.string.voice_going_to_offer else R.string.voice_going_to, place.name),
                    offersArrival = offer
                )
            }
        }
    }

    /** Dice en qué calle está el usuario y lleva el mapa ahí. */
    private fun whereAmI() {
        val here = lastState?.user
        if (here == null) {
            say(getString(R.string.voice_no_location))
            return
        }
        centerOnUser()
        viewLifecycleOwner.lifecycleScope.launch {
            val street = PlaceSearch.streetName(here.latitude, here.longitude)
            if (_binding == null) return@launch
            say(if (street == null) getString(R.string.voice_where_unknown) else getString(R.string.voice_where, street))
        }
    }

    /** El viaje que se quiere compartir en vivo mientras se piden los permisos. */
    private var pendingLiveTrip: TripShare? = null

    private val requestLivePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val share = pendingLiveTrip ?: return@registerForActivityResult
        pendingLiveTrip = null
        val context = context ?: return@registerForActivityResult
        if (!context.hasAnyLocationPermission()) {
            showLongMessage(getString(R.string.live_trip_needs_location))
            return@registerForActivityResult
        }
        startLocationUpdates()
        refreshLocationAccess()
        startLiveTrip(share)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRoutesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setUpMap()
        setUpSheet()
        setUpSearch()
        setUpRecording()
        setUpRouteFilter()

        binding.routesRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.routesRecycler.adapter = routeAdapter
        // El panel de la ruta empieza oculto y la hoja se arrastra con la lista.
        // No se declara en el XML: NestedScrollView falla al inflarse con ese
        // atributo en algunos Android 15 (Honor), porque View lo aplica antes
        // de que su ayudante de desplazamiento exista.
        binding.routePanel.isNestedScrollingEnabled = false

        binding.fabMyLocation.setOnClickListener { centerOnUser() }
        binding.voiceBarOrb.compact = true
        binding.btnCloseVoice.setOnClickListener {
            Speaker.stop()
            endMapVoice()
        }
        // La pestaña Asistente de la barra de abajo pide abrirlo aquí, sobre el mapa.
        parentFragmentManager.setFragmentResultListener(REQUEST_OPEN_ASSISTANT, viewLifecycleOwner) { _, _ ->
            AssistantSheet.show(childFragmentManager)
        }
        // Una orden dicha al asistente ("llévame a…") se cumple aquí, en el mapa.
        childFragmentManager.setFragmentResultListener(AssistantSheet.REQUEST_COMMAND, viewLifecycleOwner) { _, result ->
            onVoiceSaid(result.getString(AssistantSheet.RESULT_SAID).orEmpty())
        }
        binding.btnStreetView.setOnClickListener { openStreetView() }
        binding.streetPane.btnCloseStreet.setOnClickListener { closeStreetView() }
        binding.btnStatusAction.setOnClickListener { onStatusAction() }
        binding.btnWalkAction.setOnClickListener { onStatusAction() }
        binding.btnCloseRoute.setOnClickListener { viewModel.clearSelection() }
        binding.btnClosePlace.setOnClickListener { clearSearch() }
        binding.alertsOnRoute.setOnClickListener { openAlertsTab() }
        // Al tocar una ruta, su ficha sale en el mismo panel; ahí "Compartir"
        // manda el viaje, que dice además dónde se baja uno.
        routeInfo = RouteInfoPanel(this, binding.routeInfo, ::showLongMessage, onShare = ::shareTrip)
        binding.btnJourneyArrival.setOnClickListener { onJourneyArrival() }
        binding.btnJourneyDetail.setOnClickListener {
            pickRide(R.string.journey_detail_which) { ride -> openRouteDetail(ride.route) }
        }
        binding.btnJourneyShare.setOnClickListener { shareJourney() }
        binding.btnSavePlace.setOnClickListener { onSavePlace() }
        binding.btnDeleteRoute.setOnClickListener {
            lastState?.selected?.route?.let { route -> confirmDelete(route) }
        }
        binding.btnArrivalAction.setOnClickListener { onArrivalAction() }
        binding.btnCancelArrival.setOnClickListener { cancelArrival() }
        binding.btnPickConfirm.setOnClickListener { confirmPick() }
        binding.btnPickClose.setOnClickListener { stopPicking() }
        binding.arrivalBar.setOnClickListener {
            ArrivalAlarmService.state.value.target?.let { viewModel.selectRoute(it.routeId) }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backHandler)

        if (requireContext().hasAnyLocationPermission()) {
            startLocationUpdates()
            if (viewModel.camera?.followingUser == true) locationOverlay?.enableFollowLocation()
        } else if (!viewModel.hasAskedForLocation) {
            // El inicio gira alrededor de la ubicación: la pedimos nada más entrar,
            // pero una sola vez, para no insistir si el usuario la rechaza.
            viewModel.hasAskedForLocation = true
            askForLocation()
        }

        // La franja útil del mapa depende del alto de los controles de arriba
        // (que crece con la barra de grabación) y de la hoja.
        binding.root.doOnLayout { updateMapCenterOffset() }
        // La cabecera de la hoja cambia de alto con el aviso de ubicación.
        binding.routesRecycler.addOnLayoutChangeListener { _, _, top, _, _, _, oldTop, _, _ ->
            if (top != oldTop || !binding.routesRecycler.isLaidOut) fitSheet()
        }
        // Y la lista baja cuando aparecen las acciones del destino elegido.
        (binding.routesRecycler.parent as View).addOnLayoutChangeListener { _, _, top, _, _, _, oldTop, _, _ ->
            if (top != oldTop) fitSheet()
        }
        binding.root.doOnLayout { fitSheet() }
        binding.topArea.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) updateMapCenterOffset()
        }

        observeState()
    }

    // --- Mapa ---

    private fun setUpMap() {
        val map = binding.mapView
        map.applyDefaults(requireContext())

        val camera = viewModel.camera
        if (camera != null) {
            map.controller.setZoom(camera.zoom)
            map.controller.setCenter(GeoPoint(camera.latitude, camera.longitude))
        } else {
            map.controller.setZoom(DEFAULT_ZOOM)
            map.controller.setCenter(TRUJILLO_CENTER)
        }

        routeLayer = FolderOverlay().also { map.overlays.add(it) }
        recordingLayer = FolderOverlay().also { map.overlays.add(it) }
        favoriteLayer = FolderOverlay().also { map.overlays.add(it) }
        placeLayer = FolderOverlay().also { map.overlays.add(it) }
        alertLayer = FolderOverlay().also { map.overlays.add(it) }
        pickLayer = FolderOverlay().also { map.overlays.add(it) }
        streetLayer = FolderOverlay().also { map.overlays.add(it) }

        // Los toques se atienden al final: primero los marcadores, que tienen
        // su propia respuesta. Un toque libre elige el destino del viaje.
        map.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                val point = p ?: return false
                onMapTapped(point)
                return true
            }

            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }))
    }

    /**
     * Abre o cierra la vista de calle, que comparte la pantalla con el mapa. Se
     * abre en el sitio que el usuario marcó (la bajada que está eligiendo o el
     * destino) y, si no marcó ninguno, en el centro de lo que se ve del mapa.
     */
    private fun openStreetView() {
        if (streetPane?.isOpen == true) {
            closeStreetView()
            return
        }
        val map = binding.mapView
        val marked = picking?.drop?.point
            ?: (lastState?.search as? PlaceSearchState.Chosen)?.place?.let { GeoCoordinate(it.latitude, it.longitude) }
        // El centro de la vista queda debajo de la hoja: se toma el de la franja libre.
        val point = marked?.toGeoPoint()
            ?: visibleBand()?.let { map.projection.fromPixels(map.width / 2, it.center) }
            ?: map.mapCenter
        showStreet(GeoPoint(point.latitude, point.longitude))
        showLongMessage(getString(R.string.street_view_hint))
    }

    /** Enseña arriba la calle de [point] y lleva el mapa, debajo, a ese punto. */
    private fun showStreet(point: GeoPoint) {
        val binding = _binding ?: return
        val pane = streetPane ?: StreetViewPane(binding.streetPane, ::closeStreetView).also { streetPane = it }
        val opening = !pane.isOpen
        if (opening) {
            placeStreetPane()
            // Mirando la calle, la hoja de rutas sobra: se esconde entera y el
            // mapa se queda con todo lo que hay debajo de la calle.
            sheetBehavior?.let {
                it.isHideable = true
                it.state = BottomSheetBehavior.STATE_HIDDEN
            }
        }
        pane.show(point.latitude, point.longitude)
        refreshBackHandler()
        renderStreetButton()

        streetLayer?.let { layer ->
            layer.items.clear()
            layer.add(
                Marker(binding.mapView).apply {
                    position = point
                    icon = MapMarkers.streetView(requireContext())
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ -> true }
                }
            )
        }
        cancelPendingFrame()
        locationOverlay?.disableFollowLocation()
        // Al abrir, el panel y la hoja aún se están colocando: se espera a que acaben.
        binding.mapView.postDelayed({
            val map = _binding?.mapView ?: return@postDelayed
            updateMapCenterOffset()
            map.controller.stopAnimation(false)
            map.controller.animateTo(point, maxOf(map.zoomLevelDouble, STREET_ZOOM), CAMERA_ANIMATION_MS)
        }, if (opening) SHEET_SETTLE_MS else 0L)
    }

    /** La calle ocupa su parte de la pantalla desde arriba, tapando el buscador. */
    private fun placeStreetPane() {
        val binding = _binding ?: return
        val pane = binding.streetPane.root
        val height = (binding.root.height * STREET_PANE_RATIO).toInt()
        if (pane.layoutParams.height == height) return
        pane.layoutParams = pane.layoutParams.apply { this.height = height }
    }

    /**
     * "Ver calle" solo se ofrece cuando hay un sitio marcado del que enseñarla:
     * el destino elegido o la bajada. Con la calle abierta no hace falta: se
     * cierra con su X.
     */
    private fun renderStreetButton() {
        val binding = _binding ?: return
        binding.streetViewHolder.isVisible = streetPane?.isOpen != true &&
            (picking?.drop != null || lastState?.search is PlaceSearchState.Chosen)
    }

    private fun closeStreetView() {
        val binding = _binding ?: return
        streetPane?.close()
        streetLayer?.items?.clear()
        binding.mapView.invalidate()
        refreshBackHandler()
        renderStreetButton()
        restSheet()
        binding.root.post { updateMapCenterOffset() }
    }

    /** "Atrás" tiene algo que cerrar: la calle, la elección de bajada, la ruta o la búsqueda. */
    private fun refreshBackHandler() {
        val state = lastState
        backHandler.isEnabled = streetPane?.isOpen == true || picking != null ||
            state?.selected != null || (state != null && state.search != PlaceSearchState.Idle)
    }

    /** Franja del mapa que no tapan ni los controles de arriba ni la hoja. */
    private fun visibleBand(): VisibleBand? {
        val binding = _binding ?: return null
        val mapHeight = binding.mapView.height
        if (mapHeight == 0) return null
        // Con la calle abierta, el mapa empieza debajo de ella.
        val street = binding.streetPane.root
        val top = maxOf(binding.topArea.bottom, if (street.isVisible) street.bottom else 0)
        return VisibleBand(top, binding.sheet.top.coerceIn(top + 1, mapHeight))
    }

    private fun updateMapCenterOffset() {
        val binding = _binding ?: return
        val band = visibleBand() ?: return
        binding.mapView.centerOnBand(band)
    }

    private fun startLocationUpdates() {
        val binding = _binding ?: return
        val overlay = locationOverlay ?: createLocationOverlay().also {
            locationOverlay = it
            // Siempre la última capa: el punto del usuario queda encima de rutas y alertas.
            binding.mapView.overlays.add(it)
        }
        overlay.enableMyLocation()
    }

    private fun createLocationOverlay(): UserLocationOverlay {
        val provider = GpsMyLocationProvider(requireContext().applicationContext).apply {
            locationUpdateMinTime = LOCATION_INTERVAL_MS
            locationUpdateMinDistance = LOCATION_MIN_DISTANCE_M
        }
        val dot = MapMarkers.userDot(requireContext())

        return UserLocationOverlay(
            provider = provider,
            mapView = binding.mapView,
            onFix = ::onUserLocation,
            onUserGesture = ::onMapGesture
        ).apply {
            setDirectionArrow(dot, dot)
            setPersonHotspot(dot.width / 2f, dot.height / 2f)
        }
    }

    private fun onUserLocation(location: Location) {
        if (_binding == null) return

        viewModel.onLocationChanged(location.latitude, location.longitude)

        // La primera vez llevamos el mapa hasta el usuario, salvo que ya esté
        // mirando una ruta o un lugar buscado.
        val state = lastState
        val idle = state == null || (state.selected == null && state.search == PlaceSearchState.Idle)
        if (!viewModel.hasCenteredOnUser && idle) {
            viewModel.hasCenteredOnUser = true
            binding.mapView.controller.animateTo(GeoPoint(location), USER_ZOOM, CAMERA_ANIMATION_MS)
            locationOverlay?.enableFollowLocation()
        }
    }

    /** El usuario movió o pellizcó el mapa: a partir de aquí manda él. */
    private fun onMapGesture() {
        lastGestureAt = SystemClock.elapsedRealtime()
        cancelPendingFrame()
    }

    private fun centerOnUser() {
        val context = requireContext()
        if (!context.hasAnyLocationPermission()) {
            onStatusAction()
            return
        }
        if (!context.isLocationEnabled()) {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        startLocationUpdates()
        val overlay = locationOverlay ?: return
        cancelPendingFrame()
        // Con una ruta elegida no seguimos al usuario: solo lo mostramos.
        if (lastState?.selected == null) overlay.enableFollowLocation()

        val here = overlay.myLocation
        if (here == null) {
            showMessage(getString(R.string.location_status_waiting))
        } else {
            binding.mapView.controller.stopAnimation(false)
            binding.mapView.controller.animateTo(here, USER_ZOOM, CAMERA_ANIMATION_MS)
        }
        lowerSheet()
    }

    private fun returnToUser() {
        val binding = _binding ?: return
        val overlay = locationOverlay ?: return
        cancelPendingFrame()
        binding.mapView.controller.stopAnimation(false)
        overlay.enableFollowLocation()
        overlay.myLocation?.let {
            binding.mapView.controller.animateTo(it, USER_ZOOM, CAMERA_ANIMATION_MS)
        }
    }

    /** Encuadra [points] cuando la hoja haya terminado de moverse. */
    private fun scheduleFrame(points: List<GeoPoint>) {
        val binding = _binding ?: return
        cancelPendingFrame()
        if (points.isEmpty()) return

        val sheetMoving = sheetBehavior?.state.let {
            it == BottomSheetBehavior.STATE_SETTLING || it == BottomSheetBehavior.STATE_DRAGGING
        }
        val frame = Runnable {
            pendingFrame = null
            frameGeoPoints(points)
        }
        pendingFrame = frame
        binding.mapView.postDelayed(frame, if (sheetMoving) SHEET_SETTLE_MS else FRAME_DELAY_MS)
    }

    private fun cancelPendingFrame() {
        pendingFrame?.let { _binding?.mapView?.removeCallbacks(it) }
        pendingFrame = null
    }

    private fun frameGeoPoints(points: List<GeoPoint>) {
        val binding = _binding ?: return
        val band = visibleBand() ?: return
        binding.mapView.frameInBand(
            points = points,
            band = band,
            borderPx = (FRAME_BORDER_DP * resources.displayMetrics.density).toInt(),
            animated = true
        )
    }

    // --- Búsqueda de lugares ---

    /**
     * El buscador del inicio no se escribe: abre "¿A dónde vas?", donde se
     * eligen la salida y el destino. Aquí solo se ve el viaje en curso.
     */
    private fun setUpSearch() {
        binding.searchCard.setOnClickListener { TripPlannerSheet.show(childFragmentManager) }
        binding.btnClearSearch.setOnClickListener { clearSearch() }

        childFragmentManager.setFragmentResultListener(
            TripPlannerSheet.REQUEST_KEY, viewLifecycleOwner
        ) { _, result ->
            when (result.getString(TripPlannerSheet.RESULT_ACTION)) {
                // Con el viaje elegido, la hoja sube para enseñar las formas de llegar.
                TripPlannerSheet.ACTION_PLANNED -> {
                    restSheet()
                    if (viewModel.homeState.value.journeyStart == null) {
                        showLongMessage(getString(R.string.destination_needs_location))
                    }
                }
                // Tocar el mapa ya elige destino (onMapTapped): basta con decirlo.
                TripPlannerSheet.ACTION_PICK_ON_MAP -> {
                    // Para apuntar bien hace falta todo el mapa: la hoja, al mínimo.
                    sheetBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED
                    showLongMessage(getString(R.string.destination_tap_hint))
                }
                TripPlannerSheet.ACTION_SAVE_ON_MAP -> {
                    val kind = result.getString(TripPlannerSheet.RESULT_KIND)
                        ?.let { name -> SavedPlaceStore.Kind.entries.firstOrNull { it.name == name } }
                        ?: return@setFragmentResultListener
                    savingFavorite = kind
                    sheetBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED
                    showLongMessage(getString(R.string.saved_place_tap_hint, getString(SavedPlaceStyle.label(kind))))
                }
            }
        }
    }

    /** El buscador enseña el viaje en curso: "Salida → Destino". */
    private fun renderSearchBar(state: HomeState) {
        val chosen = state.search as? PlaceSearchState.Chosen
        binding.searchText.text = chosen?.let {
            getString(
                R.string.planner_trip_title,
                it.origin?.name ?: getString(R.string.my_location),
                it.place.name
            )
        }
        val searching = state.search != PlaceSearchState.Idle
        binding.btnClearSearch.isVisible = searching
        binding.searchIcon.isVisible = !searching
    }

    private fun choosePlace(place: Place) {
        hideKeyboard()
        viewModel.choosePlace(place)
        restSheet()
    }

    private fun clearSearch() {
        val binding = _binding ?: return
        viewModel.clearSearch()
        hideKeyboard()
        lowerSheet()
    }

    // --- Lista ---

    /** Grabar rutas nuevas es cosa de administradores: los demás no ven el botón. */
    private fun renderAddRoute() {
        val binding = _binding ?: return
        val inSearch = lastState?.let { it.search != PlaceSearchState.Idle } ?: false
        binding.btnAddRoute.isVisible = !inSearch && AccountRepository.session.value?.isAdmin == true
    }

    private fun renderList(state: HomeState) {
        val recycler = binding.routesRecycler

        if (state.mode == HomeMode.PLACE_RESULTS) {
            if (recycler.adapter !== placeAdapter) recycler.adapter = placeAdapter
            val previousFirst = placeAdapter.currentList.firstOrNull()?.place
            placeAdapter.submitList(state.places) {
                if (state.places.firstOrNull()?.place != previousFirst) _binding?.routesRecycler?.scrollToPosition(0)
            }
        } else if (state.mode == HomeMode.PLACE_JOURNEYS) {
            if (recycler.adapter !== journeyAdapter) recycler.adapter = journeyAdapter
            journeyAdapter.selectedKey = state.chosenJourney?.key
            journeyAdapter.submitList(state.journeys)
        } else {
            if (recycler.adapter !== routeAdapter) recycler.adapter = routeAdapter
            val items = shownRoutes(state)
            val previousFirst = routeAdapter.currentList.firstOrNull()?.route?.id
            routeAdapter.submitList(items) {
                // Cuando cambia la ruta de arriba (al llegar la primera posición o al
                // moverse el usuario), DiffUtil conserva el desplazamiento y la más
                // cercana queda oculta: volvemos al principio de la lista.
                if (items.firstOrNull()?.route?.id != previousFirst) {
                    _binding?.routesRecycler?.scrollToPosition(0)
                }
            }
        }

        applyListLayout()
        renderSearchBar(state)

        val search = state.search
        val inSearch = search != PlaceSearchState.Idle
        renderAddRoute()
        binding.btnClosePlace.isVisible = inSearch
        binding.searchProgress.isVisible = state.mode == HomeMode.PLACE_SEARCHING

        renderHeader(state)

        val empty = when (state.mode) {
            HomeMode.PLACE_SEARCHING -> null
            HomeMode.PLACE_RESULTS -> when {
                state.places.isNotEmpty() -> null
                (search as? PlaceSearchState.Results)?.offline == true -> R.string.search_offline
                else -> R.string.search_no_results
            }
            HomeMode.PLACE_ROUTES -> R.string.place_no_routes.takeIf { state.items.isEmpty() }
            HomeMode.PLACE_JOURNEYS -> R.string.journey_none.takeIf { state.journeys.isEmpty() }
            else -> R.string.empty_routes.takeIf { shownRoutes(state).isEmpty() }
        }
        binding.emptyState.isVisible = empty != null
        empty?.let { binding.emptyState.setText(it) }
    }

    /** Con ubicación, el usuario elige entre las que pasan cerca y todas. */
    private fun located(state: HomeState) =
        state.mode == HomeMode.NEARBY || state.mode == HomeMode.NONE_NEARBY

    /** Las rutas que enseña la lista: las del estado o, con "Todas", el catálogo entero. */
    private fun shownRoutes(state: HomeState): List<RouteListItem> =
        if (showAllRoutes && located(state)) RouteRepository.all().map { RouteListItem(it) } else state.items

    /**
     * Encabezado de la hoja como en el diseño: "Rutas" o "Líneas que pasan
     * cerca de ti", debajo cuántas opciones hay, y los filtros Cerca de ti / Todas.
     */
    private fun renderHeader(state: HomeState) {
        val binding = _binding ?: return
        val search = state.search
        val inSearch = search != PlaceSearchState.Idle
        val radius = requireContext().formatDistance(NearbyRoutes.RADIUS_METERS)
        val nearbyShown = located(state) && !showAllRoutes
        val total = RouteRepository.all().size
        fun options(count: Int) = resources.getQuantityString(R.plurals.routes_count, count, count)

        binding.sheetLabel.isVisible = inSearch
        when (state.mode) {
            HomeMode.PLACE_SEARCHING, HomeMode.PLACE_RESULTS -> {
                binding.sheetLabel.setText(R.string.search_label)
                binding.sheetTitle.text = getString(R.string.search_query_title, search.text().orEmpty())
            }
            HomeMode.PLACE_ROUTES -> {
                binding.sheetLabel.setText(R.string.place_routes_label)
                binding.sheetTitle.text = (search as? PlaceSearchState.Chosen)?.place?.name
            }
            HomeMode.PLACE_JOURNEYS -> {
                binding.sheetLabel.setText(R.string.journey_label)
                binding.sheetTitle.text = (search as? PlaceSearchState.Chosen)?.place?.name
            }
            else -> binding.sheetTitle.setText(if (nearbyShown) R.string.nearby_title else R.string.rutas_title)
        }

        val subtitle = when (state.mode) {
            HomeMode.PLACE_SEARCHING -> null
            HomeMode.PLACE_RESULTS ->
                resources.getQuantityString(R.plurals.places_count, state.places.size, state.places.size)
            HomeMode.PLACE_JOURNEYS ->
                resources.getQuantityString(R.plurals.journey_count, state.journeys.size, state.journeys.size)
            HomeMode.PLACE_ROUTES -> options(state.items.size)
            HomeMode.NEARBY ->
                if (nearbyShown) getString(R.string.rutas_count_nearby, options(state.items.size), radius)
                else options(total)
            HomeMode.NONE_NEARBY ->
                if (nearbyShown) getString(R.string.rutas_count_none, radius) else options(total)
            HomeMode.WAITING_LOCATION -> getString(R.string.rutas_count_waiting, options(state.items.size))
            HomeMode.NO_PERMISSION, HomeMode.LOCATION_OFF -> options(state.items.size)
        }
        binding.sheetSubtitle.isVisible = subtitle != null
        binding.sheetSubtitle.text = subtitle

        // Los filtros solo tienen sentido en el inicio, sin búsqueda.
        binding.routeFilter.isVisible = !inSearch
        binding.chipNearby.text = if (state.mode == HomeMode.NEARBY) {
            getString(R.string.rutas_chip_nearby, state.items.size)
        } else {
            getString(R.string.rutas_chip_nearby_plain)
        }
        binding.chipAll.text = getString(R.string.rutas_chip_all, total)
        val checked = if (nearbyShown) R.id.chipNearby else R.id.chipAll
        if (binding.routeFilter.checkedChipId != checked) binding.routeFilter.check(checked)
    }

    private fun setUpRouteFilter() {
        binding.routeFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val state = lastState ?: return@setOnCheckedStateChangeListener
            val wantsAll = checkedIds.firstOrNull() == R.id.chipAll
            if (!located(state)) {
                // Sin ubicación no se sabe qué pasa cerca: se ofrece activarla y
                // se sigue viendo todo. La preferencia del usuario no cambia.
                if (!wantsAll) {
                    if (_binding?.btnStatusAction?.isVisible == true) onStatusAction()
                    renderHeader(state)
                }
                return@setOnCheckedStateChangeListener
            }
            if (wantsAll == showAllRoutes) return@setOnCheckedStateChangeListener
            showAllRoutes = wantsAll
            renderList(state)
        }
    }

    /**
     * "Todas" y los lugares son una lista de filas. Las rutas que pasan cerca y
     * las formas de llegar van en tarjetas con foto: con la hoja en reposo, un
     * carrusel que se ojea de lado sin tapar el mapa; al subirla, las mismas
     * tarjetas a lo ancho, una debajo de otra.
     */
    private fun applyListLayout() {
        val binding = _binding ?: return
        val recycler = binding.routesRecycler
        // Al volver a la pestaña la hoja recupera su altura sin avisar.
        when (sheetBehavior?.state) {
            BottomSheetBehavior.STATE_EXPANDED -> sheetRaised = true
            BottomSheetBehavior.STATE_HALF_EXPANDED, BottomSheetBehavior.STATE_COLLAPSED -> sheetRaised = false
            else -> Unit
        }
        val manager = recycler.layoutManager as? LinearLayoutManager ?: return
        val photoCards = nearbyCardsShown()
        val style = when {
            !photoCards -> RouteAdapter.Style.ROWS
            sheetRaised -> RouteAdapter.Style.CARDS
            else -> RouteAdapter.Style.CAROUSEL
        }
        val carousel = !sheetRaised && hasCarousel()
        val orientation = if (carousel) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL

        if (routeAdapter.style != style || journeyAdapter.carousel != !sheetRaised ||
            manager.orientation != orientation
        ) {
            routeAdapter.style = style
            journeyAdapter.carousel = !sheetRaised
            manager.orientation = orientation
            carouselSnap.attachToRecyclerView(if (carousel) recycler else null)
            recycler.scrollToPosition(0)
        }
        fitSheet()
    }

    /** Lo que hay en la lista se puede enseñar como carrusel: las formas de llegar y las rutas cercanas. */
    private fun hasCarousel(): Boolean {
        val adapter = _binding?.routesRecycler?.adapter
        return adapter === journeyAdapter || (adapter === routeAdapter && nearbyCardsShown())
    }

    /** La lista muestra las rutas que pasan cerca (no "Todas"): esas van con foto, como antes. */
    private fun nearbyCardsShown(): Boolean {
        val state = lastState ?: return false
        return state.mode == HomeMode.NEARBY && !showAllRoutes
    }

    private fun setSheetRaised(raised: Boolean) {
        if (sheetRaised == raised) return
        sheetRaised = raised
        lastState?.let { renderStatus(it) }
        applyListLayout()
    }

    /** Deja la hoja en reposo: la altura a la que se ve el carrusel o el principio del contenido. */
    private fun restSheet() {
        // Con la calle abierta la hoja está escondida, y así se queda.
        if (streetPane?.isOpen == true) return
        sheetBehavior?.state = BottomSheetBehavior.STATE_HALF_EXPANDED
    }

    /** Baja la hoja a reposo si estaba arriba; si el usuario la dejó al mínimo, se respeta. */
    private fun lowerSheet() {
        if (sheetBehavior?.state != BottomSheetBehavior.STATE_COLLAPSED) restSheet()
    }

    /**
     * Ajusta al contenido las dos alturas bajas de la hoja.
     *
     * - Mínima: solo el asa y el título, para dejar casi todo el mapa a la vista.
     * - De reposo: hasta el final del carrusel. La cabecera no mide siempre lo
     *   mismo (el aviso de ubicación puede ocupar dos líneas), así que se
     *   calcula a partir de dónde empieza la lista.
     */
    private fun fitSheet() {
        val binding = _binding ?: return
        val behavior = sheetBehavior ?: return
        val parentHeight = binding.root.height
        if (parentHeight == 0) return
        val density = resources.displayMetrics.density
        val listShown = binding.listContent.isVisible && binding.routesRecycler.isLaidOut

        val peek = if (listShown) {
            binding.listContent.top + binding.sheetHeader.bottom + (MINI_PADDING_DP * density).toInt()
        } else {
            (MINI_PEEK_DP * density).toInt()
        }
        if (behavior.peekHeight != peek) behavior.peekHeight = peek

        val ratio = if (listShown && hasCarousel()) {
            val listTop = binding.listContent.top + (binding.routesRecycler.parent as View).top
            (listTop + CAROUSEL_HEIGHT_DP * density) / parentHeight
        } else {
            DEFAULT_REST_RATIO
        }.coerceIn(MIN_REST_RATIO, MAX_REST_RATIO)
        if (kotlin.math.abs(behavior.halfExpandedRatio - ratio) > REST_RATIO_TOLERANCE) {
            behavior.halfExpandedRatio = ratio
            // En reposo, la hoja se recoloca a la altura nueva.
            if (behavior.state == BottomSheetBehavior.STATE_HALF_EXPANDED) restSheet()
        }
    }

    /** Explica qué muestra la lista y, si falta la ubicación, cómo activarla. */
    private fun renderStatus(state: HomeState) {
        val binding = _binding ?: return
        val context = requireContext()
        val search = state.search

        // Con ubicación, o mientras llega, el subtítulo del encabezado ya lo dice.
        val status = when (state.mode) {
            HomeMode.NEARBY, HomeMode.NONE_NEARBY, HomeMode.WAITING_LOCATION -> null
            HomeMode.NO_PERMISSION -> getString(R.string.location_status_no_permission)
            HomeMode.LOCATION_OFF -> getString(R.string.location_status_off)
            HomeMode.PLACE_SEARCHING -> getString(R.string.search_searching)
            HomeMode.PLACE_RESULTS -> when {
                state.places.isEmpty() -> null
                (search as? PlaceSearchState.Results)?.offline == true -> getString(R.string.search_offline)
                else -> getString(R.string.search_pick_place)
            }
            HomeMode.PLACE_ROUTES -> if (!context.hasAnyLocationPermission() || !context.isLocationEnabled()) {
                getString(R.string.destination_needs_location)
            } else getString(R.string.location_status_waiting)
            // Nadie publica los horarios de los micros de Trujillo: conviene decirlo.
            // Con la hoja en reposo no cabe sin amontonar; se lee al subirla.
            HomeMode.PLACE_JOURNEYS ->
                getString(R.string.journey_status).takeIf { sheetRaised && state.journeys.isNotEmpty() }
        }
        binding.statusRow.isVisible = status != null
        binding.statusText.text = status
        binding.statusIcon.setImageResource(
            if (search == PlaceSearchState.Idle) R.drawable.ic_my_location else R.drawable.ic_place
        )

        val action = when (state.mode) {
            HomeMode.NO_PERMISSION ->
                if (viewModel.locationPermanentlyDenied) R.string.location_action_settings
                else R.string.location_action_enable
            HomeMode.LOCATION_OFF -> R.string.location_action_settings
            HomeMode.PLACE_ROUTES -> when {
                !context.hasAnyLocationPermission() -> if (viewModel.locationPermanentlyDenied) {
                    R.string.location_action_settings
                } else R.string.location_action_enable
                !context.isLocationEnabled() -> R.string.location_action_settings
                else -> null
            }
            else -> null
        }
        binding.btnStatusAction.isVisible = action != null
        action?.let { binding.btnStatusAction.setText(it) }
    }

    private fun onStatusAction() {
        val context = requireContext()
        when {
            !context.hasAnyLocationPermission() ->
                if (viewModel.locationPermanentlyDenied) openAppSettings() else askForLocation()

            !context.isLocationEnabled() ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    // --- Dibujo sin ruta elegida ---

    /**
     * Sin ruta elegida el mapa queda limpio: las rutas se eligen en la lista
     * de abajo y solo entonces se dibuja la suya. Con todas trazadas a la vez
     * no se distinguía ninguna.
     */
    private fun drawRoutes() {
        val layer = routeLayer ?: return
        layer.items.clear()
        binding.mapView.invalidate()
    }

    /** Lugares encontrados, o el lugar elegido destacado. */
    private fun drawPlaces(state: HomeState) {
        val layer = placeLayer ?: return
        val key = state.search to (state.selected == null)
        if (key == drawnPlacesKey) return
        drawnPlacesKey = key

        val map = binding.mapView
        layer.items.clear()
        when (val search = state.search) {
            is PlaceSearchState.Results ->
                if (state.selected == null) search.places.forEach { layer.add(placeMarker(map, it, chosen = false)) }
            is PlaceSearchState.Chosen -> {
                // Con una salida elegida a mano, también se marca de dónde se parte.
                search.origin?.let { layer.add(placeMarker(map, it, chosen = false, selectable = false)) }
                layer.add(placeMarker(map, search.place, chosen = true))
            }
            else -> Unit
        }
        map.invalidate()
    }

    private fun placeMarker(
        map: MapView,
        place: Place,
        chosen: Boolean,
        selectable: Boolean = !chosen
    ) = Marker(map).apply {
        position = GeoPoint(place.latitude, place.longitude)
        icon = MapMarkers.place(requireContext(), chosen)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        setOnMarkerClickListener { _, _ ->
            if (selectable) choosePlace(place) else showMessage(place.name)
            true
        }
    }

    /** Encuadra los lugares al buscar y vuelve al usuario al terminar la búsqueda. */
    private fun frameWithoutSelection(state: HomeState, previous: HomeState?) {
        val closedRoute = previous?.selected != null

        when (val search = state.search) {
            is PlaceSearchState.Results, is PlaceSearchState.Chosen -> {
                if (search == framedSearch && !closedRoute) return
                framedSearch = search
                // Al volver a la pestaña, el mapa ya recuerda dónde estaba.
                if (previous == null && viewModel.camera != null) return

                val points = if (search is PlaceSearchState.Chosen) {
                    listOf(GeoPoint(search.place.latitude, search.place.longitude)) +
                        state.items.mapNotNull { it.nearby?.boarding }.map { GeoPoint(it.latitude, it.longitude) }
                } else {
                    (search as PlaceSearchState.Results).places.map { GeoPoint(it.latitude, it.longitude) }
                }
                if (points.isEmpty()) return
                locationOverlay?.disableFollowLocation()
                scheduleFrame(points)
            }

            is PlaceSearchState.Searching -> Unit

            PlaceSearchState.Idle -> {
                framedSearch = null
                val leftSearch = previous != null && previous.search != PlaceSearchState.Idle
                if (leftSearch || closedRoute) returnToUser()
            }
        }
    }

    // --- Ruta elegida ---

    private fun selectRoute(route: BusRoute) {
        hideKeyboard()
        viewModel.selectRoute(route.id)
    }

    /** Traza la ruta elegida por sus calles y el camino a pie hasta la parada. */
    private fun drawSelected(selected: SelectedRoute) {
        val map = binding.mapView
        val layer = routeLayer ?: return
        val context = requireContext()
        val color = routeColorOf(selected.route)
        val casing = context.themeColor(com.google.android.material.R.attr.colorSurface)
        val primary = context.themeColor(com.google.android.material.R.attr.colorPrimary)

        layer.items.clear()

        // La ruta, con un borde claro para que se lea sobre cualquier calle.
        val routePoints = selected.routeLine.map { it.toGeoPoint() }
        layer.add(line(map, routePoints, casing, 10f))
        layer.add(line(map, routePoints, color, 6f))

        // Los puntitos por donde caminar hasta la parada.
        selected.walkLine?.let { walk ->
            val walkPoints = walk.map { it.toGeoPoint() }
            layer.add(line(map, walkPoints, casing, 8f, dotted = true))
            layer.add(line(map, walkPoints, primary, 5f, dotted = true))
        }

        val stops = selected.route.stops
        // Donde sube el usuario: una referencia o cualquier punto de la calle por la que pasa.
        val boarding = selected.boarding?.boarding
        stops.forEachIndexed { index, stop ->
            if (stop == boarding) return@forEachIndexed
            val style = if (index == 0 || index == stops.lastIndex) StopMarkerStyle.ENDPOINT else StopMarkerStyle.NORMAL
            layer.add(stopMarker(map, stop, index, color, style))
        }
        // Al final para que quede encima.
        if (boarding != null) {
            layer.add(ridePointMarker(map, selected.route, boarding, color, StopMarkerStyle.SELECTED))
        }

        map.invalidate()
    }

    private fun stopMarker(map: MapView, stop: Stop, index: Int, @ColorInt color: Int, style: StopMarkerStyle) =
        Marker(map).apply {
            position = GeoPoint(stop.latitude, stop.longitude)
            icon = MapMarkers.numberedStop(requireContext(), index + 1, color, style)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { _, _ ->
                showMessage(getString(R.string.stop_marker, stop.name))
                true
            }
        }

    /**
     * Encuadra la ruta al elegirla, con el usuario dentro si está a una
     * distancia razonable. Vuelve a encuadrar cuando llega el trazado por
     * calles o el camino a pie, pero solo si el usuario no ha tocado el mapa
     * entretanto: si se acercó a mirar algo, no se lo quitamos.
     */
    private fun frameSelectionIfNeeded(
        selected: SelectedRoute,
        previous: SelectedRoute?,
        user: UserPosition?,
        firstRender: Boolean
    ) {
        val routeChanged = previous?.route?.id != selected.route.id
        if (routeChanged) selectionStartedAt = SystemClock.elapsedRealtime()
        if (routeChanged && firstRender && viewModel.camera != null) return

        val streetsArrived = !routeChanged && selected.followsStreets && previous?.followsStreets == false
        // Una ruta grabada cambia de trazado al ajustarse a las calles, y puede
        // ocupar mucho más que el recorrido limpio que se encuadró al elegirla.
        val lineChanged = !routeChanged && previous?.routeLine != selected.routeLine
        val walkArrived = !routeChanged && previous?.walkLine == null && selected.walkLine != null
        val userMovedMap = lastGestureAt > selectionStartedAt
        if (!routeChanged && (userMovedMap || !(streetsArrived || lineChanged || walkArrived))) return

        locationOverlay?.disableFollowLocation()

        val points = buildList {
            addAll(selected.routeLine.map { it.toGeoPoint() })
            selected.walkLine?.forEach { add(it.toGeoPoint()) }
            val boardingMeters = selected.boarding?.distanceMeters ?: Double.MAX_VALUE
            if (user != null && boardingMeters <= FRAME_USER_MAX_METERS) add(GeoPoint(user.latitude, user.longitude))
        }
        scheduleFrame(points)
    }

    /**
     * Avisa a alguien de en qué micro va uno: "Voy en el California C, me bajo
     * en Av. España, llego en unos 20 min", con el enlace al recorrido. Con el
     * aviso de llegada activo se sabe dónde se baja y cuánto falta; sin él, solo
     * el micro. Va a WhatsApp si está instalado y, si no, a la app que se elija.
     */
    private fun shareTrip(route: BusRoute) {
        val arrival = ArrivalAlarmService.state.value
        val target = arrival.target?.takeIf { it.routeId == route.id }
        val meters = arrival.distanceMeters
        val text = when {
            target != null && meters != null -> getString(
                R.string.trip_share_arriving,
                route.name,
                target.stopName,
                kotlin.math.ceil(meters / TRIP_METERS_PER_MINUTE).toInt().coerceAtLeast(1)
            )
            target != null -> getString(R.string.trip_share_stop, route.name, target.stopName)
            else -> getString(R.string.trip_share_riding, route.name, route.subtitle)
        }
        offerShare(TripShare(text, route.id, route.name, target?.stopName, target?.latitude, target?.longitude))
    }

    /** Lo que se comparte de un viaje: el mensaje y, para el vivo, el micro y dónde se baja. */
    private data class TripShare(
        val text: String,
        val routeId: String,
        val routeName: String,
        val stopName: String? = null,
        val stopLatitude: Double? = null,
        val stopLongitude: Double? = null
    )

    /**
     * Pregunta cómo compartir: el mensaje de siempre o el viaje en vivo, con un
     * enlace en el que se ve por dónde va (Premium). Con un viaje ya en vivo se
     * ofrece reenviar su enlace o dejar de compartirlo.
     */
    private fun offerShare(share: TripShare) {
        // Sin servidor o sin web no hay dónde ver el viaje: queda el mensaje.
        if (!ApiClient.isConfigured || SharedLink.forTrip("x") == null) {
            sendTrip(share.text, share.routeId)
            return
        }
        val live = LiveTripService.state.value
        val options: List<Pair<Int, () -> Unit>> = if (live != null) {
            listOf(
                R.string.live_trip_resend to { sendLiveLink(share.text, live.id) },
                R.string.trip_share_message to { sendTrip(share.text, share.routeId) },
                R.string.live_trip_stop to { stopLiveTrip() }
            )
        } else {
            listOf(
                R.string.trip_share_message to { sendTrip(share.text, share.routeId) },
                (if (Premium.active) R.string.live_trip_start else R.string.live_trip_start_premium) to
                    { requestLiveTrip(share) }
            )
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.trip_share)
            .setItems(options.map { getString(it.first) }.toTypedArray()) { _, which -> options[which].second() }
            .show()
    }

    /** Comprueba que se puede compartir en vivo (Premium, ubicación) y lo empieza. */
    private fun requestLiveTrip(share: TripShare) {
        val context = context ?: return
        if (!Premium.active) {
            PremiumSheet.show(childFragmentManager)
            return
        }
        if (!context.isLocationEnabled()) {
            showLongMessage(getString(R.string.arrival_location_off))
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        val missing = buildList {
            if (!context.hasAnyLocationPermission()) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            // Sin notificaciones no se vería que se está compartiendo.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isEmpty()) {
            startLiveTrip(share)
        } else {
            pendingLiveTrip = share
            requestLivePermissions.launch(missing.toTypedArray())
        }
    }

    private fun startLiveTrip(share: TripShare) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = LiveTripService.create(share.routeId, share.stopName, share.stopLatitude, share.stopLongitude)
            val context = context ?: return@launch
            when (result) {
                is ApiResult.Success -> {
                    val trip = LiveTrip(result.data, share.routeName, share.stopLatitude, share.stopLongitude)
                    val started = runCatching { LiveTripService.start(context.applicationContext, trip) }.isSuccess
                    if (started) sendLiveLink(share.text, trip.id) else showLongMessage(getString(R.string.live_trip_failed))
                }
                // El motivo que da el servidor ya viene listo para mostrar.
                is ApiResult.Failure -> showLongMessage(
                    result.message?.takeIf { result.code != null } ?: getString(R.string.live_trip_failed)
                )
            }
        }
    }

    private fun stopLiveTrip() {
        val context = context ?: return
        LiveTripService.stop(context.applicationContext)
        showMessage(getString(R.string.live_trip_stopped))
    }

    /** Manda [text] con el enlace al recorrido de [routeId] y la firma de la app. */
    private fun sendTrip(text: String, routeId: String) = sendMessage(
        text,
        SharedLink.forRoute(routeId)?.let { getString(R.string.trip_share_link, it) }
    )

    /** Manda [text] con el enlace en el que se ve el viaje en vivo. */
    private fun sendLiveLink(text: String, tripId: String) = sendMessage(
        text,
        SharedLink.forTrip(tripId)?.let { getString(R.string.live_trip_link, it) }
    )

    private fun sendMessage(text: String, link: String?) {
        val message = listOfNotNull(text, link, getString(R.string.trip_share_footer)).joinToString("\n")

        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
        try {
            startActivity(Intent(send).setPackage(WHATSAPP_PACKAGE))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent.createChooser(send, getString(R.string.trip_share)))
        }
    }

    private fun openRouteDetail(route: BusRoute) {
        findNavController().navigate(R.id.action_routes_to_route_detail, RouteDetailFragment.args(route.id))
    }

    // --- Con un destino elegido: las acciones del viaje y guardar el lugar ---

    /**
     * Tocando el mapa o buscando un lugar se llega a un viaje, no al panel de
     * una ruta: aquí se le da lo mismo que ofrece ese panel (el aviso de
     * llegada, la ficha del micro y compartir) y el botón de guardar el lugar.
     */
    private fun renderPlaceActions() {
        val binding = _binding ?: return
        val state = lastState ?: return
        val place = (state.search as? PlaceSearchState.Chosen)?.place
        val rides = state.chosenJourney?.rides.orEmpty()

        binding.placeActions.isVisible = place != null
        // Andando no hay micro del que avisar ni que enseñar: se apagan, pero
        // siguen en su sitio para que la fila no cambie de forma.
        binding.btnJourneyArrival.isEnabled = rides.isNotEmpty()
        binding.btnJourneyDetail.isEnabled = rides.isNotEmpty()
        binding.btnJourneyShare.isEnabled = rides.isNotEmpty()
        binding.btnJourneyArrival.setText(
            if (arrivalOn(rides)) R.string.journey_arrival_cancel else R.string.journey_arrival_enable
        )

        val saved = place?.let { favoriteAt(it) } != null
        binding.btnSavePlace.setText(if (saved) R.string.place_saved else R.string.place_save)
        binding.btnSavePlace.setIconResource(if (saved) R.drawable.ic_star else R.drawable.ic_star_outline)
    }

    /** El aviso de llegada activo es de uno de estos micros. */
    private fun arrivalOn(rides: List<JourneyLeg.Ride>): Boolean {
        val routeId = ArrivalAlarmService.state.value.target?.routeId ?: return false
        return rides.any { it.route.id == routeId }
    }

    /**
     * Activa el aviso donde el viaje dice que hay que bajarse, sin tener que
     * marcarlo en el mapa. Con transbordo se pregunta en cuál de las bajadas.
     */
    private fun onJourneyArrival() {
        val state = lastState ?: return
        val rides = state.chosenJourney?.rides.orEmpty()
        if (arrivalOn(rides)) {
            cancelArrival()
            return
        }
        // Con la guía hablada no se pregunta en cuál bajada: se va avisando de
        // todas, una detrás de otra, y de qué micro tomar en cada transbordo.
        val journey = state.chosenJourney
        if (journey != null && rides.isNotEmpty() && Premium.active && AppSettings.get(requireContext()).voiceGuide) {
            requestArrival(guidedTarget(state, journey))
            return
        }
        pickRide(R.string.journey_arrival_which, { ride ->
            getString(R.string.journey_arrival_option, ride.route.name, exitName(ride, state))
        }) { ride ->
            requestArrival(
                ArrivalTarget(
                    routeId = ride.route.id,
                    routeName = ride.route.name,
                    stopName = exitName(ride, state),
                    latitude = ride.to.latitude,
                    longitude = ride.to.longitude
                )
            )
        }
    }

    /**
     * El viaje entero para la guía hablada: la bajada de cada micro encadenada
     * con la siguiente, y lo que se dice al empezar (hasta dónde caminar y qué
     * micro tomar).
     */
    private fun guidedTarget(state: HomeState, journey: Journey): ArrivalTarget {
        val rides = journey.rides
        val first = rides.first()
        val exit = exitName(first, state)
        val walk = journey.legs.firstOrNull() as? JourneyLeg.Walk
        val boarding = first.from.name.takeIf { it.isNotBlank() && first.fromKind != RidePoint.ON_ROUTE }
        val intro = when {
            boarding == null -> getString(R.string.voice_guide_board_plain, first.route.name, exit)
            walk != null && walk.minutes > 0 ->
                getString(R.string.voice_guide_walk, walk.minutes, boarding, first.route.name, exit)
            else -> getString(R.string.voice_guide_board, first.route.name, boarding, exit)
        }
        return rides.foldRight(null as ArrivalTarget?) { ride, following ->
            ArrivalTarget(
                routeId = ride.route.id,
                routeName = ride.route.name,
                stopName = exitName(ride, state),
                latitude = ride.to.latitude,
                longitude = ride.to.longitude,
                next = following,
                intro = intro.takeIf { ride === first }
            )
        }!!
    }

    /**
     * Cómo llamar al sitio donde se baja de [ride]. Un punto del recorrido
     * lejos de toda parada no tiene nombre propio: si es la última bajada, vale
     * el del destino.
     */
    private fun exitName(ride: JourneyLeg.Ride, state: HomeState): String {
        val place = (state.search as? PlaceSearchState.Chosen)?.place
        val last = ride === state.chosenJourney?.rides?.lastOrNull()
        return when {
            ride.toKind != RidePoint.ON_ROUTE && ride.to.name.isNotBlank() -> ride.to.name
            last && place != null && place.name != getString(R.string.destination_map) -> place.name
            // Sin nada mejor, la parada conocida más próxima sirve de referencia.
            ride.to.name.isNotBlank() -> ride.to.name
            else -> getString(R.string.arrival_pick_point)
        }
    }

    /**
     * Uno de los micros del viaje que se ve en el mapa. Con uno solo no hay nada
     * que preguntar; con transbordo se elige en una lista.
     */
    private fun pickRide(
        title: Int,
        label: (JourneyLeg.Ride) -> String = { it.route.vehicleName.ifBlank { it.route.name } },
        onPicked: (JourneyLeg.Ride) -> Unit
    ) {
        val rides = lastState?.chosenJourney?.rides.orEmpty()
        when (rides.size) {
            0 -> Unit
            1 -> onPicked(rides.single())
            else -> MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setItems(rides.map(label).toTypedArray()) { _, which -> onPicked(rides[which]) }
                .show()
        }
    }

    /** "Voy en el California y me bajo en Av. España. Llego en unos 25 min." */
    private fun shareJourney() {
        val state = lastState ?: return
        val journey = state.chosenJourney ?: return
        val rides = journey.rides
        if (rides.isEmpty()) return
        val exit = rides.last()
        offerShare(
            TripShare(
                text = getString(
                    R.string.trip_share_arriving,
                    rides.joinToString(" + ") { it.route.name },
                    exitName(exit, state),
                    journey.totalMinutes
                ),
                routeId = rides.first().route.id,
                routeName = rides.joinToString(" + ") { it.route.name },
                stopName = exitName(exit, state),
                stopLatitude = exit.to.latitude,
                stopLongitude = exit.to.longitude
            )
        )
    }

    /** El favorito guardado en ese mismo sitio, si lo hay. */
    private fun favoriteAt(place: Place): SavedPlaceStore.Favorite? = favorites.firstOrNull {
        NearbyRoutes.distanceMeters(it.place.latitude, it.place.longitude, place.latitude, place.longitude) <=
            SAME_PLACE_METERS
    }

    /** Guarda el destino elegido como favorito o, si ya lo era, ofrece quitarlo. */
    private fun onSavePlace() {
        val place = (lastState?.search as? PlaceSearchState.Chosen)?.place ?: return
        val context = requireContext()
        val store = SavedPlaceStore.get(context)

        val saved = favoriteAt(place)
        if (saved != null) {
            MaterialAlertDialogBuilder(context)
                .setTitle(getString(R.string.saved_place_title, getString(SavedPlaceStyle.label(saved.kind)), saved.place.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.saved_place_remove) { _, _ ->
                    store.remove(saved)
                    showMessage(getString(R.string.place_removed))
                }
                .show()
            return
        }
        if (!store.hasRoom) {
            // Solo la cuenta gratuita tiene tope: lo que sigue es de Premium.
            PremiumSheet.show(childFragmentManager)
            return
        }
        SavedPlaceStyle.pickKind(context, favorites) { kind -> savePlace(kind, place) }
    }

    private fun savePlace(kind: SavedPlaceStore.Kind, place: Place) {
        val store = SavedPlaceStore.get(requireContext())
        val label = getString(SavedPlaceStyle.label(kind))
        viewLifecycleOwner.lifecycleScope.launch {
            // Un punto tocado en el mapa no tiene nombre: se guarda con el de su calle.
            val named = if (place.name == getString(R.string.destination_map)) {
                val street = PlaceSearch.streetName(place.latitude, place.longitude)
                place.copy(name = street ?: getString(R.string.place_point))
            } else {
                place
            }
            if (store.save(kind, named)) showMessage(getString(R.string.saved_place_done, label))
        }
    }

    /** Los favoritos sobre el mapa, cada uno con su icono: al tocar uno se va hacia él. */
    private fun drawFavorites() {
        val binding = _binding ?: return
        val layer = favoriteLayer ?: return
        val map = binding.mapView
        val context = requireContext()
        layer.items.clear()
        favorites.forEach { favorite ->
            layer.add(
                Marker(map).apply {
                    position = GeoPoint(favorite.place.latitude, favorite.place.longitude)
                    icon = MapMarkers.favorite(context, SavedPlaceStyle.icon(favorite.kind))
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ ->
                        // Eligiendo la bajada, un toque aquí es un toque en el mapa.
                        if (picking != null) onMapTapped(position) else choosePlace(favorite.place)
                        true
                    }
                }
            )
        }
        map.invalidate()
    }

    /** Borrar una ruta propia no se puede deshacer: se pregunta antes. */
    private fun confirmDelete(route: BusRoute) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.route_delete_title)
            .setMessage(getString(R.string.route_delete_message, route.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.route_delete_confirm) { _, _ ->
                viewModel.clearSelection()
                RouteRepository.remove(route.id)
            }
            .show()
    }

    private fun renderSelected(selected: SelectedRoute) {
        val route = selected.route
        val color = routeColorOf(route)

        binding.selectedBadge.text = route.letter
        binding.selectedBadge.backgroundTintList = ColorStateList.valueOf(color)
        binding.selectedName.text = route.name
        binding.selectedSubtitle.text = route.subtitle
        routeInfo?.show(route)
        binding.btnDeleteRoute.isVisible = RouteRepository.isOwn(route.id)

        binding.alertsOnRoute.isVisible = selected.alertsOnRoute > 0
        if (selected.alertsOnRoute > 0) {
            binding.alertsOnRoute.text = resources.getQuantityString(
                R.plurals.selected_alerts_on_route, selected.alertsOnRoute, selected.alertsOnRoute
            )
        }

        renderWalk(selected)
        renderArrivalViews()
        binding.approxNote.isVisible = !selected.followsStreets && !selected.loadingStreets
        renderStops(selected, color)
    }

    /** Cuánto caminar hasta la parada, o qué falta para saberlo. */
    private fun renderWalk(selected: SelectedRoute) {
        val context = requireContext()
        val boarding = selected.boarding
        binding.btnWalkAction.isVisible = false

        binding.walkText.text = when {
            boarding == null && (!context.hasAnyLocationPermission() || !context.isLocationEnabled()) -> {
                binding.btnWalkAction.isVisible = true
                binding.btnWalkAction.setText(
                    if (context.hasAnyLocationPermission() || viewModel.locationPermanentlyDenied) {
                        R.string.location_action_settings
                    } else {
                        R.string.location_action_enable
                    }
                )
                getString(R.string.selected_no_location)
            }

            boarding == null -> getString(R.string.location_status_waiting)

            boarding.distanceMeters > NearbyRoutes.WALKABLE_METERS -> getString(
                R.string.selected_walk_far,
                context.formatDistance(boarding.distanceMeters),
                context.boardingName(boarding)
            )

            // Está en la calle por la que pasa: no hay que ir a ningún paradero.
            boarding.distanceMeters <= NearbyRoutes.ON_ROUTE_METERS -> getString(R.string.selected_here)

            else -> getString(
                R.string.selected_walk,
                context.formatDistance(selected.walkMeters ?: boarding.distanceMeters),
                selected.walkMinutes ?: boarding.walkingMinutes,
                context.boardingName(boarding)
            )
        }
    }

    private fun renderStops(selected: SelectedRoute, @ColorInt color: Int) {
        val key = "${selected.route.id}|${selected.route.stops.size}|${selected.boarding?.boarding}"
        if (key == renderedStopsKey) return
        renderedStopsKey = key

        val inflater = LayoutInflater.from(requireContext())
        val primary = requireContext().themeColor(com.google.android.material.R.attr.colorPrimary)
        val container = binding.stopsContainer
        container.removeAllViews()

        selected.route.stops.forEachIndexed { index, stop ->
            val row = ItemRouteStopBinding.inflate(inflater, container, false)
            row.stopNumber.text = (index + 1).toString()
            row.stopNumber.backgroundTintList = ColorStateList.valueOf(color)
            row.stopName.text = stop.name
            row.stopTag.isVisible = stop == selected.boarding?.boarding
            row.stopTag.backgroundTintList = ColorStateList.valueOf(primary)
            row.root.setOnClickListener { focusPoint(GeoPoint(stop.latitude, stop.longitude), STOP_ZOOM) }
            container.addView(row.root)
        }
    }

    /** Baja la hoja y lleva el mapa hasta un punto. */
    private fun focusPoint(point: GeoPoint, zoom: Double) {
        val behavior = sheetBehavior
        val settling = behavior != null && behavior.state == BottomSheetBehavior.STATE_EXPANDED
        lowerSheet()
        cancelPendingFrame()

        binding.mapView.postDelayed({
            val binding = _binding ?: return@postDelayed
            val band = visibleBand() ?: return@postDelayed
            val map = binding.mapView
            // Quien pide ver un punto concreto no quiere que el GPS lo arrastre de vuelta.
            locationOverlay?.disableFollowLocation()
            map.centerOnBand(band)
            map.controller.stopAnimation(false)
            map.controller.animateTo(point, maxOf(map.zoomLevelDouble, zoom), CAMERA_ANIMATION_MS)
        }, if (settling) SHEET_SETTLE_MS else 0L)
    }

    /** Cambia la hoja entre la lista y el panel de la ruta elegida. */
    private fun showRoutePanel(show: Boolean) {
        if (binding.routePanel.isVisible == show) return

        binding.routePanel.isVisible = show
        binding.listContent.isVisible = !show
        // La hoja se arrastra con el contenido desplazable que esté a la vista.
        binding.routesRecycler.isNestedScrollingEnabled = !show
        binding.routePanel.isNestedScrollingEnabled = show
        binding.sheet.requestLayout()

        binding.attribution.setText(if (show) R.string.map_attribution_routing else R.string.map_attribution)

        if (show) {
            binding.routePanel.scrollTo(0, 0)
            // La ruta se ve en el mapa: la hoja queda en reposo para dejarle sitio.
            restSheet()
        } else {
            renderedStopsKey = null
        }
    }

    private fun openAlertsTab() {
        requireActivity().findViewById<BottomNavigationView>(R.id.bottomNav)?.selectedItemId = R.id.reportsFragment
    }

    // --- Cómo llegar: el viaje elegido en el mapa ---

    /**
     * Dibuja el viaje completo: cada micro con su color entre la parada donde
     * se sube y donde se baja, y los tramos a pie con la fila de puntitos.
     */
    private fun drawJourney(journey: Journey, state: HomeState) {
        val map = binding.mapView
        val layer = routeLayer ?: return
        val context = requireContext()
        val casing = context.themeColor(com.google.android.material.R.attr.colorSurface)
        val primary = context.themeColor(com.google.android.material.R.attr.colorPrimary)

        layer.items.clear()

        // Los tramos a pie unen los puntos que ya conocemos: el usuario, cada
        // parada y el destino. Primero en línea recta; en cuanto llega el camino
        // por las calles, se dibuja ese.
        journeyWalks(journey, state).forEach { walk ->
            val street = streetWalk(walk)
            if (street == null) requestStreetWalk(walk)
            val points = (street ?: walk).map { it.toGeoPoint() }
            layer.add(line(map, points, casing, 8f, dotted = true))
            layer.add(line(map, points, primary, 5f, dotted = true))
        }

        journey.rides.forEachIndexed { index, ride ->
            val points = ride.line.map { it.toGeoPoint() }
            val color = routeColorOf(ride.route)
            if (points.size >= 2) {
                layer.add(line(map, points, casing, 10f))
                layer.add(line(map, points, color, 6f))
            }

            layer.add(ridePointMarker(map, ride.route, ride.from, color, StopMarkerStyle.ENDPOINT))
            // La última bajada es la que importa: se marca más grande.
            val exitStyle = if (index == journey.rides.lastIndex) StopMarkerStyle.SELECTED else StopMarkerStyle.ENDPOINT
            layer.add(ridePointMarker(map, ride.route, ride.to, color, exitStyle))
        }

        map.invalidate()
    }

    /**
     * Dónde se sube o se baja. Una parada del catálogo lleva su número; un
     * punto cualquiera del recorrido, solo un punto del color de la línea.
     */
    private fun ridePointMarker(
        map: MapView,
        route: BusRoute,
        point: Stop,
        @ColorInt color: Int,
        style: StopMarkerStyle
    ): Marker {
        val index = route.stops.indexOf(point)
        if (index >= 0) return stopMarker(map, point, index, color, style)
        return Marker(map).apply {
            position = GeoPoint(point.latitude, point.longitude)
            icon = MapMarkers.stopDot(requireContext(), color)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { _, _ -> true }
        }
    }

    /** El camino por las calles de un tramo a pie, si ya se trazó desde ahí cerca. */
    private fun streetWalk(walk: List<GeoCoordinate>): List<GeoCoordinate>? {
        val from = walk.first()
        val to = walk.last()
        val known = streetWalks.lastOrNull {
            it.to == to &&
                NearbyRoutes.distanceMeters(it.from.latitude, it.from.longitude, from.latitude, from.longitude) <=
                STREET_WALK_REUSE_METERS
        } ?: return null
        return listOf(from) + known.points + to
    }

    /** Pide el camino a pie por las calles y, al llegar, vuelve a dibujar el viaje. */
    private fun requestStreetWalk(walk: List<GeoCoordinate>) {
        val from = walk.first()
        val to = walk.last()
        val key = String.format(
            Locale.US, "%.4f,%.4f,%.5f,%.5f", from.latitude, from.longitude, to.latitude, to.longitude
        )
        // Si el servicio no responde no se insiste: se queda la línea recta.
        if (!askedWalks.add(key)) return
        val router = StreetRouter.get(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val path = router.walkingPath(
                UserPosition(from.latitude, from.longitude), Stop("", to.latitude, to.longitude)
            ) ?: return@launch
            if (_binding == null) return@launch
            streetWalks.addLast(StreetWalk(from, to, path.points))
            while (streetWalks.size > MAX_STREET_WALKS) streetWalks.removeFirst()

            val state = lastState ?: return@launch
            val journey = state.chosenJourney
            if (state.selected == null && journey != null) drawJourney(journey, state)
        }
    }

    /** Los tramos a pie del viaje, como pares de puntos: de dónde a dónde se camina. */
    private fun journeyWalks(journey: Journey, state: HomeState): List<List<GeoCoordinate>> {
        // Se sale del origen elegido o, si no hay, de donde está el usuario.
        val user = state.journeyStart
        val destination = (state.search as? PlaceSearchState.Chosen)?.place
            ?.let { GeoCoordinate(it.latitude, it.longitude) }
        val rides = journey.rides

        if (rides.isEmpty()) {
            // Viaje a pie: del usuario al destino.
            return listOfNotNull(
                if (user != null && destination != null) listOf(user, destination) else null
            )
        }

        val walks = mutableListOf<List<GeoCoordinate>>()
        val first = rides.first().from
        if (user != null) walks += listOf(user, GeoCoordinate(first.latitude, first.longitude))

        rides.zipWithNext { previous, next ->
            walks += listOf(
                GeoCoordinate(previous.to.latitude, previous.to.longitude),
                GeoCoordinate(next.from.latitude, next.from.longitude)
            )
        }

        val last = rides.last().to
        if (destination != null) walks += listOf(GeoCoordinate(last.latitude, last.longitude), destination)
        return walks.filter { it.size == 2 }
    }

    private fun frameJourneyIfNeeded(journey: Journey, state: HomeState) {
        // Se encuadra una vez por viaje y destino: al moverse el usuario cambian
        // los metros, pero no hay que quitarle el mapa de las manos.
        val key = Triple(journey.key, (state.search as? PlaceSearchState.Chosen)?.place, state.origin)
        if (key == framedJourney) return
        framedJourney = key

        val points = buildList {
            state.journeyStart?.let { add(it.toGeoPoint()) }
            (state.search as? PlaceSearchState.Chosen)?.place?.let { add(GeoPoint(it.latitude, it.longitude)) }
            journey.rides.forEach { ride ->
                addAll(ride.line.map { it.toGeoPoint() })
            }
        }
        if (points.isEmpty()) return

        locationOverlay?.disableFollowLocation()
        restSheet()
        scheduleFrame(points)
    }

    // --- Alertas en el mapa ---

    private fun drawAlerts(alerts: List<RoadAlert>) {
        val layer = alertLayer ?: return
        if (alerts == drawnAlerts) return
        drawnAlerts = alerts

        val map = binding.mapView
        val context = requireContext()
        layer.items.clear()
        alerts.forEach { alert ->
            layer.add(
                Marker(map).apply {
                    position = GeoPoint(alert.latitude, alert.longitude)
                    icon = MapMarkers.alert(
                        context,
                        AlertStyle.icon(alert.type),
                        ContextCompat.getColor(context, AlertStyle.color(alert.type))
                    )
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ ->
                        showAlertDetails(alert.id)
                        true
                    }
                }
            )
        }
        map.invalidate()
    }

    /** Qué dice la alerta y si sigue ahí: quien pasa por el sitio lo confirma o la descarta. */
    private fun showAlertDetails(alertId: String) {
        val alert = AlertRepository.alerts.value.firstOrNull { it.id == alertId } ?: return
        val context = requireContext()
        val color = ContextCompat.getColor(context, AlertStyle.color(alert.type))

        val lines = buildList {
            add(alert.placeName ?: getString(R.string.alert_unknown_place))
            add(context.formatTimeAgo(AlertRules.minutesAgo(alert, System.currentTimeMillis())))
            alert.comment?.let { add("“$it”") }
            if (alert.confirmations > 0) {
                add(resources.getQuantityString(R.plurals.alert_confirmations, alert.confirmations, alert.confirmations))
            }
        }

        MaterialAlertDialogBuilder(context)
            .setIcon(AppCompatResources.getDrawable(context, AlertStyle.icon(alert.type))?.mutate()?.apply { setTint(color) })
            .setTitle(AlertStyle.label(alert.type))
            .setMessage(lines.joinToString("\n"))
            .setView(alertPhoto(alert))
            .setPositiveButton(R.string.alert_still_there_short) { _, _ ->
                AlertRepository.vote(alert.id, AlertVote.STILL_THERE)
                showMessage(getString(R.string.alert_you_confirmed))
            }
            .setNegativeButton(R.string.alert_gone) { _, _ ->
                AlertRepository.vote(alert.id, AlertVote.GONE)
                showMessage(getString(R.string.alert_hidden))
            }
            .setNeutralButton(R.string.alert_close, null)
            .show()
    }

    /** La foto de la alerta para su cuadro, que aparece cuando llega; `null` si no tiene. */
    private fun alertPhoto(alert: RoadAlert): View? {
        if (alert.imageUrl.isNullOrBlank()) return null
        val context = requireContext()
        val density = resources.displayMetrics.density
        val photo = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (ALERT_PHOTO_HEIGHT_DP * density).toInt()
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = getString(R.string.create_alert_photo)
            isVisible = false
        }
        viewLifecycleOwner.lifecycleScope.launch {
            RouteImages.load(alert.imageUrl)?.let {
                photo.setImageBitmap(it)
                photo.isVisible = true
            }
        }
        val margin = (ALERT_PHOTO_MARGIN_DP * density).toInt()
        return FrameLayout(context).apply {
            setPadding(margin, margin / 2, margin, 0)
            addView(photo)
        }
    }

    /** "Ver en el mapa" desde la pestaña de alertas. */
    private fun focusAlert(alertId: String) {
        val alert = AlertRepository.alerts.value.firstOrNull { it.id == alertId } ?: return
        viewModel.clearSelection()
        focusPoint(GeoPoint(alert.latitude, alert.longitude), ALERT_ZOOM)
    }

    // --- Grabación de rutas nuevas ---

    private fun setUpRecording() {
        binding.btnAddRoute.setOnClickListener { onAddRoute() }
        binding.btnFinishRecording.setOnClickListener {
            when (RouteRecorderService.state.value.status) {
                RecorderStatus.RECORDING, RecorderStatus.INTERRUPTED -> RouteRecorderService.finish(requireContext())
                RecorderStatus.FINISHED -> openNewRouteForm()
                RecorderStatus.IDLE -> Unit
            }
        }
        binding.btnDiscardRecording.setOnClickListener { confirmDiscardRecording() }
    }

    private fun onAddRoute() {
        if (RouteRecorderService.state.value.status != RecorderStatus.IDLE) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_route_dialog_title)
            .setMessage(R.string.add_route_dialog_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.add_route_start) { _, _ -> beginRecording() }
            .show()
    }

    private fun beginRecording() {
        val context = context ?: return
        if (!context.isLocationEnabled()) {
            showMessage(getString(R.string.recording_location_off))
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        val missing = buildList {
            if (!context.hasFineLocationPermission()) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isEmpty()) startRecorder() else requestRecordingPermissions.launch(missing.toTypedArray())
    }

    private fun startRecorder() {
        val started = runCatching { RouteRecorderService.start(requireContext()) }.isSuccess
        if (!started) {
            showMessage(getString(R.string.recording_failed))
            return
        }
        lowerSheet()
        startLocationUpdates()
        returnToUser()
    }

    private fun confirmDiscardRecording() {
        val appContext = requireContext().applicationContext
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.recording_discard_title)
            .setMessage(R.string.recording_discard_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.recording_discard_confirm) { _, _ -> RouteRecorderService.discard(appContext) }
            .show()
    }

    private fun openNewRouteForm() {
        val navController = findNavController()
        if (navController.currentDestination?.id == R.id.routesFragment) {
            navController.navigate(R.id.action_routes_to_new_route)
        }
    }

    private fun renderRecorder(recorder: RecorderState) {
        val binding = _binding ?: return
        val context = requireContext()
        val status = recorder.status
        val previous = lastRecorderStatus
        lastRecorderStatus = status
        val draft = recorder.draft

        binding.recordingBar.isVisible = status != RecorderStatus.IDLE
        binding.btnAddRoute.isEnabled = status == RecorderStatus.IDLE

        when (status) {
            RecorderStatus.RECORDING -> {
                binding.recordingTitle.setText(R.string.recording_title)
                val weakSignal = recorder.lastAccuracyMeters
                binding.recordingSummary.text = if (draft == null || draft.points.isEmpty()) {
                    // Sin puntos todavía: o no hay lectura, o la que hay es demasiado imprecisa.
                    if (weakSignal != null) {
                        getString(R.string.recording_weak_gps, weakSignal.toInt())
                    } else {
                        getString(R.string.recording_waiting_gps)
                    }
                } else {
                    getString(
                        R.string.recording_summary,
                        context.formatDistance(draft.distanceMeters),
                        draft.durationMinutes(System.currentTimeMillis()),
                        draft.points.size
                    )
                }
                binding.btnFinishRecording.setText(R.string.recording_finish)
            }
            RecorderStatus.INTERRUPTED -> {
                binding.recordingTitle.setText(R.string.recording_interrupted_title)
                binding.recordingSummary.text = getString(
                    R.string.recording_interrupted_summary,
                    context.formatDistance(draft?.distanceMeters ?: 0.0)
                )
                binding.btnFinishRecording.setText(R.string.recording_save)
            }
            RecorderStatus.FINISHED -> {
                binding.recordingTitle.setText(R.string.recording_pending_title)
                binding.recordingSummary.text = getString(
                    R.string.recording_pending_summary,
                    context.formatDistance(draft?.distanceMeters ?: 0.0)
                )
                binding.btnFinishRecording.setText(R.string.recording_complete)
            }
            RecorderStatus.IDLE -> Unit
        }

        setRecordingDotBlinking(status == RecorderStatus.RECORDING)
        drawRecording(draft.takeIf { status != RecorderStatus.IDLE })

        // Al pulsar "Finalizar" el formulario se abre solo. Si el usuario salió
        // de él sin guardar, la barra le deja volver con "Completar".
        val justFinished = previous == RecorderStatus.RECORDING || previous == RecorderStatus.INTERRUPTED
        if (status == RecorderStatus.FINISHED && justFinished) openNewRouteForm()

        if (recorder.tooShort || recorder.failed) {
            showMessage(getString(if (recorder.failed) R.string.recording_failed else R.string.recording_too_short))
            RouteRecorderService.consumeMessages()
        }
    }

    private fun setRecordingDotBlinking(blinking: Boolean) {
        val dot = binding.recordingDot
        if (blinking && dot.animation == null) {
            dot.startAnimation(
                AlphaAnimation(1f, 0.2f).apply {
                    duration = BLINK_MS
                    repeatMode = Animation.REVERSE
                    repeatCount = Animation.INFINITE
                }
            )
        } else if (!blinking) {
            dot.clearAnimation()
        }
    }

    /** El recorrido que se va grabando, en directo. */
    private fun drawRecording(draft: RecordingDraft?) {
        val layer = recordingLayer ?: return
        val map = binding.mapView
        val context = requireContext()
        layer.items.clear()

        // Limpio también en directo: el GPS en bruto se ve rayado.
        val points = draft?.let { PathCleaner.clean(it.points) }.orEmpty().map { it.toGeoPoint() }
        val color = ContextCompat.getColor(context, R.color.track_recorded)
        if (points.size >= 2) {
            layer.add(line(map, points, context.themeColor(com.google.android.material.R.attr.colorSurface), 9f))
            layer.add(line(map, points, color, 5f))
        }
        points.firstOrNull()?.let { start ->
            layer.add(
                Marker(map).apply {
                    position = start
                    icon = MapMarkers.stopDot(context, color)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ -> true }
                }
            )
        }
        map.invalidate()
    }

    // --- Aviso de llegada ---

    private fun onArrivalAction() {
        val selected = lastState?.selected ?: return
        if (ArrivalAlarmService.state.value.target?.routeId == selected.route.id) {
            cancelArrival()
        } else {
            startPicking(selected)
        }
    }

    // --- Elegir la bajada tocando el mapa ---

    /**
     * Empieza la elección: la hoja se baja para dejar ver el recorrido y el
     * siguiente toque en el mapa marca dónde se baja el usuario.
     *
     * Antes esto era una lista de paradas. Sobre el mapa se ve por dónde va el
     * micro, así que la bajada se elige donde de verdad se quiere bajar, esté o
     * no en una parada del listado.
     */
    private fun startPicking(selected: SelectedRoute) {
        if (DropOffPicker.lineOf(selected.route).size < 2) {
            showLongMessage(getString(R.string.arrival_pick_no_line))
            return
        }
        // Si el usuario venía de buscar un lugar, la bajada se propone sola
        // sobre el recorrido, a la altura de ese lugar; solo hay que moverla si
        // no era ahí.
        val searched = (lastState?.search as? PlaceSearchState.Chosen)?.place
        val suggested = searched
            ?.let { DropOffPicker.pick(GeoCoordinate(it.latitude, it.longitude), selected.route) }

        picking = PickSession(
            routeId = selected.route.id,
            routeName = selected.route.name,
            drop = suggested,
            label = suggested?.stop?.name ?: searched?.name.takeIf { suggested != null }
        )
        lowerSheet()
        backHandler.isEnabled = true
        renderPick()
    }

    private fun onMapTapped(point: GeoPoint) {
        // Con la calle abierta, tocar el mapa es pedir la calle de ese punto.
        if (streetPane?.isOpen == true) {
            showStreet(point)
            return
        }
        savingFavorite?.let { kind ->
            saveFavoriteAt(point, kind)
            return
        }
        val session = picking
        if (session == null) {
            hideKeyboard()
            viewModel.chooseDestination(point.latitude, point.longitude)
            restSheet()
            if (lastState?.user == null) {
                showLongMessage(getString(R.string.destination_needs_location))
                if (!requireContext().hasAnyLocationPermission()) askForLocation()
                else if (!requireContext().isLocationEnabled()) {
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
            return
        }
        val route = RouteRepository.byId(session.routeId) ?: return

        val drop = DropOffPicker.pick(GeoCoordinate(point.latitude, point.longitude), route)
        if (drop == null) {
            showMessage(getString(R.string.arrival_pick_far))
            return
        }

        picking = session.copy(drop = drop, label = drop.stop?.name)
        renderPick()
        if (drop.stop == null) resolveStreetName(drop)
    }

    /** Guarda como favorito el punto tocado, con el nombre de su calle si se sabe. */
    private fun saveFavoriteAt(point: GeoPoint, kind: SavedPlaceStore.Kind) {
        savingFavorite = null
        val label = getString(SavedPlaceStyle.label(kind))
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val street = PlaceSearch.streetName(point.latitude, point.longitude)
            val place = Place(street ?: getString(R.string.saved_place_map_point), "", point.latitude, point.longitude)
            val saved = SavedPlaceStore.get(context).save(kind, place)
            showMessage(getString(if (saved) R.string.saved_place_done else R.string.saved_place_full, label))
        }
    }

    /**
     * Sin parada cerca, el punto se queda sin nombre. Se le pregunta la calle a
     * Nominatim; mientras tanto la tarjeta ya muestra el punto elegido, y si no
     * hay respuesta se queda con un nombre genérico.
     */
    private fun resolveStreetName(drop: DropOff) {
        viewLifecycleOwner.lifecycleScope.launch {
            val street = PlaceSearch.streetName(drop.point.latitude, drop.point.longitude) ?: return@launch
            val session = picking ?: return@launch
            // El usuario pudo tocar en otro sitio mientras se preguntaba.
            if (session.drop?.point != drop.point) return@launch
            picking = session.copy(label = street)
            renderPick()
        }
    }

    private fun renderPick() {
        val binding = _binding ?: return
        val session = picking

        binding.pickCard.isVisible = session != null
        binding.btnPickConfirm.isVisible = session?.drop != null
        binding.pickText.text = when {
            session == null -> ""
            session.drop == null -> getString(R.string.arrival_pick_hint)
            else -> getString(R.string.arrival_pick_chosen, session.labelOrDefault(requireContext()))
        }

        drawPick(session?.drop)
        renderStreetButton()
    }

    private fun drawPick(drop: DropOff?) {
        val binding = _binding ?: return
        val layer = pickLayer ?: return
        layer.items.clear()
        if (drop != null) {
            layer.add(
                Marker(binding.mapView).apply {
                    position = GeoPoint(drop.point.latitude, drop.point.longitude)
                    icon = MapMarkers.dropOff(requireContext())
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ -> true }
                }
            )
        }
        binding.mapView.invalidate()
    }

    private fun confirmPick() {
        val session = picking ?: return
        val drop = session.drop ?: return
        val target = ArrivalTarget(
            routeId = session.routeId,
            routeName = session.routeName,
            stopName = session.labelOrDefault(requireContext()),
            latitude = drop.point.latitude,
            longitude = drop.point.longitude
        )
        stopPicking()
        requestArrival(target)
    }

    private fun stopPicking() {
        if (picking == null) return
        picking = null
        renderPick()
        refreshBackHandler()
    }

    private fun requestArrival(target: ArrivalTarget) {
        val context = context ?: return

        // Si ya está en la parada no tiene sentido esperar: se avisaría al instante.
        lastState?.user?.let { here ->
            val distance = NearbyRoutes.distanceMeters(here.latitude, here.longitude, target.latitude, target.longitude)
            val radius = AppSettings.get(context).arrivalDistanceMeters.toDouble()
            if (distance <= radius) {
                showLongMessage(
                    getString(
                        R.string.arrival_already_there,
                        context.formatDistance(radius),
                        target.stopName
                    )
                )
                return
            }
        }

        if (!context.isLocationEnabled()) {
            showLongMessage(getString(R.string.arrival_location_off))
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        val missing = buildList {
            if (!context.hasAnyLocationPermission()) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isEmpty()) {
            startArrival(target)
        } else {
            pendingArrival = target
            requestArrivalPermissions.launch(missing.toTypedArray())
        }
    }

    private fun startArrival(target: ArrivalTarget) {
        val context = context ?: return
        val started = runCatching { ArrivalAlarmService.start(context.applicationContext, target) }.isSuccess
        if (!started) {
            showLongMessage(getString(R.string.arrival_failed))
            return
        }
        val notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        showLongMessage(
            if (notificationsAllowed) {
                getString(R.string.arrival_started, target.stopName)
            } else {
                getString(R.string.arrival_no_notifications)
            }
        )
    }

    private fun cancelArrival() {
        val context = context ?: return
        if (ArrivalAlarmService.state.value.target == null) return
        ArrivalAlarmService.cancel(context.applicationContext)
        showLongMessage(getString(R.string.arrival_cancelled))
    }

    private fun renderArrival(arrival: ArrivalState) {
        if (_binding == null) return
        renderArrivalViews()
        renderPlaceActions()

        arrival.arrivedAt?.let { target ->
            showLongMessage(getString(R.string.arrival_arrived, target.stopName))
        }
        if (arrival.failed) showLongMessage(getString(R.string.arrival_failed))
        if (arrival.arrivedAt != null || arrival.failed) ArrivalAlarmService.consumeMessages()
    }

    /** La tarjeta del panel de la ruta y la barra de arriba, según el aviso activo. */
    private fun renderArrivalViews() {
        val binding = _binding ?: return
        val context = requireContext()
        val arrival = ArrivalAlarmService.state.value
        val target = arrival.target
        val selected = lastState?.selected
        val distance = arrival.distanceMeters?.let { context.formatDistance(it) }

        // La barra de arriba recuerda el aviso cuando no se está viendo su ruta.
        binding.arrivalBar.isVisible = target != null && selected?.route?.id != target.routeId
        if (target != null) {
            binding.arrivalBarText.text = if (distance == null) {
                getString(R.string.arrival_bar, target.stopName)
            } else {
                getString(R.string.arrival_bar_distance, target.stopName, distance)
            }
        }

        if (selected != null) {
            val activeHere = target?.routeId == selected.route.id
            binding.arrivalText.text = when {
                !activeHere -> getString(R.string.arrival_card_idle)
                distance == null -> getString(R.string.arrival_card_active, target!!.stopName)
                else -> getString(R.string.arrival_card_active_distance, target!!.stopName, distance)
            }
            binding.btnArrivalAction.setText(if (activeHere) R.string.arrival_action_cancel else R.string.arrival_action_enable)
        }
    }

    // --- Hoja ---

    private fun setUpSheet() {
        val behavior = BottomSheetBehavior.from(binding.sheet)
        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                // Solo se puede esconder mientras se mira la calle: de vuelta
                // a la vista, ya no se va arrastrándola hacia abajo.
                if (newState != BottomSheetBehavior.STATE_HIDDEN && newState != BottomSheetBehavior.STATE_SETTLING &&
                    newState != BottomSheetBehavior.STATE_DRAGGING && streetPane?.isOpen != true
                ) {
                    sheetBehavior?.isHideable = false
                }
                when (newState) {
                    BottomSheetBehavior.STATE_COLLAPSED,
                    BottomSheetBehavior.STATE_HALF_EXPANDED,
                    BottomSheetBehavior.STATE_EXPANDED -> updateMapCenterOffset()
                }
                // El carrusel solo vuelve con la hoja ya quieta: mientras se
                // mueve (también cuando cambia su alto) no se decide nada.
                when (newState) {
                    BottomSheetBehavior.STATE_COLLAPSED,
                    BottomSheetBehavior.STATE_HALF_EXPANDED -> setSheetRaised(false)
                    BottomSheetBehavior.STATE_EXPANDED -> setSheetRaised(true)
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                // Con la hoja casi arriba, los botones y la atribución quedarían
                // debajo del buscador: los desvanecemos.
                val binding = _binding ?: return
                // Al subirla con el dedo por encima del reposo, la lista aparece
                // sin esperar a soltar.
                val parent = bottomSheet.parent as View
                val shown = parent.height - bottomSheet.top
                val rest = parent.height * (sheetBehavior?.halfExpandedRatio ?: DEFAULT_REST_RATIO)
                if (shown > rest + RAISED_MARGIN_DP * resources.displayMetrics.density) setSheetRaised(true)
                val alpha = ((0.9f - slideOffset) / 0.4f).coerceIn(0f, 1f)
                binding.mapControls.alpha = alpha
                binding.attribution.alpha = alpha
                binding.streetViewHolder.alpha = alpha
                binding.btnStreetView.isEnabled = alpha > 0.1f
                binding.fabMyLocation.isEnabled = alpha > 0.1f
            }
        })
        sheetBehavior = behavior
        // Subida del todo, la hoja empieza más abajo que la pantalla pero mide lo
        // mismo: lo que le sobra queda fuera, por debajo. Sin este hueco no se
        // alcanza a ver el final de las listas.
        val hidden = behavior.expandedOffset
        binding.routesRecycler.updatePadding(bottom = binding.routesRecycler.paddingBottom + hidden)
        binding.routePanel.updatePadding(bottom = binding.routePanel.paddingBottom + hidden)
        // Se empieza en reposo; la altura mínima es para quien quiera ver solo el mapa.
        behavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.homeState.collect { render(it) } }

                launch { RouteRecorderService.state.collect { renderRecorder(it) } }

                launch { ArrivalAlarmService.state.collect { renderArrival(it) } }
                // Al entrar o salir de una cuenta cambia quién puede grabar rutas.
                launch {
                    AccountRepository.session.collect {
                        renderAddRoute()
                        // Premium pudo llegar o irse: la activación por voz va con él.
                        syncVoiceWake()
                    }
                }
                launch {
                    SavedPlaceStore.get(requireContext()).favorites.collect {
                        favorites = it
                        drawFavorites()
                        renderPlaceActions()
                    }
                }

                // "Ver ruta" desde el asistente, el perfil o una ruta recién guardada.
                launch {
                    MapRouteRequests.pending.collect { routeId ->
                        if (routeId != null) {
                            MapRouteRequests.consume()
                            viewModel.selectRoute(routeId)
                        }
                    }
                }

                // "Ver en el mapa" desde la pestaña de alertas.
                launch {
                    MapAlertRequests.pending.collect { alertId ->
                        if (alertId != null) {
                            MapAlertRequests.consume()
                            focusAlert(alertId)
                        }
                    }
                }
            }
        }
    }

    private fun render(state: HomeState) {
        if (_binding == null) return
        val previous = lastState
        lastState = state

        renderList(state)
        renderStatus(state)
        renderPlaceActions()
        renderStreetButton()
        // Buscar un lugar con la calle abierta es pedir la calle de ese lugar.
        val place = (state.search as? PlaceSearchState.Chosen)?.place
        if (streetPane?.isOpen == true && place != null &&
            place != (previous?.search as? PlaceSearchState.Chosen)?.place
        ) {
            showStreet(GeoPoint(place.latitude, place.longitude))
        }

        val selected = state.selected
        showRoutePanel(selected != null)
        fitSheet()
        // Elegir la bajada solo tiene sentido con su ruta delante.
        if (picking != null && picking?.routeId != selected?.route?.id) stopPicking()
        refreshBackHandler()

        drawPlaces(state)
        val journey = state.chosenJourney
        if (selected != null) {
            renderSelected(selected)
            drawSelected(selected)
            frameSelectionIfNeeded(selected, previous?.selected, state.user, firstRender = previous == null)
        } else if (journey != null) {
            drawJourney(journey, state)
            frameJourneyIfNeeded(journey, state)
        } else {
            framedJourney = null
            drawRoutes()
            frameWithoutSelection(state, previous)
        }
        drawAlerts(state.alerts)
        renderArrivalViews()
    }

    // --- Dibujo ---

    private fun line(
        map: MapView,
        points: List<GeoPoint>,
        @ColorInt color: Int,
        widthDp: Float,
        dotted: Boolean = false
    ): Polyline {
        val density = resources.displayMetrics.density
        return Polyline(map).apply {
            setPoints(points)
            outlinePaint.color = color
            outlinePaint.alpha = Color.alpha(color)
            outlinePaint.strokeWidth = widthDp * density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            if (dotted) {
                // Trazos casi nulos con extremo redondo: una fila de puntitos.
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(1f, DOT_GAP_DP * density), 0f)
            }
            // Sin globo de información al tocar la línea, y sin quedarse con el
            // toque: tocar sobre el recorrido es justo lo que se hace para
            // elegir dónde bajarse.
            setOnClickListener { _, _, _ -> false }
        }
    }

    @ColorInt
    private fun routeColorOf(route: BusRoute): Int =
        ContextCompat.getColor(requireContext(), RouteAccent.colorFor(route.accentIndex))

    private fun GeoCoordinate.toGeoPoint() = GeoPoint(latitude, longitude)

    private fun PlaceSearchState.text(): String? = when (this) {
        is PlaceSearchState.Searching -> query
        is PlaceSearchState.Results -> query
        is PlaceSearchState.Chosen -> query
        PlaceSearchState.Idle -> null
    }

    // --- Permisos y estado de la ubicación ---

    private fun askForLocation() {
        requestLocation.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun refreshLocationAccess() {
        val context = context ?: return
        if (_binding == null) return
        viewModel.onLocationAccessChanged(
            when {
                !context.hasAnyLocationPermission() -> LocationAccess.NO_PERMISSION
                !context.isLocationEnabled() -> LocationAccess.SERVICES_OFF
                else -> LocationAccess.READY
            }
        )
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", requireContext().packageName, null)
            )
        )
    }

    // --- Utilidades ---

    private fun showMessage(text: String, long: Boolean = false) {
        val binding = _binding ?: return
        val snackbar = Snackbar.make(binding.root, text, if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT)
        // La hoja tiene más elevación que un Snackbar normal y lo tapaba: el
        // mensaje se mostraba, pero nadie lo veía.
        snackbar.view.elevation = binding.sheet.elevation + SNACKBAR_EXTRA_ELEVATION_DP * resources.displayMetrics.density
        snackbar.show()
    }

    /** Los mensajes del aviso de llegada explican algo que hay que leer: duran más. */
    private fun showLongMessage(text: String) = showMessage(text, long = true)

    private fun hideKeyboard() {
        val binding = _binding ?: return
        requireContext().getSystemService<InputMethodManager>()
            ?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    // --- Ciclo de vida ---

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        if (requireContext().hasAnyLocationPermission()) startLocationUpdates()
        // El usuario pudo activar la ubicación o dar el permiso desde los ajustes.
        refreshLocationAccess()
        viewLifecycleOwner.lifecycleScope.launch { RouteRepository.refresh() }
        syncVoiceWake()
    }

    override fun onPause() {
        // Fuera de la pantalla no hace falta el GPS del mapa: ahorra batería.
        // La grabación de rutas usa su propio servicio y sigue funcionando.
        locationOverlay?.disableMyLocation()
        _binding?.mapView?.onPause()
        voiceWake?.stop()
        endMapVoice()
        super.onPause()
    }

    override fun onDestroyView() {
        cancelPendingFrame()
        mapVoice?.release()
        mapVoice = null
        _binding?.let { b ->
            b.voiceBar.removeCallbacks(hideVoiceBar)
            b.recordingDot.clearAnimation()
            val center = b.mapView.mapCenter
            viewModel.camera = MapCamera(
                latitude = center.latitude,
                longitude = center.longitude,
                zoom = b.mapView.zoomLevelDouble,
                followingUser = locationOverlay?.isFollowLocationEnabled == true
            )
            b.routesRecycler.adapter = null
            b.mapView.onDetach()
        }
        locationOverlay = null
        routeLayer = null
        routeInfo = null
        recordingLayer = null
        favoriteLayer = null
        placeLayer = null
        alertLayer = null
        pickLayer = null
        streetLayer = null
        streetPane?.destroy()
        streetPane = null
        picking = null
        sheetBehavior = null
        sheetRaised = false
        carouselSnap.attachToRecyclerView(null)
        lastState = null
        lastRecorderStatus = null
        renderedStopsKey = null
        drawnAlerts = null
        drawnPlacesKey = null
        framedSearch = null
        framedJourney = null
        _binding = null
        super.onDestroyView()
    }

    /**
     * Lo que dura la elección de la bajada: qué ruta se estaba viendo, el punto
     * que el usuario marcó y con qué nombre llamarlo.
     */
    private data class PickSession(
        val routeId: String,
        val routeName: String,
        val drop: DropOff? = null,
        val label: String? = null
    ) {
        /** El nombre de la parada, la calle, o un genérico si no se sabe ninguno. */
        fun labelOrDefault(context: Context): String =
            label?.takeIf { it.isNotBlank() } ?: context.getString(R.string.arrival_pick_point)
    }

    private companion object {
        const val DEFAULT_ZOOM = 13.5

        /** Lo máximo que la esfera de la voz se queda en el mapa sin que nada la quite. */
        const val VOICE_BAR_TIMEOUT_MS = 25_000L
        const val VOICE_BAR_LINGER_MS = 2_500L

        /** Lo que vale un "sí" a "¿te aviso al llegar?". */
        const val ARRIVAL_ON_PHRASE = "avísame al llegar"

        /** Mientras uno no se aleje más que esto, vale el camino a pie ya trazado. */
        const val STREET_WALK_REUSE_METERS = 30.0
        const val MAX_STREET_WALKS = 12

        /** Un favorito a menos de esto del destino elegido es ese mismo lugar. */
        const val SAME_PLACE_METERS = 15.0

        const val WHATSAPP_PACKAGE = "com.whatsapp"

        /** Lo que avanza un micro por minuto en ciudad, para estimar la llegada: unos 15 km/h. */
        const val TRIP_METERS_PER_MINUTE = 250.0
        const val USER_ZOOM = 16.0
        const val STOP_ZOOM = 16.5
        const val STREET_ZOOM = 17.5

        /** Parte de la pantalla que ocupa la vista de calle; el resto es del mapa y la hoja. */
        const val STREET_PANE_RATIO = 0.45f
        const val ALERT_ZOOM = 17.0
        const val CAMERA_ANIMATION_MS = 700L
        const val SHEET_SETTLE_MS = 320L
        const val FRAME_DELAY_MS = 60L
        const val LOCATION_INTERVAL_MS = 3_000L
        const val LOCATION_MIN_DISTANCE_M = 5f
        const val FRAME_BORDER_DP = 32f
        const val DOT_GAP_DP = 11f

        /** Alto mínimo de la hoja cuando no hay lista de la que medir el título. */
        const val MINI_PEEK_DP = 116

        /** Aire debajo del título con la hoja al mínimo. */
        const val MINI_PADDING_DP = 4

        /** La foto en el cuadro de una alerta del mapa. */
        const val ALERT_PHOTO_HEIGHT_DP = 180
        const val ALERT_PHOTO_MARGIN_DP = 24

        /** Una tarjeta del carrusel con su margen de arriba y el aire de debajo. */
        const val CAROUSEL_HEIGHT_DP = 156 + 8 + 16

        /** Parte de la pantalla que ocupa la hoja en reposo cuando no enseña el carrusel. */
        const val DEFAULT_REST_RATIO = 0.5f
        const val MIN_REST_RATIO = 0.3f
        const val MAX_REST_RATIO = 0.75f
        const val REST_RATIO_TOLERANCE = 0.005f

        /** Cuánto hay que subir la hoja sobre el reposo para pasar del carrusel a la lista. */
        const val RAISED_MARGIN_DP = 56
        const val BLINK_MS = 700L
        const val SNACKBAR_EXTRA_ELEVATION_DP = 4f

        /** Si el usuario está más lejos que esto de la ruta, meterlo en el encuadre alejaría demasiado. */
        const val FRAME_USER_MAX_METERS = 6_000.0
    }
}

/** La barra de abajo pide con esto abrir el asistente sobre el mapa (ver MainActivity). */
internal const val REQUEST_OPEN_ASSISTANT = "routes_open_assistant"
