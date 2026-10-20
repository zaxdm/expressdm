package com.peru.expresdm.ui.routes

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView
import com.peru.expresdm.R
import com.peru.expresdm.ui.themeColor

/*
 * Piezas pequeñas de la ficha de un micro. Quien aloja la ficha en el inicio
 * (RoutesFragment) les escribe textos pensados para un botón o un renglón;
 * estas vistas los acomodan a como se ven en el diseño, sin cambiar qué dicen.
 */

/**
 * Las paradas de "Por dónde pasa" como línea de tiempo: un círculo hueco del
 * color de la ruta por parada, unidos por una línea, y el último lleno.
 *
 * Cada fila es un item_route_stop: el color lo toma del tinte que quien la
 * llena le pone a `stopNumber`, y en ese mismo sitio dibuja el círculo.
 */
class StopTimelineLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val radius = 6.5f * density
    private val stroke = 2.5f * density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * density }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val surface = context.themeColor(com.google.android.material.R.attr.colorSurface)
    private val brandTag = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.brand))

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        // La etiqueta "SUBES AQUÍ" va siempre en el amarillo de marca.
        child.findViewById<View>(R.id.stopTag)?.backgroundTintList = brandTag
        markLast()
    }

    override fun onViewRemoved(child: View) {
        super.onViewRemoved(child)
        markLast()
    }

    /** El nombre de la última parada, el destino, va en negrita. */
    private fun markLast() {
        for (i in 0 until childCount) {
            val name = getChildAt(i).findViewById<TextView>(R.id.stopName) ?: continue
            val bold = i == childCount - 1
            if ((name.typeface?.isBold == true) != bold) {
                name.setTypeface(Typeface.create(name.typeface, if (bold) Typeface.BOLD else Typeface.NORMAL))
            }
        }
    }

    private fun dotOf(row: View): Pair<Float, Float>? {
        val dot = row.findViewById<View>(R.id.stopNumber) ?: return null
        if (row.visibility != VISIBLE) return null
        return (row.left + dot.left + dot.width / 2f) to (row.top + dot.top + dot.height / 2f)
    }

    private fun colorOf(row: View): Int =
        row.findViewById<View>(R.id.stopNumber)?.backgroundTintList?.defaultColor
            ?: context.themeColor(com.google.android.material.R.attr.colorOnSurface)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (childCount < 2) return
        val first = dotOf(getChildAt(0)) ?: return
        val last = dotOf(getChildAt(childCount - 1)) ?: return
        linePaint.color = colorOf(getChildAt(0))
        canvas.drawLine(first.first, first.second, last.first, last.second, linePaint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // Los círculos van encima de la línea y de las filas.
        for (i in 0 until childCount) {
            val row = getChildAt(i)
            val (x, y) = dotOf(row) ?: continue
            val color = colorOf(row)
            val isLast = i == childCount - 1
            fillPaint.color = if (isLast) color else surface
            canvas.drawCircle(x, y, radius, fillPaint)
            ringPaint.color = color
            canvas.drawCircle(x, y, radius - stroke / 2, ringPaint)
        }
    }
}

/**
 * El interruptor de "Avísame al llegar". Quien lo aloja le escribe "Activar"
 * o "Cancelar", como a un botón: de eso sale si está encendido, y el texto no
 * se enseña. Al tocarlo no cambia solo; espera a que el aviso de verdad se
 * ponga o se quite (puede hacer falta un permiso antes).
 */
class ArrivalSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialSwitchStyle
) : MaterialSwitch(context, attrs, defStyleAttr) {

    override fun toggle() = Unit

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (!text.isNullOrEmpty()) {
            isChecked = text.toString() == context.getString(R.string.arrival_action_cancel)
        }
        super.setText("", type)
    }
}

/**
 * El subtítulo de la tarjeta del aviso de llegada. El texto de reposo trae
 * delante "Avísame al llegar:", que aquí ya es el título de la tarjeta.
 */
class ArrivalCardText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : MaterialTextView(context, attrs) {

    override fun setText(text: CharSequence?, type: BufferType?) {
        val idle = context?.getString(R.string.arrival_card_idle)
        val shown = if (text != null && text.toString() == idle) {
            context.getString(R.string.ficha_arrival_idle)
        } else {
            text
        }
        super.setText(shown, type)
    }
}

/**
 * El texto de la tarjeta petróleo de caminar. "Camina 540 m · 8 min hasta
 * Av. España" se parte en dos renglones como en el diseño: arriba, en negrita,
 * cuánto caminar hasta el paradero; abajo, cuál es.
 */
class WalkCardText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : MaterialTextView(context, attrs) {

    override fun setText(text: CharSequence?, type: BufferType?) {
        val split = context?.getString(R.string.ficha_walk_split)
        // Solo el texto de caminar ("Camina …"); los demás avisos van tal cual.
        val walkStart = context?.getString(R.string.selected_walk)?.substringBefore("%1")
        val isWalk = text != null && !walkStart.isNullOrEmpty() && text.startsWith(walkStart)
        val at = if (!isWalk || split.isNullOrEmpty()) -1 else text!!.indexOf(split)
        if (text == null || split == null || at <= 0) {
            // Un aviso de un solo renglón, en negrita como el título de la tarjeta.
            val bold = text?.let {
                SpannableStringBuilder(it).apply {
                    setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            super.setText(bold, if (bold == null) type else BufferType.SPANNABLE)
            return
        }
        val title = context.getString(R.string.ficha_walk_to_stop, text.substring(0, at))
        val place = text.substring(at + split.length)
        val styled = SpannableStringBuilder(title).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append('\n')
            val start = length
            append(place)
            setSpan(AbsoluteSizeSpan(12, true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(
                ForegroundColorSpan(ContextCompat.getColor(context, R.color.ficha_petrol_subtext)),
                start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        super.setText(styled, BufferType.SPANNABLE)
    }
}
