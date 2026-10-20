package com.peru.expresdm.ui.reports

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.peru.expresdm.R
import com.peru.expresdm.data.AlertRepository
import com.peru.expresdm.data.AlertType
import com.peru.expresdm.data.AlertVote
import com.peru.expresdm.data.RoadAlert
import com.peru.expresdm.databinding.FragmentReportsBinding
import com.peru.expresdm.databinding.AlertasItemFilterChipBinding
import com.peru.expresdm.ui.AlertStyle
import com.peru.expresdm.ui.hasAnyLocationPermission
import com.peru.expresdm.ui.showAlertOnMap
import kotlinx.coroutines.launch

/**
 * Alertas que los usuarios dejan en el mapa: tráfico, accidentes, obras...
 *
 * Cada alerta caduca sola según su tipo, y quien pasa por el sitio puede
 * confirmar que sigue ahí o descartarla. "Ver en el mapa" la muestra en el
 * inicio, donde también aparecen como marcadores.
 */
class ReportsFragment : Fragment() {

    private var _binding: FragmentReportsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ReportsViewModel by viewModels()

    private val adapter by lazy {
        AlertAdapter(
            onShowOnMap = { alert -> showAlertOnMap(alert.id) },
            onVote = { alert, vote -> vote(alert, vote) }
        )
    }

    /** Chip de cada filtro; la clave `null` es "Todas". */
    private val filterChips = mutableMapOf<AlertType?, Chip>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReportsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.alertsRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.alertsRecycler.adapter = adapter

        buildFilterChips()
        // Con servidor, las alertas son de todos y van "en vivo"; sin él, se quedan en el teléfono.
        binding.subtitle.setText(
            if (AlertRepository.isShared) R.string.alertas_subtitle_shared else R.string.alertas_subtitle_local
        )
        binding.livePill.isVisible = AlertRepository.isShared

        binding.btnReport.setOnClickListener { openCreateAlert() }

        childFragmentManager.setFragmentResultListener(
            CreateAlertSheet.RESULT_KEY,
            viewLifecycleOwner
        ) { _, _ ->
            // Si la lista estaba filtrada por otro tipo, la alerta recién
            // publicada no se vería: se vuelve a "Todas".
            filterChips[null]?.isChecked = true
            showMessage(getString(R.string.reports_published))
            _binding?.alertsRecycler?.scrollToPosition(0)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (requireContext().hasAnyLocationPermission()) viewModel.refreshLocation()
    }

    private fun buildFilterChips() {
        val context = requireContext()
        val current = viewModel.state.value.filter
        val types: List<AlertType?> = listOf<AlertType?>(null) + AlertType.entries

        types.forEach { type ->
            val chip = AlertasItemFilterChipBinding.inflate(layoutInflater, binding.filterChips, false).root
            chip.id = View.generateViewId()
            chip.tag = type
            // Un punto del color del tipo; "Todas" lleva el amarillo de la marca.
            val dot = if (type == null) R.color.brand else AlertStyle.color(type)
            chip.chipIconTint = ColorStateList.valueOf(ContextCompat.getColor(context, dot))
            chip.isChecked = type == current
            binding.filterChips.addView(chip)
            filterChips[type] = chip
        }

        binding.filterChips.setOnCheckedStateChangeListener { group, checkedIds ->
            val chip = checkedIds.firstOrNull()?.let { group.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            viewModel.setFilter(chip.tag as AlertType?)
        }
    }

    private fun render(state: ReportsState) {
        adapter.submitList(state.items)

        filterChips.forEach { (type, chip) ->
            val label = if (type == null) getString(R.string.reports_filter_all) else getString(AlertStyle.label(type))
            val count = if (type == null) state.totalActive else state.counts[type] ?: 0
            chip.text = if (count > 0) getString(R.string.reports_filter_count, label, count) else label
        }

        val empty = state.items.isEmpty()
        val filteredOut = empty && state.filter != null && state.totalActive > 0
        binding.emptyState.isVisible = empty
        binding.emptyTitle.setText(if (filteredOut) R.string.reports_empty_filtered else R.string.alertas_empty_title)
        binding.emptyBody.isVisible = !filteredOut
    }

    private fun vote(alert: RoadAlert, vote: AlertVote) {
        viewModel.vote(alert.id, vote)
        showMessage(
            getString(if (vote == AlertVote.GONE) R.string.alert_hidden else R.string.alert_you_confirmed)
        )
    }

    private fun openCreateAlert() {
        CreateAlertSheet.show(childFragmentManager)
    }

    private fun showMessage(text: String) {
        _binding?.let { Snackbar.make(it.root, text, Snackbar.LENGTH_SHORT).setAnchorView(it.btnReport).show() }
    }

    override fun onDestroyView() {
        binding.alertsRecycler.adapter = null
        filterChips.clear()
        _binding = null
        super.onDestroyView()
    }
}
