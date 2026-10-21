package com.peru.expresdm.ui.support

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.peru.expresdm.BuildConfig
import com.peru.expresdm.R
import com.peru.expresdm.databinding.FragmentAboutBinding
import com.peru.expresdm.databinding.ItemAboutPointBinding

/** Nosotros: quiénes hacen Subo, qué hace la app y lo que no va a cambiar. */
class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    private class Point(@DrawableRes val icon: Int, @StringRes val title: Int, @StringRes val body: Int)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        fill(binding.teamPoints, TEAM)
        fill(binding.whatPoints, WHAT)
        fill(binding.promisePoints, PROMISES)

        // Escribirnos es el buzón de Ayuda: ahí llega al equipo y se ve la respuesta.
        binding.btnAboutContact.setOnClickListener { findNavController().navigate(R.id.action_about_to_support) }
        binding.aboutVersion.text = getString(R.string.support_version, BuildConfig.VERSION_NAME)
    }

    private fun fill(container: LinearLayout, points: List<Point>) {
        points.forEach { point ->
            val row = ItemAboutPointBinding.inflate(layoutInflater, container, false)
            row.pointIcon.setImageResource(point.icon)
            row.pointTitle.setText(point.title)
            row.pointBody.setText(point.body)
            container.addView(row.root)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        val TEAM = listOf(
            Point(R.drawable.ic_fav_study, R.string.about_team_students_title, R.string.about_team_students_body),
            Point(R.drawable.ic_star, R.string.about_team_growth_title, R.string.about_team_growth_body),
            Point(R.drawable.ic_place, R.string.about_team_city_title, R.string.about_team_city_body)
        )
        val WHAT = listOf(
            Point(R.drawable.ic_map, R.string.about_what_routes_title, R.string.about_what_routes_body),
            Point(R.drawable.ic_nav_alerts, R.string.about_what_alerts_title, R.string.about_what_alerts_body),
            Point(R.drawable.ic_assistant, R.string.about_what_assistant_title, R.string.about_what_assistant_body),
            Point(R.drawable.ic_arrival_alarm, R.string.about_what_arrival_title, R.string.about_what_arrival_body)
        )
        val PROMISES = listOf(
            Point(R.drawable.ic_star_outline, R.string.about_promise_free_title, R.string.about_promise_free_body),
            Point(R.drawable.ic_info, R.string.about_promise_data_title, R.string.about_promise_data_body),
            Point(R.drawable.ic_edit, R.string.about_promise_fix_title, R.string.about_promise_fix_body)
        )
    }
}
