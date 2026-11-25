# MixLab

App Android (Kotlin + Jetpack Compose) para mezclar dos canciones: ver su espectro, marcar dónde entra y sale cada una, aplicar efectos y hacer crossfade.

## Compilar

1. Abre la carpeta `MixLab` en **Android Studio** (Ladybug o más reciente) y deja que sincronice Gradle.
2. Conecta un móvil (Android 8.0 / API 26 o superior) o crea un emulador.
3. Pulsa **Run**. También puedes usar `./gradlew assembleDebug`.

Las versiones de Gradle, AGP, Kotlin y Compose están fijadas en `build.gradle.kts` y `app/build.gradle.kts`. Si Android Studio sugiere actualizarlas, acepta.

## Compilar en GitHub (sin instalar nada)

El repositorio incluye un workflow (`.github/workflows/build.yml`) que compila el APK en los servidores de GitHub:

1. Crea un repositorio nuevo en github.com y sube el contenido de esta carpeta (rama `main`).
2. Entra en la pestaña **Actions**. La compilación arranca sola con cada push; también puedes lanzarla con **Run workflow**.
3. Cuando termine (3-6 min), abre la ejecución y descarga **MixLab-debug-apk** desde *Artifacts* (es un zip con `app-debug.apk`).
4. Copia el APK al móvil e instálalo (hay que permitir «instalar apps de origen desconocido»).

Si la compilación falla, abre el paso «Compilar APK de depuración» y copia el error: el código nunca se ha compilado antes, así que el primer intento puede necesitar algún arreglo.

## Cómo se usa

1. **Cargar canción** en el Deck A y en el Deck B (mp3, aac, flac, ogg, wav… lo que decodifique Android).
2. El **espectrograma** (arriba) muestra la energía por frecuencia a lo largo del tiempo. La **forma de onda** (abajo) está coloreada: azul = graves, naranja = medios, cian/blanco = agudos. Los cambios de color marcan drops, breaks y estrofas.
3. Toca el espectro para saltar a un punto. Usa **Zoom +/−** y arrastra para afinar.
4. **Marcar IN**: punto donde entra esta canción cuando es la que viene. **Marcar OUT**: punto donde esta canción empieza a ceder la mezcla.
   Con «Marcas al beat» activado (por defecto), IN y OUT se ajustan solos al pulso más cercano.
5. **Crossfade ahora** lanza la transición a mano; con **Mezcla automática** se dispara sola al llegar al OUT.
6. **Exportar mezcla completa (WAV)** renderiza A → transición → B con todos los ajustes.

> El crossfader empieza en el lado A. Si pulsas play en B sin mover el crossfader no lo oirás: mueve el slider hacia B o usa "Crossfade ahora".

## BPM y rejilla de beats

Al cargar una canción se detecta su tempo y la posición de los pulsos. Cada deck muestra su BPM y dibuja la rejilla sobre el espectro: una línea tenue por beat y más gruesa cada 4 (compás).

| Control | Qué hace |
|---|---|
| **÷2 / ×2** | Corrige el tempo si sale al doble o a la mitad (87 ↔ 174) |
| **Rejilla aquí** | Alinea la rejilla para que un beat caiga justo en el cabezal (úsalo si las líneas salen desfasadas) |
| **Sync** | Beat-match: cambia la velocidad de este deck para igualar el tempo del otro (±15 %; usa el doble o la mitad si encaja mejor) y mueve su cabezal, como mucho medio beat, para que los golpes coincidan |
| **Marcas al beat** | IN y OUT se pegan al beat más cercano |

La detección asume tempo constante (música electrónica, pop, rock con metrónomo). En música con tempo variable o sin pulso claro avisará de «detección dudosa» o «no se detectó pulso».
Flujo típico: carga A y B, pulsa play en A, pulsa **Sync** en B (iguala tempo y alinea los golpes) y luego **Crossfade ahora**. Si al oírlo los golpes no coinciden, la rejilla de ese deck está desfasada: colócala con «Rejilla aquí» en un golpe claro (bombo) y vuelve a pulsar Sync.

## Crossfade

| Curva | Comportamiento |
|---|---|
| Lineal | Suma de volúmenes constante; se nota un bajón en el centro |
| Potencia constante | Volumen percibido estable (la más natural) |
| Curva S | Arranque y final suaves, cambio más rápido en el centro |
| Club | Ambas canciones suenan a tope en el tramo central |

**Bass swap**: a mitad del fundido los graves de A se cortan y los de B entran, para evitar que dos bombos choquen.

## Efectos (por deck)

EQ de 3 bandas (con KILL), filtro pasa-bajos/pasa-altos con un solo control, eco ping-pong, reverb, flanger, volumen y velocidad (la velocidad cambia también el tono, como un plato).

## Estructura

```
app/src/main/java/com/mixlab/app/
├── MainActivity.kt
├── audio/
│   ├── AudioDecoder.kt     MediaCodec → PCM estéreo 44.1 kHz
│   ├── SpectrumAnalyzer.kt FFT 2048, 64 bandas logarítmicas, colores de onda
│   ├── BpmDetector.kt      flujo espectral + autocorrelación + peine de pulsos (tempo y fase)
│   ├── Effects.kt          Biquad, eco, reverb, flanger
│   ├── Deck.kt             reproducción + cadena de efectos
│   ├── Mixer.kt            crossfader, curvas, bass swap, transiciones, auto-mix
│   ├── AudioEngine.kt      hilo de audio + AudioTrack (float)
│   ├── MixExporter.kt      render offline a WAV
│   └── Param.kt            parámetros y curvas
└── ui/
    ├── MixViewModel.kt
    ├── SpectrumView.kt     espectrograma + onda + marcas (Canvas)
    └── MixScreen.kt
```

El reproductor en vivo y el exportador usan el mismo `Mixer.render()`, así que lo que oyes es lo que se exporta.

## Limitaciones conocidas

- **No se ha compilado ni probado en un dispositivo**: se escribió sin SDK de Android disponible. Es probable que haga falta corregir algún detalle al compilar por primera vez.
- Los valores de **reverb y eco** se eligieron sin escuchar y pueden necesitar ajuste (ganancias en `Effects.kt` y `Deck.kt`).
- La reproducción **no continúa con la app en segundo plano**: haría falta un servicio en primer plano con notificación.
- Cada canción se guarda en memoria como PCM de 16 bits (~10 MB por minuto), con tope de 15 minutos.
- La velocidad cambia el tono; no hay time-stretch.
- **BPM**: el algoritmo se validó con un port a Python sobre pistas sintéticas de 72 a 174 BPM (error de tempo < 0,05 BPM, fase < 10 ms), pero **no con música real ni en el móvil**. La ambigüedad de octava (tempo al doble o a la mitad) no se puede evitar del todo: por eso existen ÷2 y ×2.
- Sync mueve el cabezal hasta medio beat, así que si el deck está sonando se oye un pequeño salto. La alineación tiene un error de unos pocos ms por la latencia entre el toque y el hilo de audio, y depende de que la rejilla de ambos decks esté bien colocada.

## Ícono

Ícono adaptativo vectorial (`res/drawable/ic_launcher_*.xml` + `res/mipmap-anydpi-v26/ic_launcher.xml`): dos discos que se solapan (deck A azul, deck B naranja) con una forma de onda encima, sobre degradado oscuro. Incluye versión monocroma para los íconos temáticos de Android 13+.

## Ideas siguientes

Time-stretch para mantener el tono, bucles, automatización de efectos por zonas, más de dos decks, servicio en primer plano.
