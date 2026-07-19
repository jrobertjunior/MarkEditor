package com.creepybubble.markeditor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Uma paleta de cores completa do app. */
data class AppPalette(
    val name: String,
    val bg: Color,
    val surface: Color,
    val text: Color,
    val red: Color,
    val orange: Color,
    val yellow: Color,
    val aqua: Color,
    val blue: Color,
    val gray: Color,
    val purple: Color,
    val lilac: Color
)

val gruvboxDarkPalette = AppPalette(
    name = "Gruvbox Escuro",
    bg = Color(0xFF282828), surface = Color(0xFF3C3836), text = Color(0xFFEBDBB2),
    red = Color(0xFFCC241D), orange = Color(0xFFD65D0E), yellow = Color(0xFFD79921),
    aqua = Color(0xFF689D6A), blue = Color(0xFF458588), gray = Color(0xFF928374),
    purple = Color(0xFFB16286), lilac = Color(0xFFD3869B)
)

val gruvboxLightPalette = AppPalette(
    name = "Gruvbox Claro",
    bg = Color(0xFFFBF1C7), surface = Color(0xFFEBDBB2), text = Color(0xFF3C3836),
    red = Color(0xFF9D0006), orange = Color(0xFFAF3A03), yellow = Color(0xFFB57614),
    aqua = Color(0xFF427B58), blue = Color(0xFF076678), gray = Color(0xFF7C6F64),
    purple = Color(0xFF8F3F71), lilac = Color(0xFFB16286)
)

val nordPalette = AppPalette(
    name = "Noturno",
    bg = Color(0xFF2E3440), surface = Color(0xFF3B4252), text = Color(0xFFD8DEE9),
    red = Color(0xFFBF616A), orange = Color(0xFFD08770), yellow = Color(0xFFEBCB8B),
    aqua = Color(0xFFA3BE8C), blue = Color(0xFF81A1C1), gray = Color(0xFF7B88A1),
    purple = Color(0xFFB48EAD), lilac = Color(0xFFB48EAD)
)

val appPalettes = listOf(gruvboxDarkPalette, gruvboxLightPalette, nordPalette)

// Cores "vivas": são estados, então trocar de paleta recompoe a UI que as lê.
var gruvboxBg by mutableStateOf(gruvboxDarkPalette.bg)
    private set
var gruvboxSurface by mutableStateOf(gruvboxDarkPalette.surface)
    private set
var gruvboxText by mutableStateOf(gruvboxDarkPalette.text)
    private set
var gruvboxRed by mutableStateOf(gruvboxDarkPalette.red)
    private set
var gruvboxOrange by mutableStateOf(gruvboxDarkPalette.orange)
    private set
var gruvboxYellow by mutableStateOf(gruvboxDarkPalette.yellow)
    private set
var gruvboxAqua by mutableStateOf(gruvboxDarkPalette.aqua)
    private set
var gruvboxBlue by mutableStateOf(gruvboxDarkPalette.blue)
    private set
var gruvboxGray by mutableStateOf(gruvboxDarkPalette.gray)
    private set
var gruvboxPurple by mutableStateOf(gruvboxDarkPalette.purple)
    private set
var gruvboxLilac by mutableStateOf(gruvboxDarkPalette.lilac)
    private set

var currentPaletteName by mutableStateOf(gruvboxDarkPalette.name)
    private set

/** Troca todas as cores do app para a paleta escolhida. */
fun applyPalette(p: AppPalette) {
    gruvboxBg = p.bg
    gruvboxSurface = p.surface
    gruvboxText = p.text
    gruvboxRed = p.red
    gruvboxOrange = p.orange
    gruvboxYellow = p.yellow
    gruvboxAqua = p.aqua
    gruvboxBlue = p.blue
    gruvboxGray = p.gray
    gruvboxPurple = p.purple
    gruvboxLilac = p.lilac
    currentPaletteName = p.name
}
