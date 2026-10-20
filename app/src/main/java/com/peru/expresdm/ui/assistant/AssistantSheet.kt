package com.peru.expresdm.ui.assistant

import android.Manifest
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import com.peru.expresdm.R
import com.peru.expresdm.data.AppSettings
import com.peru.expresdm.data.Premium
import com.peru.expresdm.databinding.AlertasItemSuggestionBinding
import com.peru.expresdm.databinding.FragmentAssistantBinding
import com.peru.expresdm.ui.MapRouteRequests
import com.peru.expresdm.ui.account.PremiumSheet
import com.peru.expresdm.voice.Speaker
import com.peru.expresdm.voice.VoiceCommand
import com.peru.expresdm.voice.VoiceCommands
import com.peru.expresdm.voice.VoiceInput
import com.peru.expresdm.voice.VoiceOrbView
import kotlinx.coroutines.launch

/**
 * Chat con el asistente, abierto desde la burbuja del mapa.
 *
 * La conversación vive en un ViewModel de la Activity: cerrar la burbuja y
 * volver a abrirla no borra lo que ya se habló. "Ver ruta" cierra el chat y
 * traza la ruta en el mapa.
 *
 * Con Premium también se le habla: el micrófono escucha la pregunta, la
 * respuesta se lee en voz alta y, al terminar, vuelve a escuchar, para seguir
 * la conversación sin tocar la pantalla. Se corta al callar o al escribir.
 */
class AssistantSheet : BottomSheetDialogFragment() {

    private var _binding: FragmentAssistantBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AssistantViewModel by activityViewModels()

    private var voiceInput: VoiceInput? = null

    /** Se está leyendo una respuesta en voz alta. */
    private var speaking = false

