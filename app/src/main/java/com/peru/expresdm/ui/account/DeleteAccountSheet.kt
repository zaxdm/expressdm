package com.peru.expresdm.ui.account

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.peru.expresdm.R
import com.peru.expresdm.data.ProfilePhotoStore
import com.peru.expresdm.data.net.AccountRepository
import com.peru.expresdm.data.net.ApiFailure
import com.peru.expresdm.data.net.ApiResult
import com.peru.expresdm.databinding.SheetDeleteAccountBinding
import kotlinx.coroutines.launch

/**
 * Borrar la cuenta para siempre, como pide Google Play.
 *
 * Dice qué se pierde y pide la contraseña (vacía si se entra solo con Google)
 * y la palabra ELIMINAR, para que no se haga sin querer. Al terminar, la
 * sesión se cierra igual que con "Cerrar sesión".
 */
class DeleteAccountSheet : BottomSheetDialogFragment() {

    private var _binding: SheetDeleteAccountBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetDeleteAccountBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnDeleteBack.setOnClickListener { dismiss() }
        binding.btnDeleteAccount.setOnClickListener { delete() }
        // Al corregir lo escrito, el aviso de error ya no hace falta.
        binding.deletePasswordInput.doAfterTextChanged { binding.deletePasswordLayout.error = null }
        binding.deleteConfirmInput.doAfterTextChanged { binding.deleteConfirmLayout.error = null }
    }

    override fun onStart() {
        super.onStart()
        // Con el teclado abierto, la hoja sube para que el botón siga a la vista.
        @Suppress("DEPRECATION")
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        sheet.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        BottomSheetBehavior.from(sheet).apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun delete() {
        val word = getString(R.string.delete_account_word)
        val confirm = binding.deleteConfirmInput.text?.toString().orEmpty().trim()
        // La palabra se revisa aquí primero, para no gastar un intento en el servidor.
        if (!confirm.equals(word, ignoreCase = true)) {
            binding.deleteConfirmLayout.error = getString(R.string.delete_account_confirm_wrong)
            return
        }
        val password = binding.deletePasswordInput.text?.toString().orEmpty()
        val userId = AccountRepository.session.value?.userId
        val context = requireContext().applicationContext

        setBusy(true)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = AccountRepository.deleteAccount(password, word)
            if (result is ApiResult.Success) {
                // La foto de perfil guardada en este teléfono también se va.
                userId?.let { ProfilePhotoStore.delete(context, it) }
                Toast.makeText(context, R.string.delete_account_done, Toast.LENGTH_LONG).show()
                if (_binding != null) dismissAllowingStateLoss()
                return@launch
            }
            val binding = _binding ?: return@launch
            setBusy(false)
            val failure = result as ApiResult.Failure
            when {
                // La sesión ya no valía: se cerró sola y lo avisa la pantalla principal.
                failure.reason == ApiFailure.UNAUTHORIZED -> dismissAllowingStateLoss()
                failure.code == "contrasena_incorrecta" ->
                    binding.deletePasswordLayout.error = failure.message ?: getString(R.string.delete_account_failed)
                // Lo demás (la cuenta principal, demasiados intentos) lo explica el servidor.
                else -> Toast.makeText(
                    context,
                    failure.message ?: getString(R.string.delete_account_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** Mientras el servidor responde, el botón no se puede volver a tocar. */
    private fun setBusy(busy: Boolean) {
        binding.btnDeleteAccount.isEnabled = !busy
        binding.deleteProgress.isVisible = busy
        isCancelable = !busy
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "DeleteAccountSheet"

        fun show(manager: FragmentManager) {
            if (manager.findFragmentByTag(TAG) == null) DeleteAccountSheet().show(manager, TAG)
        }
    }
}
