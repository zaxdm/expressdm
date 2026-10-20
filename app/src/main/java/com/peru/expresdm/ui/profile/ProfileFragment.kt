package com.peru.expresdm.ui.profile

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import android.text.format.DateFormat
import com.google.android.material.divider.MaterialDividerItemDecoration
import com.peru.expresdm.BuildConfig
import com.peru.expresdm.ui.themeColor
import java.util.Date
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.peru.expresdm.R
import com.peru.expresdm.data.Premium
import com.peru.expresdm.data.ProfilePhotoStore
import com.peru.expresdm.data.ProfileStats
import com.peru.expresdm.data.RouteRepository
import com.peru.expresdm.data.net.AccountRepository
import com.peru.expresdm.data.net.ApiClient
import com.peru.expresdm.data.net.ApiFailure
import com.peru.expresdm.data.net.ApiResult
import com.peru.expresdm.data.net.ImageUploader
import com.peru.expresdm.data.net.Session
import com.peru.expresdm.databinding.DialogEditNameBinding
import com.peru.expresdm.databinding.FragmentProfileBinding
import com.peru.expresdm.ui.Initials
import com.peru.expresdm.ui.RouteImages
import com.peru.expresdm.ui.account.AccountSheet
import com.peru.expresdm.ui.account.PremiumSheet
import com.peru.expresdm.ui.showRouteOnMap
import kotlinx.coroutines.launch

