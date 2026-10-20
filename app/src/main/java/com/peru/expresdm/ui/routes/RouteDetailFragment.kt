package com.peru.expresdm.ui.routes

import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.peru.expresdm.R
import com.peru.expresdm.data.BusRoute
import com.peru.expresdm.data.RouteRepository
import com.peru.expresdm.databinding.FragmentRouteDetailBinding
import com.peru.expresdm.databinding.ItemRouteStopBinding
import com.peru.expresdm.ui.RouteAccent
import com.peru.expresdm.ui.applyDefaults
import com.peru.expresdm.ui.themeColor
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * La ficha de un micro en una pantalla propia: su recorrido en el mapa, cuánto
 * dura y cuesta, por dónde va y lo que la gente dice de él.
 *
 * Al tocar una ruta en el mapa la ficha sale ahí mismo, en el panel; a esta
 * pantalla se llega por un enlace compartido o desde un viaje de "Cómo llegar".
 */
class RouteDetailFragment : Fragment() {

    private var _binding: FragmentRouteDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRouteDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        val route = arguments?.getString(ARG_ROUTE_ID)?.let { RouteRepository.byId(it) }
        if (route == null) {
            // La ruta se borró mientras tanto: no hay ficha que enseñar.
            findNavController().popBackStack()
            return
        }
        render(route)
        drawMap(route)
        // Solo la primera vez: girar la pantalla no es volver a abrir la ficha.
        RouteInfoPanel(this, binding.routeInfo, ::showMessage)
            .show(route, countView = savedInstanceState == null)
    }

    private fun render(route: BusRoute) {
        val color = routeColor(route)

        binding.detailBadge.text = route.letter
        binding.detailBadge.backgroundTintList = ColorStateList.valueOf(color)
        binding.detailName.text = route.name
        binding.detailSubtitle.text = getString(R.string.ficha_route_subtitle, route.origin, route.destination)

        binding.detailStops.removeAllViews()
        route.stops.forEachIndexed { index, stop ->
            val item = ItemRouteStopBinding.inflate(layoutInflater, binding.detailStops, false)
            item.stopNumber.text = (index + 1).toString()
            item.stopNumber.backgroundTintList = ColorStateList.valueOf(color)
            item.stopName.text = stop.name
            // Aquí los puntos solo se leen; en el mapa son los que se tocan.
            item.root.isClickable = false
            item.root.isFocusable = false
            binding.detailStops.addView(item.root)
        }
    }

    // --- Mapa ---

    /** El recorrido en el color de la ruta, con un punto en cada parada. Solo se mira. */
    private fun drawMap(route: BusRoute) {
        val map = binding.detailMap
        map.applyDefaults(requireContext())
        map.setMultiTouchControls(false)
        map.isClickable = false
        map.setOnTouchListener { _, _ -> true }

        val stops = route.stops.map { GeoPoint(it.latitude, it.longitude) }
        val points = route.path?.takeIf { it.size >= 2 }?.map { GeoPoint(it.latitude, it.longitude) } ?: stops
        if (points.isEmpty()) return

        val color = routeColor(route)
        val density = resources.displayMetrics.density
        map.overlays.add(Polyline(map).apply {
            setPoints(points)
            outlinePaint.color = color
            outlinePaint.strokeWidth = 5f * density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            setOnClickListener { _, _, _ -> true }
        })
        stops.forEachIndexed { index, point ->
            val last = index == stops.lastIndex
            map.overlays.add(Marker(map).apply {
                position = point
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = dot(color, filled = last, sizeDp = if (last) 16 else 12)
                infoWindow = null
                setOnMarkerClickListener { _, _ -> true }
            })
        }

        // Encuadra el recorrido en la parte del mapa que no tapa la hoja.
        map.addOnFirstLayoutListener { _, _, _, _, _ ->
            val box = BoundingBox.fromGeoPointsSafe(points)
            val visible = (resources.getDimensionPixelSize(R.dimen.ficha_map_visible)).coerceAtMost(map.height)
            map.setMapCenterOffset(0, (visible - map.height) / 2)
            map.zoomToBoundingBox(box.increaseByScale(1.25f), false, (28 * density).toInt())
        }
    }

    private fun dot(@ColorInt color: Int, filled: Boolean, sizeDp: Int) = GradientDrawable().apply {
        val density = resources.displayMetrics.density
        shape = GradientDrawable.OVAL
        val size = (sizeDp * density).toInt()
        setSize(size, size)
        setColor(if (filled) color else requireContext().themeColor(com.google.android.material.R.attr.colorSurface))
        setStroke((3 * density).toInt(), color)
    }

    @ColorInt
    private fun routeColor(route: BusRoute): Int =
        ContextCompat.getColor(requireContext(), RouteAccent.colorFor(route.accentIndex))

    override fun onResume() {
        super.onResume()
        _binding?.detailMap?.onResume()
    }

    override fun onPause() {
        _binding?.detailMap?.onPause()
        super.onPause()
    }

    private fun showMessage(text: String) {
        val binding = _binding ?: return
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        _binding?.detailMap?.onDetach()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_ROUTE_ID = "routeId"

        fun args(routeId: String): Bundle = bundleOf(ARG_ROUTE_ID to routeId)
    }
}
