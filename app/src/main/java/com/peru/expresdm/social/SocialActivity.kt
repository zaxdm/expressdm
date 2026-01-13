package com.peru.expresdm.social

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.peru.expresdm.R

class SocialActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_social)

        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        toolbar.title = getString(R.string.social_title)
        toolbar.inflateMenu(R.menu.top_app_bar_menu)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_profile) {
                // TODO: Abrir perfil cuando esté disponible
                true
            } else false
        }

        val recycler = findViewById<RecyclerView>(R.id.socialRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        val adapter = SocialAdapter(sampleData())
        recycler.adapter = adapter

        val fab = findViewById<FloatingActionButton>(R.id.fabComment)
        fab.setOnClickListener {
            startActivity(Intent(this, CommentActivity::class.java))
        }

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        bottomNav.selectedItemId = R.id.nav_social
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    startActivity(Intent(this, com.peru.expresdm.HomeActivity::class.java))
                    true
                }
                R.id.nav_routes -> true
                R.id.nav_social -> true
                else -> false
            }
        }
    }

    private fun sampleData(): List<SocialPost> {
        return listOf(
            SocialPost("María", "Tráfico pesado en Av. Larco", "Hace 5 min", R.drawable.ic_nav_routes),
            SocialPost("José", "Micro 202 con retraso", "Hace 20 min", null),
            SocialPost("Ana", "Obras en Mansiche", "Hoy", R.drawable.ic_nav_routes)
        )
    }
}
