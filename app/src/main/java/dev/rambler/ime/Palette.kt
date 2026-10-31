package dev.rambler.ime

import android.content.Context
import android.content.res.Configuration

/** Material 3 style colours for the keyboard, light and dark. */
class Palette(val dark: Boolean) {
    val background = if (dark) 0xFF1B1C20.toInt() else 0xFFEAEFF8.toInt()
    val key = if (dark) 0xFF2C2E34.toInt() else 0xFFF8FAFE.toInt()
    val functionKey = if (dark) 0xFF3B3E47.toInt() else 0xFFD8E1F2.toInt()
    val text = if (dark) 0xFFE3E3E8.toInt() else 0xFF1B1B1F.toInt()
    val primary = if (dark) 0xFFA8C7FA.toInt() else 0xFF0B57D0.toInt()
    val onPrimary = if (dark) 0xFF062E6F.toInt() else 0xFFFFFFFF.toInt()
    val pressed = if (dark) 0x33FFFFFF else 0x22000000
    val chip = if (dark) 0xFF2C2E34.toInt() else 0xFFFFFFFF.toInt()
    val popup = if (dark) 0xFF3B3E47.toInt() else 0xFFFFFFFF.toInt()
    val barGradient = if (dark)
        intArrayOf(0xFF1F3A66.toInt(), 0xFF3563AE.toInt(), 0xFF1F3A66.toInt())
    else
        intArrayOf(0xFFD3E3FD.toInt(), 0xFF9CC2FB.toInt(), 0xFFD3E3FD.toInt())
    val barButton = if (dark) 0xFF2C2E34.toInt() else 0xFFF8FAFE.toInt()

    companion object {
        fun forContext(c: Context) = Palette(
            (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        )
    }
}
