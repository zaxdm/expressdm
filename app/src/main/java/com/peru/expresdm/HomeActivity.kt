package com.peru.expresdm

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.MapsInitializer
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.bottomnavigation.BottomNavigationView
import android.view.Menu
import android.view.MenuItem
import android.content.Intent
import com.peru.expresdm.social.SocialActivity

/**
 * Pantalla principal (Home) de expresdm.
 * - Barra superior fija: perfil (izquierda), título "DM" centrado y menú (derecha).
 * - Mapa que muestra la ubicación actual.
     * - Contenido informativo con tabs bajo el mapa.
     * - FAB de lupa para buscar rutas.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private var googleMap: GoogleMap? = null

    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                enableMyLocation()
            } else {
                Toast.makeText(this, "Permiso de ubicación denegado", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // Toolbar
        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        toolbar.title = "DM"
        toolbar.inflateMenu(R.menu.top_app_bar_menu)
        toolbar.setNavigationOnClickListener {
            Toast.makeText(this, "Perfil", Toast.LENGTH_SHORT).show()
            // TODO: Navegar a pantalla de perfil
        }
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_profile) {
                Toast.makeText(this, "Perfil", Toast.LENGTH_SHORT).show()
                true
            } else false
        }

        // Mapa
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        MapsInitializer.initialize(this)
        mapView.getMapAsync { map ->
            googleMap = map
            map.uiSettings.isCompassEnabled = true
            map.uiSettings.isMyLocationButtonEnabled = true
            map.uiSettings.isZoomControlsEnabled = true
            ensureLocationPermission()
        }

        // Tabs debajo del mapa
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val autobuses = tabLayout.newTab().setText("Autobuses")
        val eventos = tabLayout.newTab().setText("Eventos Locales")
        tabLayout.addTab(autobuses, true)
        tabLayout.addTab(eventos)

        val autobusesContent = findViewById<android.view.View>(R.id.tabAutobusesContent)
        val eventosContent = findViewById<android.view.View>(R.id.tabEventosContent)
        


        fun showContent(index: Int) {
            autobusesContent.visibility = if (index == 0) android.view.View.VISIBLE else android.view.View.GONE
            eventosContent.visibility = if (index == 1) android.view.View.VISIBLE else android.view.View.GONE
        }
        showContent(0)

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showContent(tab.position)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNav.selectedItemId = R.id.nav_home
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_routes -> true
                R.id.nav_social -> {
                    startActivity(Intent(this, com.peru.expresdm.social.SocialActivity::class.java))
                    true
                }
                else -> false
            }
        }

        // FAB Buscar
        findViewById<FloatingActionButton>(R.id.fabBuscar).setOnClickListener {
            Toast.makeText(this, getString(R.string.buscar_rutas), Toast.LENGTH_SHORT).show()
            // TODO: Abrir buscador de rutas
        }
    }

    

    

    private fun ensureLocationPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            enableMyLocation()
        } else {
            requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun enableMyLocation() {
        val map = googleMap ?: return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            map.isMyLocationEnabled = true
            val fusedClient = LocationServices.getFusedLocationProviderClient(this)
            fusedClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    val latLng = com.google.android.gms.maps.model.LatLng(it.latitude, it.longitude)
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
                }
            }
        }
    }

    // MapView lifecycle
    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }
}
