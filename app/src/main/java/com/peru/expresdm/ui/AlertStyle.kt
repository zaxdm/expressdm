package com.peru.expresdm.ui

import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.peru.expresdm.R
import com.peru.expresdm.data.AlertType

/** Icono, color y nombre de cada tipo de alerta, iguales en el mapa y en la lista. */
object AlertStyle {

    @DrawableRes
    fun icon(type: AlertType): Int = when (type) {
        AlertType.TRAFFIC -> R.drawable.ic_alert_traffic
        AlertType.ACCIDENT -> R.drawable.ic_alert_accident
        AlertType.OVERCHARGE -> R.drawable.ic_alert_overcharge
        AlertType.ROADWORK -> R.drawable.ic_alert_roadwork
        AlertType.CROWDED -> R.drawable.ic_alert_crowded
        AlertType.DANGER -> R.drawable.ic_alert_danger
        AlertType.OTHER -> R.drawable.ic_info
    }

    @StringRes
    fun label(type: AlertType): Int = when (type) {
        AlertType.TRAFFIC -> R.string.alert_type_traffic
        AlertType.ACCIDENT -> R.string.alert_type_accident
        AlertType.OVERCHARGE -> R.string.alert_type_overcharge
        AlertType.ROADWORK -> R.string.alert_type_roadwork
        AlertType.CROWDED -> R.string.alert_type_crowded
        AlertType.DANGER -> R.string.alert_type_danger
        AlertType.OTHER -> R.string.alert_type_other
    }

    @ColorRes
    fun color(type: AlertType): Int = when (type) {
        AlertType.TRAFFIC -> R.color.alert_traffic
        AlertType.ACCIDENT -> R.color.alert_accident
        AlertType.OVERCHARGE -> R.color.alert_overcharge
        AlertType.ROADWORK -> R.color.alert_roadwork
        AlertType.CROWDED -> R.color.alert_crowded
        AlertType.DANGER -> R.color.alert_danger
        AlertType.OTHER -> R.color.alert_other
    }

    /** El fondo suave del mismo tipo, para poner el icono en su color encima. */
    @ColorRes
    fun softColor(type: AlertType): Int = when (type) {
        AlertType.TRAFFIC -> R.color.alert_traffic_soft
        AlertType.ACCIDENT -> R.color.alert_accident_soft
        AlertType.OVERCHARGE -> R.color.alert_overcharge_soft
        AlertType.ROADWORK -> R.color.alert_roadwork_soft
        AlertType.CROWDED -> R.color.alert_crowded_soft
        AlertType.DANGER -> R.color.alert_danger_soft
        AlertType.OTHER -> R.color.alert_other_soft
    }
}

/** "Ahora mismo", "Hace 5 min", "Hace 2 h" o "Hace 3 d". */
fun Context.formatTimeAgo(minutes: Long): String = when {
    minutes < 1 -> getString(R.string.time_now)
    minutes < 60 -> getString(R.string.time_minutes, minutes.toInt())
    minutes < 24 * 60 -> getString(R.string.time_hours, (minutes / 60).toInt())
    else -> getString(R.string.time_days, (minutes / (24 * 60)).toInt())
}
