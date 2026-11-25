package com.mixlab.app.audio

import kotlin.math.roundToInt

/** Parámetros ajustables de cada deck. La UI genera los sliders a partir de esta lista. */
enum class Param(
    val label: String,
    val min: Float,
    val max: Float,
    val def: Float,
    val fmt: (Float) -> String,
) {
    VOLUME("Volumen", 0f, 1.2f, 1f, { "${(it * 100).roundToInt()}%" }),
    SPEED("Velocidad (cambia el tono)", 0.85f, 1.15f, 1f, { "%.2fx".format(it) }),

    EQ_LOW("Graves", -30f, 10f, 0f, { if (it <= -29f) "KILL" else "%+.0f dB".format(it) }),
    EQ_MID("Medios", -30f, 10f, 0f, { if (it <= -29f) "KILL" else "%+.0f dB".format(it) }),
    EQ_HIGH("Agudos", -30f, 10f, 0f, { if (it <= -29f) "KILL" else "%+.0f dB".format(it) }),

    FILTER("Filtro  (← pasa-bajos · pasa-altos →)", -1f, 1f, 0f, {
        when {
            it < -0.02f -> "LP ${(-it * 100).roundToInt()}%"
            it > 0.02f -> "HP ${(it * 100).roundToInt()}%"
            else -> "off"
        }
    }),

    ECHO_MIX("Eco · cantidad", 0f, 1f, 0f, { "${(it * 100).roundToInt()}%" }),
    ECHO_TIME("Eco · tiempo", 80f, 900f, 375f, { "${it.roundToInt()} ms" }),
    ECHO_FEEDBACK("Eco · repeticiones", 0f, 0.9f, 0.4f, { "${(it * 100).roundToInt()}%" }),

    REVERB_MIX("Reverb · cantidad", 0f, 1f, 0f, { "${(it * 100).roundToInt()}%" }),
    REVERB_SIZE("Reverb · tamaño", 0f, 1f, 0.6f, { "${(it * 100).roundToInt()}%" }),

    FLANGER_MIX("Flanger", 0f, 1f, 0f, { "${(it * 100).roundToInt()}%" }),
}

/** Curvas de crossfade. p = 0 → solo deck A, p = 1 → solo deck B. */
enum class FadeCurve(val label: String) {
    LINEAR("Lineal"),
    EQUAL_POWER("Potencia constante"),
    S_CURVE("Curva S"),
    CLUB("Club (ambos a tope)"),
}
