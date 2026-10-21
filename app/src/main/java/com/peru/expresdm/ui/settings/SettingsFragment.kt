package com.peru.expresdm.ui.settings

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.peru.expresdm.R
import com.peru.expresdm.data.AlertType
import com.peru.expresdm.data.AppLanguage
import com.peru.expresdm.data.AppSettings
import com.peru.expresdm.data.ArrivalSms
import com.peru.expresdm.data.ArrivalTone
import com.peru.expresdm.data.CustomSound
import com.peru.expresdm.data.Premium
import com.peru.expresdm.data.SmsContact
import com.peru.expresdm.data.ThemeMode
import com.peru.expresdm.databinding.FragmentSettingsBinding
import com.peru.expresdm.databinding.ItemSettingRowBinding
import com.peru.expresdm.tracking.AlertWatchWorker
import com.peru.expresdm.tracking.ArrivalAlarmService
import com.peru.expresdm.tracking.ArrivalSignal
import com.peru.expresdm.tracking.ArrivalSmsSender
import com.peru.expresdm.tracking.SmsTestResult
import com.peru.expresdm.tracking.PushRegistration
import com.peru.expresdm.ui.AlertStyle
import com.peru.expresdm.ui.account.PremiumSheet
import com.peru.expresdm.ui.themeColor
import com.peru.expresdm.voice.Speaker
import com.peru.expresdm.voice.VoiceInput
import kotlinx.coroutines.launch

