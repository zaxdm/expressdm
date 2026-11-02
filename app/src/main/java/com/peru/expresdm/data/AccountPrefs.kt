package com.peru.expresdm.data

import android.content.Context
import android.content.SharedPreferences
import com.peru.expresdm.data.net.SessionStore

/**
 * Las preferencias de quien usa la app ahora mismo.
 *
 * Lo que es de la persona (sus rutas guardadas, sus lugares, lo que más usa)
 * se guarda aparte para cada cuenta: al entrar con otra en el mismo teléfono
 * no se ve lo de la anterior. Sin sesión se usan las del teléfono, y la primera
 * cuenta que entra se las queda, para no perder lo que se hizo antes de
 * registrarse.
 */
internal class AccountPrefs(context: Context, private val baseName: String) {

    private val appContext = context.applicationContext
    private val sessions = SessionStore.get(appContext)

    init {
        synchronized(knownBaseNames) { knownBaseNames += baseName }
    }

    /** Las de la cuenta abierta, o las del teléfono si no hay ninguna. */
    var current: SharedPreferences = open(sessions.session.value?.userId)
        private set

    /** Avisa, con las preferencias ya cambiadas, cuando entra o sale una cuenta. */
    fun onAccountChanged(listener: () -> Unit) {
        sessions.onUserChanged { userId ->
            current = open(userId)
            listener()
        }
    }

    private fun open(userId: String?): SharedPreferences {
        val device = appContext.getSharedPreferences(baseName, Context.MODE_PRIVATE)
        if (userId.isNullOrBlank()) return device

        val own = appContext.getSharedPreferences("${baseName}_$userId", Context.MODE_PRIVATE)
        if (own.all.isEmpty() && device.all.isNotEmpty()) {
            copy(device, own)
            device.edit().clear().apply()
        }
        return own
    }

    companion object {
        /**
         * Los almacenes que guardan cosas por cuenta. Están escritos aquí y no
         * solo apuntados al crearse, porque al borrar la cuenta alguno puede no
         * haberse abierto todavía.
         */
        private val knownBaseNames = mutableSetOf(
            "expresdm_saved_routes",
            "expresdm_route_usage",
            "saved_places",
            "recent_places"
        )

        /**
         * Borra del teléfono todo lo guardado para [userId]: rutas guardadas,
         * lugares, las más usadas… Solo al eliminar la cuenta; al cerrar sesión
         * se conserva, para encontrarlo al volver a entrar.
         */
        fun wipe(context: Context, userId: String) {
            if (userId.isBlank()) return
            val app = context.applicationContext
            synchronized(knownBaseNames) { knownBaseNames.toList() }.forEach { baseName ->
                val name = "${baseName}_$userId"
                // Se vacía primero: Android guarda una copia en memoria que el
                // borrado del archivo no toca.
                app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                app.deleteSharedPreferences(name)
            }
        }
    }

    private fun copy(from: SharedPreferences, to: SharedPreferences) {
        val editor = to.edit()
        from.all.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        editor.apply()
    }
}
