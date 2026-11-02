package com.peru.expresdm.ui.account

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.peru.expresdm.R
import com.peru.expresdm.data.net.AccountRepository
import com.peru.expresdm.data.net.ApiClient
import com.peru.expresdm.data.net.ApiResult
import com.peru.expresdm.databinding.ItemPurchaseBinding
import com.peru.expresdm.databinding.SheetPurchasesBinding
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Date
import java.util.Locale

/**
 * Historial de compras de Subo Premium: cada pago de la cuenta, del más nuevo
 * al más viejo, con lo que costó, cómo se pagó, en qué quedó y su comprobante.
 */
class PurchasesSheet : BottomSheetDialogFragment() {

    /** Un pago, tal como lo manda el servidor. */
    private data class Purchase(
        val days: Int,
        val amount: Double,
        /** PENDING, APPROVED o REJECTED. */
        val status: String,
        val note: String?,
        val createdAt: Long,
        /** YAPE o MERCADOPAGO. */
        val method: String,
        val receipt: String?
    )

    private var _binding: SheetPurchasesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetPurchasesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnPurchasesBack.setOnClickListener { dismiss() }
        viewLifecycleOwner.lifecycleScope.launch { load() }
    }

    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        sheet.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        BottomSheetBehavior.from(sheet).apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private suspend fun load() {
        val result = ApiClient.get("/api/premium/payments/mine", AccountRepository.token)
        val binding = _binding ?: return
        binding.purchasesProgress.isVisible = false
        when (result) {
            is ApiResult.Failure -> showEmpty(getString(R.string.purchases_failed))
            is ApiResult.Success -> {
                val purchases = parse(result.data).sortedByDescending { it.createdAt }
                if (purchases.isEmpty()) showEmpty(getString(R.string.purchases_empty)) else render(purchases)
            }
        }
    }

    private fun parse(data: JSONObject): List<Purchase> {
        val array = data.optJSONArray("payments") ?: return emptyList()
        return List(array.length()) { index -> array.getJSONObject(index) }.map { item ->
            Purchase(
                days = item.optInt("days"),
                amount = item.optDouble("amount", 0.0).takeUnless { it.isNaN() } ?: 0.0,
                status = item.optString("status"),
                note = item.textOrNull("note"),
                createdAt = item.optLong("createdAt"),
                method = item.optString("method"),
                receipt = item.textOrNull("receipt")
            )
        }
    }

    private fun JSONObject.textOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun showEmpty(text: String) {
        binding.purchasesEmpty.text = text
        binding.purchasesEmpty.isVisible = true
    }

    private fun render(purchases: List<Purchase>) {
        val context = requireContext()
        val dateFormat = DateFormat.getMediumDateFormat(context)
        binding.purchasesList.removeAllViews()
        purchases.forEach { purchase ->
            val item = ItemPurchaseBinding.inflate(layoutInflater, binding.purchasesList, false)
            item.purchaseTitle.text =
                getString(R.string.purchases_item_title, getString(R.string.purchases_days, purchase.days))
            item.purchaseAmount.text = getString(R.string.purchases_amount, priceText(purchase.amount))
            val method = getString(
                if (purchase.method == "MERCADOPAGO") R.string.purchases_method_mercadopago else R.string.purchases_method_yape
            )
            item.purchaseMeta.text = listOfNotNull(
                purchase.createdAt.takeIf { it > 0 }?.let { dateFormat.format(Date(it)) },
                method
            ).joinToString(" · ")

            // Aprobado en petróleo, en revisión en amarillo y rechazado en rojo.
            val (label, text, background) = when (purchase.status) {
                "APPROVED" -> Triple(R.string.purchases_status_approved, R.color.petrol, R.color.petrol_soft)
                "REJECTED" -> Triple(R.string.purchases_status_rejected, R.color.perfil_danger, R.color.perfil_danger_soft)
                else -> Triple(R.string.purchases_status_pending, R.color.amber_700, R.color.yellow_100)
            }
            item.purchaseStatus.setText(label)
            item.purchaseStatus.setTextColor(ContextCompat.getColor(context, text))
            item.purchaseStatus.background = GradientDrawable().apply {
                cornerRadius = 12 * resources.displayMetrics.density
                setColor(ContextCompat.getColor(context, background))
            }

            val note = purchase.note.takeIf { purchase.status == "REJECTED" }
            item.purchaseNote.isVisible = note != null
            note?.let { item.purchaseNote.text = getString(R.string.purchases_note, it) }
            item.purchaseReceipt.isVisible = purchase.receipt != null
            purchase.receipt?.let { item.purchaseReceipt.text = getString(R.string.purchases_receipt, it) }

            binding.purchasesList.addView(item.root)
        }
    }

    /** "5" o "5.50": sin decimales de adorno. */
    private fun priceText(price: Double): String =
        if (price == Math.floor(price)) price.toInt().toString() else String.format(Locale.US, "%.2f", price)

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "PurchasesSheet"

        fun show(manager: FragmentManager) {
            if (manager.findFragmentByTag(TAG) == null) PurchasesSheet().show(manager, TAG)
        }
    }
}
