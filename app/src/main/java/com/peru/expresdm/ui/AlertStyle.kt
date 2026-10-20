package com.peru.expresdm.ui

import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.peru.expresdm.R
import com.peru.expresdm.data.AlertType

/** Icono, color y nombre de cada tipo de alerta, iguales en el mapa y en la lista. */
object AlertStyle {

    /** Los iconos de la guía de estilo: ondas, "!", "S/", cono, dos cabezas, bandera y "…". */
    @DrawableRes
    fun icon(type: AlertType): Int = when (type) {
        AlertType.TRAFFIC -> R.drawable.alertas_ic_traffic
        AlertType.ACCIDENT -> R.drawable.alertas_ic_accident
        AlertType.OVERCHARGE -> R.drawable.alertas_ic_overcharge
        AlertType.ROADWORK -> R.drawable.alertas_ic_roadwork
        AlertType.CROWDED -> R.drawable.alertas_ic_crowded
        AlertType.DANGER -> R.drawable.alertas_ic_danger
        AlertType.OTHER -> R.drawable.alertas_ic_other
    }

    /** El texto del botón de publicar, que dice qué se va a publicar. */
    @StringRes
    fun publishLabel(type: AlertType?): Int = when (type) {
        AlertType.TRAFFIC -> R.string.alertas_publish_traffic
        AlertType.ACCIDENT -> R.string.alertas_publish_accident
        AlertType.OVERCHARGE -> R.string.alertas_publish_overcharge
        AlertType.ROADWORK -> R.string.alertas_publish_roadwork
        AlertType.CROWDED -> R.string.alertas_publish_crowded
        AlertType.DANGER -> R.string.alertas_publish_danger
        AlertType.OTHER, null -> R.string.create_alert_publish
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
