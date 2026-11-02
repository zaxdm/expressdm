package com.peru.expresdm.ui.account

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.peru.expresdm.BuildConfig
import com.peru.expresdm.R
import com.peru.expresdm.data.PremiumCheckout
import com.peru.expresdm.data.net.AccountRepository
import com.peru.expresdm.databinding.DialogPremiumPayBinding

/**
 * Pagar Subo Premium con Mercado Pago sin salir de la app.
 *
 * Abre la página `/pagar` de la web, que lleva el formulario de Mercado Pago.
 * La sesión va en la parte de la dirección que no se manda a ningún servidor.
 * Al terminar, la página va a `subo-app://pago?estado=…`: aquí se lee, se
 * cierra la ventana y se avisa a quien la abrió con [REQUEST_KEY].
 * Cerrar con la X o con "atrás" cuenta como cancelado.
 */
class PremiumPayDialog : DialogFragment() {

    private var _binding: DialogPremiumPayBinding? = null
    private val binding get() = _binding!!

    /** Ya se dijo cómo terminó: no se repite. */
    private var delivered = false

    /** La página no cargó: se enseña el aviso hasta que se reintente. */
    private var failed = false

    private lateinit var startUrl: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.Theme_Expresdm_FullScreenDialog)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogPremiumPayBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val token = AccountRepository.token
        val days = requireArguments().getInt(ARG_DAYS)
        if (token == null || days <= 0) {
            finish(PremiumCheckout.Outcome.CANCELLED)
            return
        }
        startUrl = PremiumCheckout.payUrl(BuildConfig.WEB_URL, days, token)

        // La barra no se mete bajo la de estado, y el teclado no tapa el formulario.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            root.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        binding.payToolbar.setNavigationOnClickListener { finish(PremiumCheckout.Outcome.CANCELLED) }
        binding.btnPayRetry.setOnClickListener { retry() }

        val web = binding.payWeb
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Las ventanas nuevas (target=_blank) se abren en la misma.
            setSupportMultipleWindows(false)
        }
        // La verificación del banco (3-D Secure) puede usar cookies de otro dominio.
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = PayClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                _binding?.payProgress?.isVisible = newProgress < 100 && !failed
            }
        }
        // Al girar el teléfono se vuelve a empezar: el estado de la página no se guarda,
        // porque llevaría la sesión.
        web.loadUrl(startUrl)
    }

    private inner class PayClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            handle(request.url.toString())

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            _binding?.payProgress?.isVisible = true
        }

        override fun onPageFinished(view: WebView, url: String?) {
            _binding?.payProgress?.isVisible = false
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            // Solo si falla la página entera; una imagen que no carga no importa.
            if (request.isForMainFrame) showError()
        }
    }

    /** `true`: la dirección no se carga en la ventana. */
    private fun handle(url: String): Boolean = when (PremiumCheckout.navigation(url)) {
        PremiumCheckout.Navigation.STAY -> false
        PremiumCheckout.Navigation.FINISH -> {
            PremiumCheckout.outcome(url)?.let { finish(it) }
            true
        }
        PremiumCheckout.Navigation.EXTERNAL -> {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            true
        }
        PremiumCheckout.Navigation.BLOCK -> true
    }

    private fun showError() {
        val binding = _binding ?: return
        failed = true
        binding.payProgress.isVisible = false
        binding.payError.isVisible = true
    }

    private fun retry() {
        val binding = _binding ?: return
        failed = false
        binding.payError.isVisible = false
        binding.payProgress.isVisible = true
        // Si ya se había llegado a Mercado Pago, se recarga esa página; si no, la de inicio.
        val current = binding.payWeb.url
        if (current.isNullOrBlank() || current == "about:blank") binding.payWeb.loadUrl(startUrl) else binding.payWeb.reload()
    }

    /** Avisa a la hoja de Premium de cómo terminó y cierra la ventana. */
    private fun finish(outcome: PremiumCheckout.Outcome) {
        if (!delivered) {
            delivered = true
            parentFragmentManager.setFragmentResult(REQUEST_KEY, bundleOf(RESULT_OUTCOME to outcome.name))
        }
        if (isAdded) dismissAllowingStateLoss()
    }

    /** "Atrás" cierra la ventana: cuenta como cancelado. */
    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        finish(PremiumCheckout.Outcome.CANCELLED)
    }

    override fun onDestroyView() {
        _binding?.payWeb?.apply {
            stopLoading()
            webChromeClient = null
            destroy()
        }
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "PremiumPayDialog"
        private const val ARG_DAYS = "days"
        const val REQUEST_KEY = "premium_pay"
        const val RESULT_OUTCOME = "outcome"

        fun show(manager: FragmentManager, days: Int) {
            if (manager.findFragmentByTag(TAG) != null) return
            PremiumPayDialog().apply { arguments = bundleOf(ARG_DAYS to days) }.show(manager, TAG)
        }

        /** Lee el resultado que llega con [REQUEST_KEY]. */
        fun outcomeOf(result: Bundle): PremiumCheckout.Outcome =
            runCatching { PremiumCheckout.Outcome.valueOf(result.getString(RESULT_OUTCOME).orEmpty()) }
                .getOrDefault(PremiumCheckout.Outcome.CANCELLED)
    }
}