    private val askMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (_binding == null) return@registerForActivityResult
        if (granted) listen() else showMessage(R.string.voice_needs_microphone)
    }

    private val adapter by lazy {
        AssistantAdapter(
            onRouteClick = { route ->
                MapRouteRequests.request(route.id)
                dismiss()
            }
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAssistantBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.messagesRecycler.layoutManager = LinearLayoutManager(requireContext()).apply {
            stackFromEnd = true
        }
        binding.messagesRecycler.adapter = adapter

        setUpSuggestions()
        binding.premiumTag.isVisible = Premium.active
        binding.btnCloseTrip.setOnClickListener { dismiss() }

        binding.btnSend.setOnClickListener { sendCurrentInput() }
        binding.btnVoice.setOnClickListener { onVoiceClicked() }
        binding.messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentInput()
                true
            } else {
                false
            }
        }

        if (!viewModel.isAssistantAvailable) {
            Snackbar.make(binding.root, R.string.assistant_disabled, Snackbar.LENGTH_LONG).show()
        }

        observeConversation()

        // Se abrió llamándolo por voz: o ya trae la pregunta, o hay que escucharla.
        if (savedInstanceState == null && arguments?.getBoolean(ARG_VOICE) == true && voiceAllowed()) {
            val said = arguments?.getString(ARG_QUESTION).orEmpty()
            if (said.isBlank()) listen() else viewModel.send(said, byVoice = true)
        }
    }

    /** Fondo arena, como en la maqueta: la burbuja y las filas blancas resaltan. */
    override fun getTheme(): Int = R.style.ThemeOverlay_Alertas_BottomSheetDialog

    override fun onStart() {
        super.onStart()
        // El teclado empuja la caja de texto hacia arriba en lugar de taparla.
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

    override fun onStop() {
        // Con la app fuera de la vista no se escucha ni se sigue hablando.
        stopVoice()
        super.onStop()
    }

    /** Preguntas de ejemplo para que la primera vez no haya que pensar qué escribir. */
    private fun setUpSuggestions() {
        val suggestions = listOf(
            R.string.assistant_suggestion_1,
            R.string.assistant_suggestion_2,
            R.string.assistant_suggestion_3
        )

        suggestions.forEach { resId ->
            val row = AlertasItemSuggestionBinding.inflate(layoutInflater, binding.suggestions, false)
            row.suggestionText.setText(resId)
            row.root.setOnClickListener {
                binding.messageInput.setText(getString(resId))
                sendCurrentInput()
            }
            binding.suggestions.addView(row.root)
        }
    }

    private fun sendCurrentInput() {
        val text = binding.messageInput.text?.toString().orEmpty()
        if (text.isBlank()) return

        // Quien escribe no quiere que le sigan escuchando.
        stopVoice()
        viewModel.send(text)
        binding.messageInput.text?.clear()
        hideKeyboard()
    }

    // --- Voz ---

    /** La voz es de Premium y necesita que el teléfono sepa reconocerla. */
    private fun voiceAllowed(): Boolean = Premium.active && VoiceInput.isAvailable(requireContext())

    private fun onVoiceClicked() {
        val context = requireContext()
        when {
            voiceInput?.isListening == true -> stopVoice()
            !Premium.active -> PremiumSheet.show(parentFragmentManager)
            !VoiceInput.isAvailable(context) -> showMessage(R.string.voice_unavailable)
            else -> listen()
        }
    }

    /** Escucha una pregunta y la manda. Lo que se va entendiendo se ve en la caja de texto. */
    private fun listen() {
        val context = requireContext()
        if (!VoiceInput.hasPermission(context)) {
            askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (viewModel.isThinking.value) return
        Speaker.stop()
        hideKeyboard()
        val input = voiceInput ?: VoiceInput(context).also { voiceInput = it }
        input.listen(
            onPartial = { heard -> _binding?.messageInput?.setText(heard) },
            onLevel = { level -> _binding?.voiceOrb?.setLevel(level) }
        ) { text, failed ->
            val binding = _binding ?: return@listen
            renderListening(false)
            binding.messageInput.text?.clear()
            when {
                text != null -> onHeard(text)
                failed -> showMessage(R.string.voice_failed)
                // Silencio: la conversación hablada se da por terminada.
                else -> Unit
            }
        }
        renderListening(input.isListening)
    }

    /**
     * Lo que se dijo: una pregunta se le hace al asistente; una orden ("llévame
     * a…", "avísame al llegar") es cosa del mapa, que la recibe al cerrarse esto.
     */
    private fun onHeard(text: String) {
        when (VoiceCommands.parse(text)) {
            is VoiceCommand.Ask -> viewModel.send(text, byVoice = true)
            VoiceCommand.Close -> dismiss()
            else -> {
                parentFragmentManager.setFragmentResult(REQUEST_COMMAND, bundleOf(RESULT_SAID to text))
                dismiss()
            }
        }
    }

    private fun stopVoice() {
        // Lo que se iba entendiendo ya no vale: no se queda escrito en la caja.
        if (voiceInput?.isListening == true) _binding?.messageInput?.text?.clear()
        voiceInput?.cancel()
        Speaker.stop()
        speaking = false
        renderListening(false)
    }

    /** La esfera se ve mientras hay voz: escuchando, pensando una pregunta hablada o contestándola. */
    private fun renderOrb(listening: Boolean) {
        val binding = _binding ?: return
        val thinking = viewModel.isThinking.value && viewModel.lastAskedByVoice
        binding.voiceOrb.isVisible = listening || speaking || thinking
        binding.voiceOrb.mode = when {
            listening -> VoiceOrbView.Mode.LISTENING
            speaking -> VoiceOrbView.Mode.SPEAKING
            else -> VoiceOrbView.Mode.THINKING
        }
    }

    private fun renderListening(listening: Boolean) {
        val binding = _binding ?: return
        binding.btnVoice.setImageResource(if (listening) R.drawable.alertas_ic_close else R.drawable.alertas_ic_mic)
        binding.btnVoice.contentDescription =
            getString(if (listening) R.string.voice_stop else R.string.voice_ask)
        binding.messageInput.hint = getString(
            when {
                listening -> R.string.voice_listening
                viewModel.isThinking.value -> R.string.assistant_thinking
                else -> R.string.assistant_hint
            }
        )
        renderOrb(listening)
    }

    /**
     * Lee en alto una respuesta recién llegada: siempre que la pregunta se hizo
     * hablando y, si así se pidió en Ajustes, también las escritas. Después de
     * una pregunta hablada vuelve a escuchar.
     */
    private fun speakIfNew(message: ChatMessage) {
        if (message.fromUser || message.id <= viewModel.lastSpokenId) return
        viewModel.lastSpokenId = message.id
        val context = requireContext()
        val byVoice = viewModel.lastAskedByVoice
        if (!Premium.active || !(byVoice || AppSettings.get(context).voiceAnswers)) return

        // Un error no invita a seguir hablando: se dice y ya.
        val followUp = byVoice && !message.isError
        speaking = true
        renderOrb(listening = false)
        Speaker.speak(
            context,
            message.text,
            asAlarm = false,
            onWord = { _binding?.voiceOrb?.pulse() }
        ) {
            speaking = false
            renderOrb(listening = false)
            if (followUp && _binding != null && isResumed) listen()
        }
    }

    private fun observeConversation() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.messages.collect { messages ->
                        val shown = if (messages.isEmpty()) listOf(welcomeMessage()) else messages
                        adapter.submitList(shown) {
                            _binding?.messagesRecycler?.scrollToPosition(shown.lastIndex)
                        }
                        // Las sugerencias solo estorban una vez hay conversación.
                        binding.suggestionsScroll.visibility =
                            if (messages.isEmpty()) View.VISIBLE else View.GONE
                        messages.lastOrNull()?.let(::speakIfNew)
                    }
                }

                launch {
                    viewModel.premiumOffer.collect { offered ->
                        if (offered) {
                            viewModel.premiumOfferShown()
                            PremiumSheet.show(parentFragmentManager)
                        }
                    }
                }

                launch {
                    viewModel.isThinking.collect { thinking ->
                        binding.btnSend.isEnabled = !thinking
                        binding.btnVoice.isEnabled = !thinking
                        binding.messageInput.isEnabled = !thinking
                        renderListening(voiceInput?.isListening == true)
                    }
                }
            }
        }
    }

    private fun welcomeMessage() = ChatMessage(
        id = 0L,
        text = getString(R.string.assistant_welcome),
        fromUser = false
    )

    private fun showMessage(text: Int) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService<InputMethodManager>()
        imm?.hideSoftInputFromWindow(binding.messageInput.windowToken, 0)
    }

    override fun onDestroyView() {
        voiceInput?.release()
        voiceInput = null
        Speaker.stop()
        binding.messagesRecycler.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "assistant"

        /** Con esto avisa al mapa de una orden dicha en voz alta; [RESULT_SAID] es la frase. */
        const val REQUEST_COMMAND = "assistant_voice_command"
        const val RESULT_SAID = "said"
        private const val ARG_VOICE = "voice"
        private const val ARG_QUESTION = "question"
        private const val SHEET_HEIGHT_RATIO = 0.88

        /**
         * Abre el chat, salvo que ya esté abierto (dos toques rápidos a la
         * burbuja). Con [voice] se abre escuchando o, si ya se dijo la pregunta
         * al llamarlo ([question]), contestándola.
         */
        fun show(fragmentManager: FragmentManager, voice: Boolean = false, question: String? = null) {
            if (fragmentManager.findFragmentByTag(TAG) == null) {
                AssistantSheet().apply {
                    arguments = bundleOf(ARG_VOICE to voice, ARG_QUESTION to question)
                }.show(fragmentManager, TAG)
            }
        }
    }
}