/**
 * Ajustes del aviso de bajada, de las alertas, de apariencia y de idioma.
 *
 * Las listas de opciones (distancia, sonido, tema, idioma) son filas que
 * enseñan lo elegido y abren un selector, para que la pantalla no se llene.
 *
 * Cambiar el tema o el idioma recrea la Activity: Android necesita volver a
 * resolver los recursos. Por eso los valores se guardan antes de aplicarlos.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: AppSettings

    private val distances = listOf(
        100 to R.string.settings_arrival_100,
        AppSettings.DEFAULT_ARRIVAL_DISTANCE to R.string.settings_arrival_200,
        500 to R.string.settings_arrival_500
    )

    /** Qué botón del selector de tema corresponde a cada modo. */
    private val themeButtons = mapOf(
        ThemeMode.SYSTEM to R.id.themeSystem,
        ThemeMode.LIGHT to R.id.themeLight,
        ThemeMode.DARK to R.id.themeDark
    )

    private val languages = listOf(
        AppLanguage.SYSTEM to R.string.settings_language_system,
        AppLanguage.SPANISH to R.string.settings_language_spanish,
        AppLanguage.ENGLISH to R.string.settings_language_english
    )

    /** Evita reaccionar a los cambios que provoca el propio código al pintar. */
    private var bindingSelection = false

    /** El permiso de notificaciones de Android 13 en adelante; si lo niegan, no se insiste. */
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** El micrófono, para la activación por voz: sin él la casilla no se queda marcada. */
    private val askMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val binding = _binding ?: return@registerForActivityResult
        settings.voiceWake = granted
        bindingSelection = true
        binding.voiceWake.isChecked = granted
        bindingSelection = false
        if (!granted) showMessage(getString(R.string.voice_needs_microphone))
    }

    /** Lo que se iba a hacer cuando se pidió el permiso de SMS: activar el aviso o mandar la prueba. */
    private var afterSmsPermission: (() -> Unit)? = null

    private val askSms = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val next = afterSmsPermission
        afterSmsPermission = null
        if (_binding == null) return@registerForActivityResult
        if (granted) {
            next?.invoke()
        } else {
            settings.arrivalSms = false
            renderSms()
            showMessage(getString(R.string.settings_sms_need_permission))
        }
    }

    /**
     * La agenda de Android, mostrando solo números: devuelve el elegido con
     * permiso para leer solo ese, sin pedir acceso a todos los contactos.
     */
    private val pickContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        if (_binding == null) return@registerForActivityResult
        val contact = try {
            requireContext().contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val number = ArrivalSms.cleanNumber(cursor.getString(1).orEmpty()) ?: return@use null
                SmsContact(cursor.getString(0).orEmpty(), number)
            }
        } catch (e: RuntimeException) {
            null
        }
        if (contact == null) {
            showMessage(getString(R.string.settings_sms_bad_number))
        } else {
            settings.arrivalSmsContact = contact
            renderSms()
        }
    }

    /** El selector de archivos de Android, para añadir un sonido propio. */
    private val pickSound = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && _binding != null) addCustomSound(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = AppSettings.get(requireContext())

        binding.btnBack.setOnClickListener { findNavController().popBackStack() }

        setUpRow(binding.rowArrivalDistance, R.drawable.ic_place, R.string.settings_arrival_distance)
        setUpRow(binding.rowArrivalTone, R.drawable.ic_arrival_alarm, R.string.settings_arrival_tone)
        setUpRow(binding.rowLanguage, R.drawable.ic_language, R.string.settings_language)

        renderCurrentSelection()
        renderAlertTypes()
        renderPalette()

        binding.rowArrivalDistance.root.setOnClickListener {
            val selected = distances.indexOfFirst { it.first == settings.arrivalDistanceMeters }
                .takeIf { it >= 0 } ?: 1
            pickOne(R.string.settings_arrival_distance, distances.map { getString(it.second) }, selected) {
                settings.arrivalDistanceMeters = distances[it].first
                renderCurrentSelection()
            }
        }

        binding.arrivalVibration.setOnCheckedChangeListener { _, checked ->
            if (bindingSelection) return@setOnCheckedChangeListener
            settings.arrivalVibration = checked
        }

        binding.arrivalSound.setOnCheckedChangeListener { _, checked ->
            if (bindingSelection) return@setOnCheckedChangeListener
            settings.arrivalSound = checked
        }

        binding.arrivalVoice.setOnCheckedChangeListener { _, checked ->
            if (bindingSelection) return@setOnCheckedChangeListener
            settings.arrivalVoice = checked
        }

        // El preaviso es de Premium: sin él la casilla no se queda marcada y se
        // enseña qué es.
        binding.arrivalEarly.setOnCheckedChangeListener { button, checked ->
            if (bindingSelection) return@setOnCheckedChangeListener
            if (checked && !Premium.active) {
                bindingSelection = true
                button.isChecked = false
                bindingSelection = false
                PremiumSheet.show(childFragmentManager)
            } else {
                settings.arrivalEarlyWarning = checked
            }
        }

        // La voz es de Premium: sin él las casillas no se quedan marcadas.
        premiumCheck(binding.voiceAnswers) { settings.voiceAnswers = it }
        premiumCheck(binding.voiceGuide) { settings.voiceGuide = it }
        premiumCheck(binding.voiceWake) { checked ->
            val context = requireContext()
            when {
                !checked -> settings.voiceWake = false
                !VoiceInput.isAvailable(context) -> {
                    uncheck(binding.voiceWake)
                    showMessage(getString(R.string.voice_unavailable))
                }
                VoiceInput.hasPermission(context) -> settings.voiceWake = true
                else -> askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        setUpSms()

        binding.alertNotifications.setOnCheckedChangeListener { _, checked ->
            if (bindingSelection) return@setOnCheckedChangeListener
            settings.alertNotifications = checked
            AlertWatchWorker.sync(requireContext())
            PushRegistration.sync(requireContext())
            // Sin permiso de notificaciones, el aviso no llegaría a verse.
            if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        renderTones()
        binding.rowArrivalTone.root.setOnClickListener { pickTone() }

        binding.btnTestArrival.setOnClickListener { testArrival() }

        // El tema se elige tocando uno de los tres botones, sin abrir un selector.
        binding.themeToggle.addOnButtonCheckedListener { _, buttonId, isChecked ->
            if (!isChecked || bindingSelection) return@addOnButtonCheckedListener
            val mode = themeButtons.entries.firstOrNull { it.value == buttonId }?.key
                ?: return@addOnButtonCheckedListener
            if (mode != settings.themeMode) settings.themeMode = mode
        }
        // Tocar el texto también cambia el preaviso.
        binding.arrivalEarlyLabel.setOnClickListener { binding.arrivalEarly.toggle() }

        binding.rowLanguage.root.setOnClickListener {
            val selected = languages.indexOfFirst { it.first == settings.language }
            pickOne(R.string.settings_language, languages.map { getString(it.second) }, selected) {
                settings.language = languages[it].first
            }
        }
    }

    // --- Aviso por SMS al llegar (Premium) ---

    private fun setUpSms() {
        setUpRow(binding.rowSmsContact, R.drawable.ic_fav_family, R.string.settings_sms_contact)
        renderSms()

        binding.arrivalSms.setOnCheckedChangeListener { box, checked ->
            if (bindingSelection) return@setOnCheckedChangeListener
            when {
                !checked -> settings.arrivalSms = false
                !Premium.active -> {
                    uncheck(box)
                    PremiumSheet.show(childFragmentManager)
                }
                !ArrivalSmsSender.canSendSms(requireContext()) -> {
                    uncheck(box)
                    showMessage(getString(R.string.settings_sms_unavailable))
                }
                else -> withSmsPermission {
                    settings.arrivalSms = true
                    renderSms()
                    // Sin a quién avisar no sirve de nada: se pregunta ya.
                    if (settings.arrivalSmsContact == null) chooseContact()
                }
            }
        }
        binding.arrivalSmsOffline.setOnCheckedChangeListener { _, checked ->
            if (bindingSelection) return@setOnCheckedChangeListener
            settings.arrivalSmsOnlyOffline = checked
        }
        binding.rowSmsContact.root.setOnClickListener {
            if (Premium.active) chooseContact() else PremiumSheet.show(childFragmentManager)
        }
        binding.btnTestSms.setOnClickListener { testSms() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ArrivalSmsSender.testResult.collect { renderSmsTest(it) }
            }
        }
    }

    private fun renderSms() {
        bindingSelection = true
        binding.arrivalSms.isChecked = settings.arrivalSms && Premium.active &&
            ArrivalSmsSender.hasPermission(requireContext())
        binding.arrivalSmsOffline.isChecked = settings.arrivalSmsOnlyOffline
        bindingSelection = false
        val contact = settings.arrivalSmsContact
        binding.rowSmsContact.settingSummary.text = when {
            contact == null -> getString(R.string.settings_sms_contact_none)
            contact.name.isBlank() -> ArrivalSms.displayNumber(contact.number)
            else -> "${contact.name} · ${ArrivalSms.displayNumber(contact.number)}"
        }
    }

    private fun renderSmsTest(result: SmsTestResult?) {
        val contact = settings.arrivalSmsContact
        val who = contact?.name?.ifBlank { null } ?: contact?.number?.let(ArrivalSms::displayNumber).orEmpty()
        binding.smsTestStatus.isVisible = result != null
        binding.smsTestStatus.text = when (result) {
            null -> ""
            SmsTestResult.SENDING -> getString(R.string.settings_sms_sending)
            SmsTestResult.SENT -> getString(R.string.settings_sms_test_sent, who)
            SmsTestResult.NO_SIGNAL -> getString(R.string.settings_sms_test_no_signal)
            SmsTestResult.FAILED -> getString(R.string.settings_sms_test_failed)
        }
        binding.btnTestSms.isEnabled = result != SmsTestResult.SENDING
    }

    /** Hace [action] con el permiso de SMS; si no lo hay, lo pide antes. */
    private fun withSmsPermission(action: () -> Unit) {
        if (ArrivalSmsSender.hasPermission(requireContext())) {
            action()
        } else {
            afterSmsPermission = action
            askSms.launch(Manifest.permission.SEND_SMS)
        }
    }

    private fun chooseContact() {
        val options = mutableListOf(getString(R.string.settings_sms_pick), getString(R.string.settings_sms_type))
        if (settings.arrivalSmsContact != null) options += getString(R.string.settings_sms_remove)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_sms_contact)
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> openContacts()
                    1 -> typeNumber()
                    else -> {
                        settings.arrivalSmsContact = null
                        renderSms()
                    }
                }
            }
            .show()
    }

    private fun openContacts() {
        try {
            pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
        } catch (e: android.content.ActivityNotFoundException) {
            // Un teléfono sin agenda: queda escribir el número.
            typeNumber()
        }
    }

    private fun typeNumber() {
        val context = requireContext()
        val field = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            hint = getString(R.string.settings_sms_number_hint)
            setText(settings.arrivalSmsContact?.number.orEmpty())
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val holder = FrameLayout(context).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(field)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_sms_contact)
            .setView(holder)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val number = ArrivalSms.cleanNumber(field.text.toString())
                if (number == null) {
                    showMessage(getString(R.string.settings_sms_bad_number))
                } else {
                    settings.arrivalSmsContact = SmsContact("", number)
                    renderSms()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun testSms() {
        when {
            !Premium.active -> PremiumSheet.show(childFragmentManager)
            !ArrivalSmsSender.canSendSms(requireContext()) -> showMessage(getString(R.string.settings_sms_unavailable))
            settings.arrivalSmsContact == null -> {
                showMessage(getString(R.string.settings_sms_need_contact))
                chooseContact()
            }
            else -> withSmsPermission {
                settings.arrivalSmsContact?.let { ArrivalSmsSender.sendTest(requireContext(), it) }
            }
        }
    }

    private fun setUpRow(row: ItemSettingRowBinding, @DrawableRes icon: Int, @StringRes title: Int) {
        row.settingIcon.setImageResource(icon)
        row.settingTitle.setText(title)
    }

    /** Un selector de una sola opción; se cierra al elegir. */
    private fun pickOne(@StringRes title: Int, labels: List<String>, selected: Int, onPicked: (Int) -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, which ->
                dialog.dismiss()
                if (which != selected) onPicked(which)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renderCurrentSelection() {
        bindingSelection = true

        val distance = distances.firstOrNull { it.first == settings.arrivalDistanceMeters } ?: distances[1]
        binding.rowArrivalDistance.settingSummary.setText(distance.second)
        binding.arrivalVibration.isChecked = settings.arrivalVibration
        binding.arrivalSound.isChecked = settings.arrivalSound
        binding.arrivalVoice.isChecked = settings.arrivalVoice
        binding.arrivalEarly.isChecked = settings.arrivalEarlyWarning && Premium.active
        binding.voiceAnswers.isChecked = settings.voiceAnswers && Premium.active
        binding.voiceGuide.isChecked = settings.voiceGuide && Premium.active
        binding.voiceWake.isChecked = settings.voiceWake && Premium.active &&
            VoiceInput.hasPermission(requireContext())
        binding.alertNotifications.isChecked = settings.alertNotifications

        themeButtons[settings.themeMode]?.let { binding.themeToggle.check(it) }
        languages.firstOrNull { it.first == settings.language }
            ?.let { binding.rowLanguage.settingSummary.setText(it.second) }

        bindingSelection = false
    }

    /**
     * Una casilla de algo que es de Premium: marcarla sin serlo la deja como
     * estaba y enseña qué trae Premium.
     */
    private fun premiumCheck(box: CompoundButton, onChanged: (Boolean) -> Unit) {
        box.setOnCheckedChangeListener { _, checked ->
            if (bindingSelection) return@setOnCheckedChangeListener
            if (checked && !Premium.active) {
                uncheck(box)
                PremiumSheet.show(childFragmentManager)
            } else {
                onChanged(checked)
            }
        }
    }

    private fun uncheck(box: CompoundButton) {
        bindingSelection = true
        box.isChecked = false
        bindingSelection = false
    }

    /** Un chip por tipo de alerta: los apagados dejan de salir en el mapa y en la lista. */
    private fun renderAlertTypes() {
        val enabled = settings.alertTypes.value
        binding.alertTypesContainer.removeAllViews()

        AlertType.entries.forEach { type ->
            val check = layoutInflater
                .inflate(R.layout.item_setting_check, binding.alertTypesContainer, false) as Chip
            check.setText(AlertStyle.label(type))
            // El punto del color del tipo, como en el mapa.
            check.chipIconTint = ContextCompat.getColorStateList(requireContext(), AlertStyle.color(type))
            check.isChecked = type in enabled
            // Sin id propio, Android restauraría todos los chips con el estado del último.
            check.isSaveEnabled = false
            check.setOnCheckedChangeListener { _, checked -> settings.setAlertTypeEnabled(type, checked) }
            binding.alertTypesContainer.addView(check)
        }
    }

    /** Los tonos de la app y, debajo, los que añadió el usuario: (lo que se guarda, nombre). */
    private fun toneOptions(): List<Pair<String, String>> =
        ArrivalTone.entries.map { it.key to getString(labelOf(it)) } +
            settings.customSounds.map { it.uri to it.name }

    /** Pone en la fila el sonido elegido y las notas que le tocan. */
    private fun renderTones() {
        val selected = ArrivalSignal.soundChoice(requireContext())
        binding.rowArrivalTone.settingSummary.text =
            toneOptions().firstOrNull { it.first == selected }?.second ?: getString(labelOf(ArrivalTone.DEFAULT))

        binding.customSoundsNote.isVisible = settings.customSounds.isNotEmpty()
        binding.tonePremiumNote.isVisible = !Premium.active
    }

    /**
     * El selector de sonidos. Cada uno suena al tocarlo, porque nadie escoge un
     * sonido por el nombre; los del usuario se quitan manteniéndolos presionados.
     */
    private fun pickTone() {
        val options = toneOptions()
        val selected = options.indexOfFirst { it.first == ArrivalSignal.soundChoice(requireContext()) }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_arrival_tone)
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), selected) { dialog, which ->
                val choice = options[which].first
                // Elegir el sonido es de Premium: sin él se queda el de siempre.
                if (!Premium.active && choice != ArrivalTone.DEFAULT.key) {
                    dialog.dismiss()
                    PremiumSheet.show(childFragmentManager)
                    return@setSingleChoiceItems
                }
                settings.arrivalSoundChoice = choice
                ArrivalSignal.play(requireContext(), vibrate = false, sound = true)
                renderTones()
            }
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.settings_arrival_tone_add) { _, _ -> addSound() }
            .setOnDismissListener { ArrivalSignal.stop() }
            .show()
        dialog.listView.setOnItemLongClickListener { _, _, position, _ ->
            val choice = options[position].first
            if (ArrivalTone.fromKey(choice) != null) return@setOnItemLongClickListener false
            dialog.dismiss()
            removeCustomSound(choice)
            true
        }
    }

    private fun addSound() {
        if (!Premium.active) {
            PremiumSheet.show(childFragmentManager)
        } else if (settings.customSounds.size >= AppSettings.MAX_CUSTOM_SOUNDS) {
            showMessage(getString(R.string.settings_arrival_tone_limit, AppSettings.MAX_CUSTOM_SOUNDS))
        } else {
            pickSound.launch(arrayOf("audio/*"))
        }
    }

    private fun addCustomSound(uri: Uri) {
        val context = requireContext()
        val choice = uri.toString()
        if (settings.customSounds.none { it.uri == choice }) {
            // Sin este permiso, el archivo dejaría de poder leerse al reiniciar el teléfono.
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                showMessage(getString(R.string.settings_arrival_tone_failed))
                return
            }
            settings.customSounds = settings.customSounds + CustomSound(choice, nameOf(uri))
        }
        settings.arrivalSoundChoice = choice
        renderTones()
        ArrivalSignal.play(context, vibrate = false, sound = true)
    }

    private fun removeCustomSound(choice: String) {
        try {
            requireContext().contentResolver
                .releasePersistableUriPermission(Uri.parse(choice), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // Ya no lo teníamos: no hay nada que soltar.
        }
        settings.customSounds = settings.customSounds.filterNot { it.uri == choice }
        if (settings.arrivalSoundChoice == choice) settings.arrivalSoundChoice = ArrivalTone.DEFAULT.key
        ArrivalSignal.stop()
        renderTones()
        showMessage(getString(R.string.settings_arrival_tone_removed))
    }

    /** El nombre del archivo sin la extensión, que es como lo reconoce quien lo eligió. */
    private fun nameOf(uri: Uri): String {
        val name = requireContext().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        return name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: getString(R.string.settings_arrival_tone_custom)
    }

    @StringRes
    private fun labelOf(tone: ArrivalTone): Int = when (tone) {
        ArrivalTone.BELL -> R.string.settings_arrival_tone_bell
        ArrivalTone.CHIME -> R.string.settings_arrival_tone_chime
        ArrivalTone.BEEP -> R.string.settings_arrival_tone_beep
        ArrivalTone.SIREN -> R.string.settings_arrival_tone_siren
        ArrivalTone.MELODY -> R.string.settings_arrival_tone_melody
    }

    private fun showMessage(text: String) {
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()
    }

    /** Hace vibrar y sonar el aviso tal como quedó configurado. */
    private fun testArrival() {
        val vibrate = settings.arrivalVibration
        val sound = settings.arrivalSound
        ArrivalSignal.play(requireContext(), vibrate = vibrate, sound = sound)
        if (settings.arrivalVoice) {
            Speaker.speak(
                requireContext(),
                getString(R.string.arrival_arrived, getString(R.string.settings_arrival_voice_sample)),
                delayMs = if (sound) ArrivalAlarmService.VOICE_AFTER_SOUND_MS else 0
            )
        }

        val message = if (vibrate || sound || settings.arrivalVoice) {
            getString(R.string.settings_arrival_test_done, settings.arrivalDistanceMeters)
        } else {
            getString(R.string.settings_arrival_test_silent)
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    /** Muestra los colores del tema activo, para ver el efecto del cambio. */
    private fun renderPalette() {
        val context = requireContext()
        val swatches = listOf(
            // El amarillo de marca y su versión suave; el negro ya sale al final.
            binding.swatchPrimary to com.google.android.material.R.attr.colorTertiary,
            binding.swatchTertiary to com.google.android.material.R.attr.colorPrimaryContainer,
            binding.swatchSecondary to com.google.android.material.R.attr.colorSecondary,
            binding.swatchNeutral to com.google.android.material.R.attr.colorOnSurfaceVariant,
            binding.swatchSurface to com.google.android.material.R.attr.colorSurfaceVariant,
            binding.swatchInk to com.google.android.material.R.attr.colorOnSurface
        )

        swatches.forEach { (view, attr) ->
            view.backgroundTintList = ColorStateList.valueOf(context.themeColor(attr))
        }
    }

    override fun onDestroyView() {
        // Un sonido de prueba no sigue sonando en otra pantalla.
        ArrivalSignal.stop()
        _binding = null
        super.onDestroyView()
    }
}
