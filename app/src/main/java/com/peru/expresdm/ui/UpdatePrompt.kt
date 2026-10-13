package com.peru.expresdm.ui

import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.peru.expresdm.R
import com.peru.expresdm.data.AppUpdate
import com.peru.expresdm.data.AppUpdates
import kotlinx.coroutines.launch

/**
 * Ofrece una versión nueva de la app y, si el usuario quiere, la descarga ahí
 * mismo y abre el instalador de Android.
 */
object UpdatePrompt {

    private const val PROGRESS_PADDING_DP = 24

    fun offer(activity: AppCompatActivity, update: AppUpdate) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_title, update.versionName))
            .setMessage(update.notes.ifBlank { activity.getString(R.string.update_message) })
            .setNegativeButton(R.string.update_later, null)
            .setPositiveButton(R.string.update_now) { _, _ -> download(activity, update) }
            .show()
    }

    private fun download(activity: AppCompatActivity, update: AppUpdate) {
        val progress = LinearProgressIndicator(activity).apply { isIndeterminate = true }
        val padding = (PROGRESS_PADDING_DP * activity.resources.displayMetrics.density).toInt()
        val holder = FrameLayout(activity).apply {
            setPadding(padding, padding, padding, 0)
            addView(progress)
        }

        val job = activity.lifecycleScope.launch {
            val apk = AppUpdates.download(activity.applicationContext, update) { percent ->
                progress.post {
                    if (percent != null) progress.setProgressCompat(percent, true)
                }
            }
            if (apk == null) {
                Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show()
            } else {
                AppUpdates.install(activity, apk)
            }
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_downloading)
            .setView(holder)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> job.cancel() }
            .show()
        job.invokeOnCompletion { progress.post { if (dialog.isShowing) dialog.dismiss() } }
    }
}