/**
 * Quién eres, lo que llevas hecho y tus rutas de siempre.
 *
 * Las favoritas no se marcan a mano: salen del uso, así que esta pantalla se
 * llena sola conforme la app se va usando.
 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ProfileViewModel by viewModels()

    /** El selector de fotos de Android: no pide permiso para leer la galería. */
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && _binding != null) savePhoto(uri)
    }

    private val adapter by lazy {
        // Una favorita se traza en el mapa del inicio, igual que desde la lista de cercanas.
        ProfileRouteAdapter(onRouteClick = { route -> showRouteOnMap(route.id) })
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.favoritesRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.favoritesRecycler.adapter = adapter
        // La lista crece con su contenido dentro de la pantalla, que es la que
        // se desplaza. En XML este atributo revienta en algunos teléfonos.
        binding.favoritesRecycler.isNestedScrollingEnabled = false
        // Una línea fina entre ruta y ruta, no después de la última.
        binding.favoritesRecycler.addItemDecoration(
            MaterialDividerItemDecoration(requireContext(), MaterialDividerItemDecoration.VERTICAL).apply {
                isLastItemDecorated = false
                dividerColor = requireContext().themeColor(com.google.android.material.R.attr.colorOutline)
                val inset = (14 * resources.displayMetrics.density).toInt()
                dividerInsetStart = inset
                dividerInsetEnd = inset
            }
        )
        binding.profileFooter.text = getString(R.string.perfil_footer, BuildConfig.VERSION_NAME)

        binding.rowPremium.setOnClickListener { PremiumSheet.show(childFragmentManager) }

        binding.rowSettings.setOnClickListener {
            findNavController().navigate(R.id.action_profile_to_settings)
        }
        binding.btnProfileSettings.setOnClickListener {
            findNavController().navigate(R.id.action_profile_to_settings)
        }

        binding.rowSupport.setOnClickListener {
            findNavController().navigate(R.id.action_profile_to_support)
        }

        binding.btnAccount.setOnClickListener { AccountSheet.show(childFragmentManager) }
        binding.avatarFrame.setOnClickListener { onAvatarClick() }
        binding.identityRow.setOnClickListener { onAvatarClick() }
        binding.rowSignOut.setOnClickListener { signOut() }

        childFragmentManager.setFragmentResultListener(AccountSheet.RESULT_KEY, viewLifecycleOwner) { _, _ ->
            // Con la sesión recién abierta, el catálogo compartido puede traer
            // rutas de otros usuarios.
            viewLifecycleOwner.lifecycleScope.launch { RouteRepository.refresh() }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.favorites.collect { items ->
                        adapter.submitList(items)
                        binding.emptyFavorites.isVisible = items.isEmpty()
                        binding.favoritesCard.isVisible = items.isNotEmpty()
                        binding.favoritesCount.isVisible = items.isNotEmpty()
                        binding.favoritesCount.text = items.size.toString()
                    }
                }

                launch { viewModel.stats.collect { renderStats(it) } }

                launch {
                    AccountRepository.session.collect {
                        renderAccount(it)
                        renderPremium(it)
                    }
                }

                launch {
                    viewModel.messages.collect { message ->
                        Snackbar.make(
                            binding.root,
                            message.text ?: getString(message.fallback),
                            Snackbar.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    /** La tarjeta de identidad: quién eres, o una invitación a entrar. */
    private fun renderAccount(session: Session?) {
        val binding = _binding ?: return
        val name = session?.displayName?.takeIf { it.isNotBlank() }

        binding.profileName.text = name ?: getString(R.string.profile_guest)
        binding.profileAvatar.text = Initials.of(name)
        binding.profileAvatar.isVisible = name != null
        binding.profileAvatarIcon.isVisible = name == null
        binding.profileSubtitle.text = when {
            session != null -> session.email
            !ApiClient.isConfigured -> getString(R.string.account_no_server)
            else -> getString(R.string.profile_guest_subtitle)
        }

        val signedIn = session != null
        binding.rowSignOut.isVisible = signedIn
        binding.signOutDivider.isVisible = signedIn
        // Sin servidor no hay cuentas que abrir: el botón solo estorbaría.
        binding.btnAccount.isVisible = !signedIn && ApiClient.isConfigured

        binding.avatarFrame.isClickable = signedIn
        binding.identityRow.isClickable = signedIn
        binding.avatarFrame.contentDescription = getString(R.string.perfil_edit_profile).takeIf { signedIn }
        binding.avatarCamera.isVisible = signedIn
        showPhoto(null)
        if (session != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                val photo = ProfilePhotoStore.load(requireContext().applicationContext, session.userId)
                // Mientras se leía pudo cambiar la cuenta: la foto es de quien era.
                if (AccountRepository.session.value?.userId != session.userId) return@launch
                if (photo != null) {
                    showPhoto(photo)
                } else {
                    // En un teléfono nuevo no hay copia local: se trae la del servidor.
                    _binding?.let { RouteImages.show(it.profilePhoto, session.avatarUrl, getString(R.string.profile_photo)) }
                }
            }
        }
    }

    /**
     * La tarjeta negra de Premium: activo, con hasta cuándo; si no, una
     * invitación a verlo.
     */
    private fun renderPremium(session: Session?) {
        val binding = _binding ?: return
        val active = Premium.isActive(session)
        binding.premiumBadge.isVisible = active
        binding.premiumSubtitle.text = when {
            active -> getString(
                R.string.perfil_premium_until,
                DateFormat.getMediumDateFormat(requireContext()).format(Date(session!!.premiumUntil!!))
            )
            else -> getString(R.string.perfil_premium_invite)
        }
    }

    /** La foto tapa las iniciales; sin foto, se ven ellas. */
    private fun showPhoto(photo: Bitmap?) {
        val binding = _binding ?: return
        binding.profilePhoto.setImageBitmap(photo)
        binding.profilePhoto.isVisible = photo != null
    }

    /** El lápiz del avatar: cambiar el nombre o la foto, y quitarla si hay una. */
    private fun onAvatarClick() {
        if (AccountRepository.session.value == null) return
        val options = buildList {
            add(getString(R.string.profile_edit_name) to ::askForName)
            add(getString(R.string.profile_photo_change) to ::pickPhoto)
            if (binding.profilePhoto.isVisible) add(getString(R.string.profile_photo_remove) to ::removePhoto)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.perfil_edit_profile)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .show()
    }

    private fun pickPhoto() {
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun savePhoto(source: Uri) {
        val userId = AccountRepository.session.value?.userId ?: return
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            // Con servidor, la foto se sube para verla en cualquier teléfono. El
            // servidor la revisa: si la rechaza, no se pone y se dice por qué.
            var onlyLocal = !ApiClient.isConfigured
            if (ApiClient.isConfigured) {
                val uploaded = ImageUploader.upload(context, source, ImageUploader.Purpose.AVATAR)
                val saved = if (uploaded is ApiResult.Success) AccountRepository.setAvatar(uploaded.data) else uploaded
                if (saved is ApiResult.Failure) {
                    if (saved.reason == ApiFailure.REJECTED) {
                        showMessage(saved.message ?: getString(R.string.profile_photo_failed))
                        return@launch
                    }
                    // Sin conexión, o un servidor que no acepta fotos: se queda en este teléfono.
                    onlyLocal = true
                }
            }

            val photo = ProfilePhotoStore.save(context, userId, source)
            if (photo == null) {
                showMessage(getString(R.string.profile_photo_failed))
            } else if (AccountRepository.session.value?.userId == userId) {
                showPhoto(photo)
                if (onlyLocal && ApiClient.isConfigured) showMessage(getString(R.string.profile_photo_local_only))
            }
        }
    }

    private fun showMessage(text: String) {
        val binding = _binding ?: return
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()
    }

    private fun removePhoto() {
        val userId = AccountRepository.session.value?.userId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            ProfilePhotoStore.delete(requireContext().applicationContext, userId)
            showPhoto(null)
            if (ApiClient.isConfigured) AccountRepository.setAvatar(null)
        }
    }

    /** Tres números con lo que el usuario lleva aportado. */
    private fun renderStats(stats: ProfileStats) {
        val binding = _binding ?: return
        // Grabar rutas es cosa de quien administra el catálogo: a los demás
        // les dice más cuántas tienen guardadas.
        val records = AccountRepository.session.value?.isAdmin == true
        binding.statRoutes.text = (if (records) stats.recordedRoutes else stats.savedRoutes).toString()
        binding.statRoutesLabel.setText(if (records) R.string.profile_stat_recorded else R.string.profile_stat_saved)
        binding.statAlerts.text = stats.publishedAlerts.toString()
        binding.statUsed.text = stats.usedRoutes.toString()
        // Tres ceros no dicen nada: se explica de dónde saldrán.
        binding.emptyActivity.isVisible = stats.isEmpty
    }

    private fun askForName() {
        val dialogBinding = DialogEditNameBinding.inflate(layoutInflater)
        dialogBinding.nameInput.setText(AccountRepository.session.value?.displayName.orEmpty())
        dialogBinding.nameInput.setSelection(dialogBinding.nameInput.text?.length ?: 0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_edit_name)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = dialogBinding.nameInput.text?.toString().orEmpty().trim()
                if (name.isEmpty()) {
                    Snackbar.make(binding.root, R.string.account_name_required, Snackbar.LENGTH_SHORT).show()
                } else {
                    viewModel.rename(name)
                }
            }
            .show()
    }

    private fun signOut() {
        AccountRepository.signOut()
        Snackbar.make(binding.root, R.string.account_signed_out, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        binding.favoritesRecycler.adapter = null
        _binding = null
        super.onDestroyView()
    }
}
