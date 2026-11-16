package app.tslink.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Small view kit for the phone screen: dark cards, text styles and buttons, no libraries. */
class Ui(private val ctx: Context) {
    companion object {
        // Same palette as the car page.
        val SURFACE = Color.parseColor("#1e1e1e")
        val OUTLINE = Color.parseColor("#3c4043")
        val TEXT = Color.parseColor("#e8eaed")
        val MUTED = Color.parseColor("#9aa0a6")
        val ACCENT = Color.parseColor("#8ab4f8")
        val ON_ACCENT = Color.parseColor("#062e6f")
        val OK = Color.parseColor("#81c995")
        val WARN = Color.parseColor("#fdd663")
        val ERR = Color.parseColor("#f28b82")
    }

    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun ripple(content: Drawable, radiusDp: Int) =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, rounded(Color.WHITE, radiusDp))

    fun text(size: Float, color: Int = TEXT, bold: Boolean = false) = TextView(ctx).apply {
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** A body line (wraps), with a little space above. */
    fun line(s: CharSequence = "", size: Float = 14f, color: Int = MUTED) = text(size, color).apply {
        text = s
        setLineSpacing(0f, 1.15f)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) }
    }

    /** Label on the left, selectable value on the right (addresses, URLs). */
    fun row(label: String, value: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(6), 0, dp(6))
        addView(text(14f, MUTED).apply { text = label }, LinearLayout.LayoutParams(dp(110), WRAP_CONTENT))
        addView(text(14f).apply {
            text = value
            setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    /** Rounded surface card with a small section title; add content to the returned layout. */
    fun card(parent: LinearLayout, title: String): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(SURFACE, 16)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        card.addView(text(12f, ACCENT, bold = true).apply {
            text = title.uppercase()
            letterSpacing = 0.08f
            setPadding(0, 0, 0, dp(6))
        })
        parent.addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) })
        return card
    }

    /** A card whose content stays folded under its title until tapped (rarely needed settings). */
    fun expander(parent: LinearLayout, title: String): LinearLayout {
        val card = card(parent, title)
        val header = card.getChildAt(0) as TextView
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        card.addView(body)
        fun label() { header.text = "${title.uppercase()}  ${if (body.visibility == View.VISIBLE) "▾" else "▸"}" }
        label()
        // The whole card toggles: the title alone is a small target.
        card.setOnClickListener {
            body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            label()
        }
        card.background = ripple(rounded(SURFACE, 16), 16)
        return body
    }

    private fun baseButton(label: String, onClick: () -> Unit) = Button(ctx).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        stateListAnimator = null
        minHeight = dp(44)
        minimumHeight = dp(44)
        setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener { onClick() }
    }

    /** Filled (accent) or outlined button; [stylePrimary] restyles it later (Start/Stop toggle). */
    fun button(label: String, primary: Boolean, onClick: () -> Unit) = baseButton(label, onClick).also { stylePrimary(it, primary) }

    fun stylePrimary(b: Button, primary: Boolean) {
        b.background = ripple(if (primary) rounded(ACCENT, 22) else rounded(SURFACE, 22, OUTLINE), 22)
        b.setTextColor(if (primary) ON_ACCENT else ACCENT)
    }

    /** Small text-only action, e.g. "New code". */
    fun textButton(label: String, onClick: () -> Unit) = baseButton(label, onClick).apply {
        textSize = 14f
        minWidth = 0
        minimumWidth = 0
        setTextColor(ACCENT)
        background = ripple(rounded(Color.TRANSPARENT, 18), 18)
    }

    /** Two-choice segmented control; [selectSegment] marks one (or none, -1). */
    fun segmented(labels: List<String>, onClick: (Int) -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        labels.forEachIndexed { i, label ->
            // One line, shrinking the text if a label doesn't fit (large font settings).
            val b = baseButton(label) { onClick(i) }.apply {
                maxLines = 1
                setAutoSizeTextTypeUniformWithConfiguration(11, 15, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }
            addView(b, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(8)
            })
        }
        selectSegment(this, -1)
    }

    fun selectSegment(group: LinearLayout, selected: Int) {
        for (i in 0 until group.childCount) {
            val b = group.getChildAt(i) as Button
            val on = i == selected
            b.background = ripple(if (on) rounded(Color.parseColor("#1f3a5f"), 22, ACCENT) else rounded(SURFACE, 22, OUTLINE), 22)
            b.setTextColor(if (on) ACCENT else TEXT)
        }
    }

    /**
     * Edge-to-edge scrolling screen. A bar the colour of the window sits under
     * the status bar, so content scrolling up doesn't show through it.
     */
    fun screen(window: Window, content: View): View {
        val scroll = ScrollView(ctx).apply {
            clipToPadding = false
            addView(content, MATCH_PARENT, WRAP_CONTENT)
        }
        window.setDecorFitsSystemWindows(false)
        val bg = (window.decorView.background as? android.graphics.drawable.ColorDrawable)?.color ?: Color.parseColor("#121212")
        val scrim = View(ctx).apply { setBackgroundColor(bg) }
        val root = android.widget.FrameLayout(ctx).apply {
            addView(scroll, MATCH_PARENT, MATCH_PARENT)
            addView(scrim, android.widget.FrameLayout.LayoutParams(MATCH_PARENT, 0))
        }
        val pad = dp(16)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            scroll.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            scrim.layoutParams = scrim.layoutParams.apply { height = bars.top }
            insets
        }
        return root
    }

    /** Coloured status dot. */
    fun dot() = View(ctx).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(MUTED) }
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) }
    }

    fun setDot(dot: View, color: Int) = (dot.background as GradientDrawable).setColor(color)

    /** Title + switch on one row. */
    @Suppress("UseSwitchCompatOrMaterialCode") // no AndroidX in this app
    fun switchRow(label: String, onClick: () -> Unit): Pair<LinearLayout, Switch> {
        fun checkedOr(on: Int, off: Int) = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(on, off))
        val sw = Switch(ctx).apply {
            thumbTintList = checkedOr(ACCENT, MUTED)
            trackTintList = checkedOr(Color.parseColor("#4d8ab4f8"), OUTLINE)
            setOnClickListener { onClick() }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(16f).apply { text = label }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(sw)
        }
        return row to sw
    }
}
