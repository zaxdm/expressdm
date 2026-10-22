package com.peru.expresdm.ui.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peru.expresdm.R
import com.peru.expresdm.ai.AssistantRepository
import com.peru.expresdm.ai.AssistantResult
import com.peru.expresdm.ai.GeminiClient
import com.peru.expresdm.data.BusRoute
import com.peru.expresdm.data.net.ApiClient
import com.peru.expresdm.ui.LastPosition
import com.peru.expresdm.ui.currentLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Un mensaje de la conversación con el asistente. */
data class ChatMessage(
    val id: Long,
    val text: String,
    val fromUser: Boolean,
    val suggestedRoutes: List<BusRoute> = emptyList(),
    val isError: Boolean = false
)

/**
 * Conversación con el asistente.
 *
 * Vive en el ViewModel para que el hilo no se pierda al rotar la pantalla ni al
 * cambiar de pestaña y volver.
 */
class AssistantViewModel(app: Application) : AndroidViewModel(app) {

    private var nextId = 1L

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    private val _premiumOffer = MutableStateFlow(false)

    /** Se acabaron las preguntas del día: toca enseñar lo que da Premium. */
    val premiumOffer: StateFlow<Boolean> = _premiumOffer.asStateFlow()

    fun premiumOfferShown() {
        _premiumOffer.value = false
    }

    /** Con servidor, la clave de Gemini está allí; sin él, tiene que estar en la app. */
    val isAssistantAvailable: Boolean get() = ApiClient.isConfigured || GeminiClient.isConfigured

    /** La última pregunta se hizo hablando: su respuesta se lee en voz alta. */
    var lastAskedByVoice = false
        private set

    /** Hasta qué mensaje se leyó ya en voz alta, para no repetirlo al reabrir el chat. */
    var lastSpokenId = 0L

    fun send(question: String, byVoice: Boolean = false) {
        val text = question.trim()
        if (text.isEmpty() || _isThinking.value) return
        lastAskedByVoice = byVoice

        // El historial que se manda al modelo debe capturarse antes de añadir
        // la pregunta nueva, que se envía aparte.
        val history = _messages.value
            .filterNot { it.isError }
            .map { GeminiClient.Turn(it.text, it.fromUser) }
            .takeLast(MAX_HISTORY_TURNS)

        append(ChatMessage(nextId++, text, fromUser = true))
        _isThinking.value = true

        viewModelScope.launch {
            val message = when (val result = AssistantRepository.ask(text, history, whereAmI())) {
                is AssistantResult.Success -> ChatMessage(
                    id = nextId++,
                    text = result.answer.text,
                    fromUser = false,
                    suggestedRoutes = result.answer.suggestedRoutes
                )

                is AssistantResult.Failure -> ChatMessage(
                    id = nextId++,
                    text = AssistantRepository.describeFailure(result.reason),
                    fromUser = false,
                    isError = true
                )

                is AssistantResult.LimitReached -> {
                    _premiumOffer.value = true
                    ChatMessage(
                        id = nextId++,
                        text = result.message
                            ?: getApplication<Application>().getString(R.string.assistant_daily_limit),
                        fromUser = false,
                        isError = true
                    )
                }

                // Con servidor, el asistente gasta su cuota de IA: hace falta cuenta.
                AssistantResult.NeedsAccount -> ChatMessage(
                    id = nextId++,
                    text = getApplication<Application>().getString(R.string.assistant_needs_account),
                    fromUser = false,
                    isError = true
                )
            }

            _isThinking.value = false
            append(message)
        }
    }

    /**
     * Dónde está quien pregunta, para que el asistente parta de ahí. Solo si ya
     * dio permiso de ubicación: no se le pide por preguntar, y si el GPS tarda
     * vale la última posición conocida.
     */
    private suspend fun whereAmI(): Pair<Double, Double>? {
        val app = getApplication<Application>()
        val fix = app.currentLocation(timeoutMs = LOCATION_WAIT_MS)
        return fix?.let { it.latitude to it.longitude } ?: LastPosition.get(app)
    }

    private fun append(message: ChatMessage) {
        _messages.value = _messages.value + message
    }

    private companion object {
        /** Suficiente contexto para seguir el hilo sin encarecer cada consulta. */
        const val MAX_HISTORY_TURNS = 8

        /** Lo que se espera al GPS antes de preguntar sin él: la respuesta no debe tardar. */
        const val LOCATION_WAIT_MS = 3_000L
    }
}
