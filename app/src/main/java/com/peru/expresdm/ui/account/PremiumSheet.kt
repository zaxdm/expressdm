package com.peru.expresdm.ui.account

import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.radiobutton.MaterialRadioButton
import com.peru.expresdm.BuildConfig
import com.peru.expresdm.R
import com.peru.expresdm.data.Premium
import com.peru.expresdm.data.net.AccountRepository
import com.peru.expresdm.data.net.ApiClient
import com.peru.expresdm.data.net.ApiFailure
import com.peru.expresdm.data.net.ApiResult
import com.peru.expresdm.data.net.ImageUploader
import com.peru.expresdm.data.net.Session
import com.peru.expresdm.databinding.ItemPremiumBenefitBinding
import com.peru.expresdm.databinding.SheetPremiumBinding
import com.peru.expresdm.ui.RouteImages
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Date

/**
 * Subo Premium: qué trae de más, si la cuenta ya lo tiene y cómo pagarlo.
 *
 * Premium va con la cuenta, así que primero hay que tener una. Se paga por
 * Yape: aquí se enseña el QR y lo que cuesta cada plan, y la persona sube la
 * captura del pago. El administrador la confirma y la app se entera sola la
 * próxima vez que pregunta por la sesión.
 *
 * Si el administrador todavía no puso su QR, queda lo de antes: pedirlo deja un
 * mensaje en el buzón y él lo activa a mano.
 */
class PremiumSheet : BottomSheetDialogFragment() {

    /** Un plan que se ofrece: cuántos días da y cuánto cuesta, en soles. */
    private data class Plan(val days: Int, val price: Double)

    /** A qué QR se paga y cómo va el último comprobante de esta cuenta. */
    private data class Payment(
        val qrUrl: String?,
        val holder: String,
        val plans: List<Plan>,
        /** PENDING, APPROVED o REJECTED; `null` si nunca subió ninguno. */
        val status: String?,
        val note: String?
    ) {
        /** El administrador ya dejó todo listo para cobrar por Yape. */
        val isOffered: Boolean get() = qrUrl != null && plans.isNotEmpty()
    }

    private var _binding: SheetPremiumBinding? = null
    private val binding get() = _binding!!

    /** Ya se mandó la solicitud desde esta hoja: no hace falta repetirla. */
    private var requested = false

    private var payment: Payment? = null

    /** Se está subiendo y revisando la captura. */
    private var uploading = false

    /** Lo último que salió mal al pagar, para decirlo junto al botón. */
    private var problem: String? = null

    /** Solo imágenes: el selector de Android no deja elegir otra cosa. */
    private val pickProof = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && _binding != null) sendProof(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetPremiumBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requested = savedInstanceState?.getBoolean(STATE_REQUESTED) ?: false

        compareRows().forEach { (feature, free, premium) ->
            val row = ItemPremiumBenefitBinding.inflate(layoutInflater, binding.benefits, false)
            row.benefitFeature.text = feature
            row.benefitFree.text = free
            row.benefitPremium.text = premium
            binding.benefits.addView(row.root)
        }

        binding.btnPremiumAction.setOnClickListener { onAction() }
        binding.btnPremiumBack.setOnClickListener { dismiss() }
        // La voz y el aviso de llegada se ajustan en Ajustes, desde el perfil.
        binding.btnPremiumSettings.setOnClickListener {
            val nav = runCatching { findNavController() }.getOrNull()
            dismiss()
            if (nav == null) return@setOnClickListener
            when (nav.currentDestination?.id) {
                R.id.profileFragment -> nav.navigate(R.id.action_profile_to_settings)
                // Abierta desde Ajustes: ya está ahí.
                R.id.settingsFragment -> Unit
                else -> nav.navigate(R.id.settingsFragment)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // Puede que lo acaben de activar: se pregunta al abrir.
            launch { AccountRepository.refresh() }
            launch { loadPayment() }
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AccountRepository.session.collect { render(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // La hoja se abre entera: la cabecera y la tabla se ven de una vez.
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        sheet.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        BottomSheetBehavior.from(sheet).apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    /**
     * Las filas de la tabla: qué es, cuánto trae la cuenta gratuita y cuánto
     * Premium. Solo lo que ya funciona: no se promete lo que aún no existe.
     */
    private fun compareRows(): List<Triple<String, String, String>> {
        val no = getString(R.string.perfil_premium_no)
        val yes = getString(R.string.perfil_premium_yes)
        return listOf(
            Triple(
                getString(R.string.perfil_premium_row_offline),
                Premium.FREE_SAVED_ROUTES.toString(),
                getString(R.string.perfil_premium_up_to, Premium.PREMIUM_SAVED_ROUTES)
            ),
            Triple(
                getString(R.string.perfil_premium_row_places),
                Premium.FREE_FAVORITE_PLACES.toString(),
                getString(R.string.perfil_premium_unlimited)
            ),
            Triple(
                getString(R.string.perfil_premium_row_assistant),
                getString(R.string.perfil_premium_assistant_free),
                getString(R.string.perfil_premium_no_cap)
            ),
            Triple(getString(R.string.perfil_premium_row_arrival), yes, getString(R.string.perfil_premium_early)),
            Triple(getString(R.string.perfil_premium_row_tone), no, yes),
            Triple(getString(R.string.perfil_premium_row_route_alerts), no, yes),
            Triple(getString(R.string.perfil_premium_row_live), no, yes),
            Triple(getString(R.string.perfil_premium_row_voice), no, yes)
        )
    }

    private suspend fun loadPayment() {
        val result = ApiClient.get("/api/premium/payment", AccountRepository.token)
        if (result is ApiResult.Success) {
            payment = parsePayment(result.data)
            renderPlans()
            render(AccountRepository.session.value)
        }
    }

    private fun parsePayment(data: JSONObject): Payment {
        val plans = data.optJSONArray("plans")
        val mine = data.optJSONObject("mine")
        return Payment(
            qrUrl = if (data.isNull("qrUrl")) null else data.optString("qrUrl").takeIf { it.startsWith("https://") },
            holder = data.optString("holder").trim(),
            plans = List(plans?.length() ?: 0) { index ->
                val plan = plans!!.getJSONObject(index)
                Plan(plan.optInt("days"), plan.optDouble("price"))
            }.filter { it.days > 0 && it.price > 0 },
            status = mine?.optString("status")?.takeIf { it.isNotBlank() },
            note = mine?.takeUnless { it.isNull("note") }?.optString("note")?.takeIf { it.isNotBlank() }
        )
    }

    /** Un botón por plan, con lo que dura y lo que cuesta. Sale marcado el primero. */
    private fun renderPlans() {
        val binding = _binding ?: return
        val plans = payment?.plans.orEmpty()
        binding.planGroup.removeAllViews()
        plans.forEachIndexed { index, plan ->
            val radio = layoutInflater
                .inflate(R.layout.item_setting_choice, binding.planGroup, false) as MaterialRadioButton
            radio.id = View.generateViewId()
            radio.tag = plan
            radio.text = getString(R.string.premium_plan_label, planName(plan.days), priceText(plan.price))
            radio.isSaveEnabled = false
            binding.planGroup.addView(radio)
            if (index == 0) binding.planGroup.check(radio.id)
        }
    }

    private fun planName(days: Int): String = when {
        days == 30 -> getString(R.string.premium_plan_month)
        days == 365 || days == 366 -> getString(R.string.premium_plan_year)
        days % 30 == 0 -> getString(R.string.premium_plan_months, days / 30)
        else -> getString(R.string.premium_plan_days, days)
    }

    /** "5" o "5.50": sin decimales de adorno. */
    private fun priceText(price: Double): String =
        if (price == Math.floor(price)) price.toInt().toString() else String.format(java.util.Locale.US, "%.2f", price)

    private fun selectedPlan(): Plan? =
        binding.planGroup.findViewById<View>(binding.planGroup.checkedRadioButtonId)?.tag as? Plan

    private fun render(session: Session?) {
        val binding = _binding ?: return
        val active = Premium.isActive(session)
        val payment = payment
        val pending = payment?.status == STATUS_PENDING
        // Se paga por Yape si hay sesión, no es ya Premium y el QR está puesto.
        val paying = !active && session != null && payment?.isOffered == true

        binding.premiumActiveDot.isVisible = active
        // Con Premium activo, lo que queda es ajustarlo; sin él, el botón principal invita a tenerlo.
        binding.btnPremiumSettings.isVisible = active
        binding.premiumStatus.text = when {
            active -> getString(
                R.string.premium_status_active,
                DateFormat.getMediumDateFormat(requireContext()).format(Date(session!!.premiumUntil!!))
            )
            session == null -> getString(R.string.premium_status_signed_out)
            pending -> getString(R.string.premium_status_pending)
            else -> getString(R.string.premium_status_free)
        }

        // Con la captura en revisión ya no hace falta el QR: solo esperar.
        binding.paymentSection.isVisible = paying && !pending
        if (paying && !pending) {
            RouteImages.show(binding.qrImage, payment?.qrUrl, getString(R.string.premium_pay_qr))
            binding.yapeHolder.isVisible = payment?.holder?.isNotBlank() == true
            binding.yapeHolder.text = getString(R.string.premium_pay_holder, payment?.holder.orEmpty())
        }

        binding.btnPremiumAction.isVisible = !active
        binding.btnPremiumAction.isEnabled = when {
            paying -> !pending && !uploading
            else -> !requested
        }
        binding.btnPremiumAction.setText(
            when {
                session == null -> R.string.premium_action_sign_in
                paying && pending -> R.string.premium_pay_pending_action
                paying && uploading -> R.string.premium_pay_uploading
                paying -> R.string.premium_pay_action
                requested -> R.string.premium_action_requested
                else -> R.string.premium_action_request
            }
        )

        binding.premiumHow.isVisible = !active
        binding.premiumHow.text = when {
            session == null -> getString(R.string.premium_how_sign_in)
            problem != null -> problem
            paying && pending -> getString(R.string.premium_pay_pending)
            paying && payment?.status == STATUS_REJECTED ->
                getString(R.string.premium_pay_rejected, payment.note ?: getString(R.string.premium_pay_rejected_plain))
            paying -> getString(R.string.premium_pay_only_images)
            requested -> getString(R.string.premium_how_requested)
            else -> getString(R.string.premium_how_request)
        }
    }

    private fun onAction() {
        val session = AccountRepository.session.value
        if (session == null) {
            AccountSheet.show(parentFragmentManager)
            dismiss()
            return
        }
        if (payment?.isOffered == true) {
            problem = null
            pickProof.launch("image/*")
        } else {
            requestByMessage(session)
        }
    }

    /**
     * Sube la captura (el servidor comprueba que sea la de un pago, y nada
     * más) y la deja en revisión para el plan elegido.
     */
    private fun sendProof(image: android.net.Uri) {
        val plan = selectedPlan() ?: return
        uploading = true
        problem = null
        render(AccountRepository.session.value)
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext().applicationContext
            val failure: ApiResult.Failure? =
                when (val uploaded = ImageUploader.upload(context, image, ImageUploader.Purpose.PAYMENT)) {
                    is ApiResult.Failure -> uploaded
                    is ApiResult.Success -> {
                        val body = JSONObject().put("days", plan.days).put("imageUrl", uploaded.data)
                        when (val sent = ApiClient.post("/api/premium/payments", body, AccountRepository.token)) {
                            is ApiResult.Failure -> sent
                            is ApiResult.Success -> null
                        }
                    }
                }
            uploading = false
            if (failure == null) {
                payment = payment?.copy(status = STATUS_PENDING, note = null)
            } else {
                // El motivo que da el servidor ("eso no es un pago") ya viene listo para mostrar.
                problem = failure.message?.takeIf { failure.reason == ApiFailure.REJECTED }
                    ?: getString(R.string.premium_pay_failed)
                // Puede que ya hubiera uno en revisión desde otro teléfono.
                loadPayment()
            }
            render(AccountRepository.session.value)
        }
    }

    /** Sin QR configurado: se deja un mensaje y el administrador lo activa a mano. */
    private fun requestByMessage(session: Session) {
        binding.btnPremiumAction.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val body = JSONObject()
                .put("subject", getString(R.string.premium_request_subject))
                .put("message", getString(R.string.premium_request_message, session.email))
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
            val result = ApiClient.post("/api/feedback", body, session.token)
            if (_binding == null) return@launch
            when (result) {
                is ApiResult.Success -> requested = true
                // El motivo que da el servidor ya viene listo para mostrar.
                is ApiResult.Failure -> problem = when (result.reason) {
                    ApiFailure.NETWORK -> getString(R.string.premium_request_failed)
                    else -> result.message ?: getString(R.string.premium_request_failed)
                }
            }
            render(AccountRepository.session.value)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_REQUESTED, requested)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "PremiumSheet"
        private const val STATE_REQUESTED = "requested"
        private const val STATUS_PENDING = "PENDING"
        private const val STATUS_REJECTED = "REJECTED"

        fun show(manager: FragmentManager) {
            if (manager.findFragmentByTag(TAG) == null) PremiumSheet().show(manager, TAG)
        }
    }
}
