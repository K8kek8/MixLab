# MixLab

App Android (Kotlin + Jetpack Compose) para mezclar canciones en pantalla horizontal, al estilo de una mesa de DJ: dos tocadiscos, ondas grandes con rejilla de beats, efectos, crossfade, y un editor para **cortar partes de una pista y pegarlas en otra** para hacer mezclas personalizadas.

## Compilar

1. Abre la carpeta `MixLab` en **Android Studio** (Ladybug o más reciente) y deja que sincronice Gradle.
2. Conecta un móvil (Android 8.0 / API 26 o superior) o crea un emulador.
3. Pulsa **Run**. También puedes usar `./gradlew assembleDebug`.

Las versiones de Gradle, AGP, Kotlin y Compose están fijadas en `build.gradle.kts` y `app/build.gradle.kts`. Si Android Studio sugiere actualizarlas, acepta.

## Compilar en GitHub (sin instalar nada)

El repositorio incluye un workflow (`.github/workflows/build.yml`) que compila el APK en los servidores de GitHub:

1. Sube el contenido de esta carpeta a tu repositorio (rama `main`).
2. Entra en la pestaña **Actions**. La compilación arranca sola con cada push; también puedes lanzarla con **Run workflow**.
3. Cuando termine (3-6 min), abre la ejecución y descarga **MixLab-debug-apk** desde *Artifacts* (es un zip con `app-debug.apk`).
4. Copia el APK al móvil e instálalo (hay que permitir «instalar apps de origen desconocido»).

Si la compilación falla, abre el paso «Compilar APK de depuración» y busca las líneas que empiezan por `e:`: ahí está el error.

## La pantalla

La app se abre siempre en horizontal y a pantalla completa.

```
┌──────────────────────────────────────────────────────────────────┐
│ 125.0 │ Título A   00:20 │  MENU  │ Título B   00:33 │ 125.0     │  BPM + PITCH, título, tiempo
│ PITCH │ ▁▂▅▇▅▂ vista general│       │ vista general ▁▂▅▇│ PITCH     │  y vista general de cada canción
├──────────────────────────────────────────────────────────────────┤
│ A ▁▂▃▅▇█▇▅▃▂▁│▁▂▃▅▇█   (onda grande, cabezal rojo fijo)           │
│ B ▁▃▅▇▅▃▂▁▂▃▅│▇█▇▅▃▂                                              │
├────┬─────────────────┬────────────┬─────────────────┬────┤
│ FX │     (disco A)   │ ♪+    ♪+   │    (disco B)    │ FX │
│ EQ │                 │ vol A  B   │                 │ EQ │
│CORTE│                │ [SYNC]     │                 │CORTE│
│MARCAS│                │            │                 │MARCAS│
├────┴─────────────────┴────────────┴─────────────────┴────┤
│ ▶  |◀  [  ]  ═════════ crossfader ═════════  [  ]  |◀  ▶  │
└──────────────────────────────────────────────────────────────────┘
```

- **Disco:** toca para play/pausa; arrástralo en círculo para avanzar o retroceder la canción (mientras arrastras el deck se silencia). Gira según la posición y el brazo se apoya al sonar.
- **Onda grande:** la silueta tenue es el **volumen** y el núcleo brillante son los **graves**. Un drop o un bombo se ven como núcleo ancho; un break, como silueta hueca. Así se distingue de un vistazo dónde cambia la canción. Las líneas blancas son los **beats** (más largas cada 4 = compás). Arrastra la onda para desplazarte; toca la etiqueta «A · 8 s» para cambiar el zoom (4, 8, 16 o 32 s visibles).
- **Vista general** (franja fina junto al título): toda la canción; toca o arrastra para saltar.
- **Colores de las marcas:** amarillo = selección para cortar, verde = IN de la transición, violeta = OUT de la transición, rojo = cabezal.
- **Centro:** `♪+` carga una canción, los faders verticales son el volumen de cada deck y **SYNC** iguala el tempo (y los golpes) del deck que entra con el que suena.
- **Abajo:** play, volver al inicio, `[` y `]` (marcan el inicio y el final de una selección en el cabezal) y el crossfader.
- **Botones laterales** de cada deck abren su panel: **FX** (eco, reverb, flanger), **EQ** (graves, medios, agudos, filtro, volumen), **CORTE** (editar la pista) y **MARCAS** (IN/OUT de la transición). Tocar **PITCH** junto al BPM abre la pestaña de tempo.
- **MENU:** curva y duración del crossfade, bass swap, mezcla automática, «Crossfade ahora» y exportar la mezcla completa.

## Cortar y pegar partes de una pista

Sirve para construir mezclas personalizadas: sacar un estribillo de una canción y meterlo en otra, repetir un compás, quitar una parte, etc.

1. **Selecciona:** lleva el cabezal al principio de la parte (arrastra la onda o toca la vista general) y pulsa **`[`**. Lleva el cabezal al final y pulsa **`]`**. La selección se ve en amarillo en las ondas. Con «Marcas al beat» activado (por defecto) se ajusta sola al beat más cercano, así que los cortes caen en el compás.
2. Abre **CORTE** y elige:
   - **Copiar**: guarda la parte en el portapapeles sin tocar la pista.
   - **Cortar**: la guarda y la quita de la pista (los dos lados se unen).
   - **Eliminar**: la quita sin guardarla.
3. **Pega:** pon el cabezal donde quieras, en cualquiera de los dos decks, abre **CORTE** y pulsa:
   - **Pegar (insertar)**: mete el fragmento y empuja el resto de la pista.
   - **Superponer**: lo mezcla encima sin mover nada (por ejemplo, una percusión sobre otra pista).
   - Si el deck está vacío, el fragmento pasa a ser su pista.
4. **Ajustar tempo al pegar** (activado por defecto): si el fragmento viene de una canción a 128 BPM y la de destino va a 120, se acelera o frena para que encaje con la rejilla (cambia también el tono).
5. **Deshacer** recupera hasta las 3 últimas ediciones de cada deck. **Guardar esta pista (WAV)** exporta la pista editada tal cual.

Las pistas editadas siguen usando los efectos, el crossfade y la exportación de la mezcla. Cada edición recalcula la onda y la rejilla de beats.

## BPM y rejilla de beats

Al cargar una canción se detecta su tempo y la posición de los pulsos. En la pestaña **PITCH** de cada deck:

| Control | Qué hace |
|---|---|
| **Velocidad** | Cambia el tempo (y el tono) ±15 % |
| **÷2 / ×2** | Corrige el tempo si sale al doble o a la mitad (87 ↔ 174) |
| **Rejilla aquí** | Alinea la rejilla para que un beat caiga justo en el cabezal (úsalo si las líneas salen desfasadas) |
| **Sync** | Beat-match: cambia la velocidad de este deck para igualar el tempo del otro (±15 %; usa el doble o la mitad si encaja mejor) y mueve su cabezal, como mucho medio beat, para que los golpes coincidan |
| **Marcas al beat** | Las marcas y selecciones se pegan al beat más cercano |

La detección asume tempo constante (música electrónica, pop, rock con metrónomo). Flujo típico: carga A y B, pulsa play en A, pulsa **SYNC** y luego **Crossfade ahora** (en MENU). Si al oírlo los golpes no coinciden, coloca la rejilla con «Rejilla aquí» en un bombo claro y vuelve a pulsar SYNC.

## Crossfade

| Curva | Comportamiento |
|---|---|
| Lineal | Suma de volúmenes constante; se nota un bajón en el centro |
| Potencia constante | Volumen percibido estable (la más natural) |
| Curva S | Arranque y final suaves, cambio más rápido en el centro |
| Club | Ambas canciones suenan a tope en el tramo central |

**Bass swap**: a mitad del fundido los graves de un deck se cortan y los del otro entran, para evitar que dos bombos choquen.
**Mezcla automática**: cuando el deck activo llega a su OUT (o al final), entra el otro desde su IN. Las marcas IN/OUT se ponen en la pestaña **MARCAS**.

> El crossfader empieza en el lado A. Si pulsas play en B sin moverlo no lo oirás: muévelo hacia B o usa «Crossfade ahora».

## Efectos (por deck)

EQ de 3 bandas (con KILL), filtro pasa-bajos/pasa-altos con un solo control, eco ping-pong, reverb, flanger, volumen y velocidad (la velocidad cambia también el tono, como un plato).

## Estructura

```
app/src/main/java/com/mixlab/app/
├── MainActivity.kt           pantalla completa, horizontal, pantalla siempre encendida
├── audio/
│   ├── AudioDecoder.kt       MediaCodec → PCM estéreo 44.1 kHz
│   ├── FFT.kt                FFT radix-2
│   ├── Waveform.kt           onda simplificada: volumen + graves (50 valores/s) y vista general
│   ├── TrackEditor.kt        cortar, copiar, insertar, superponer, estirar (con mini-fundidos en los empalmes)
│   ├── BpmDetector.kt        flujo espectral + autocorrelación + peine de pulsos (tempo y fase)
│   ├── Effects.kt            Biquad, eco, reverb, flanger
│   ├── Deck.kt               reproducción + cadena de efectos
│   ├── Mixer.kt              crossfader, curvas, bass swap, transiciones, auto-mix
│   ├── AudioEngine.kt        hilo de audio + AudioTrack (float)
│   ├── MixExporter.kt        render offline a WAV (mezcla completa o pista suelta)
│   └── Param.kt              parámetros y curvas
└── ui/
    ├── MixViewModel.kt       estado, selección, portapapeles, deshacer, sync
    ├── MixScreen.kt          disposición horizontal
    ├── DiscView.kt           tocadiscos (Canvas)
    ├── WaveViews.kt          onda grande con rejilla y vista general (Canvas)
    ├── Controls.kt           faders, botones, sliders
    ├── Panels.kt             paneles FX / EQ / PITCH / CORTE / MARCAS y MENU
    └── Theme.kt              colores y formatos de tiempo
```

El reproductor en vivo y el exportador usan el mismo `Mixer.render()`, así que lo que oyes es lo que se exporta.

## Limitaciones conocidas

- **Compila en GitHub Actions, pero no se ha probado en un dispositivo**: el audio real, el tacto de la interfaz y el rendimiento están por verificar. La parte de audio (edición de pistas, onda, BPM tras editar, motor al sustituir la pista sonando y exportación WAV) sí se probó compilada con Kotlin 2.0.21 sobre pistas sintéticas; la interfaz de Compose no se pudo compilar fuera de GitHub.
- Al arrastrar el disco o la onda el deck **se silencia** (no hay scratch audible); al soltar sigue sonando si lo estaba.
- Cada canción vive en memoria como PCM de 16 bits (~10 MB por minuto, tope de 15 minutos) y cada edición crea una copia nueva; con «deshacer» se guardan hasta 3 por deck. Con pistas muy largas el móvil puede quedarse sin memoria (la app lo avisa).
- Ajustar el tempo de un fragmento al pegarlo cambia también su tono (no hay time-stretch que lo conserve).
- Los empalmes llevan un mini-fundido de ~2 ms para evitar clics; un corte que no caiga en un beat puede notarse.
- La detección de **BPM** se validó con pistas sintéticas (error de tempo < 0,05 BPM, fase < 10 ms), pero no con música real. La ambigüedad de octava (tempo al doble o a la mitad) no se puede evitar del todo: por eso existen ÷2 y ×2.
- Sync mueve el cabezal hasta medio beat, así que si el deck está sonando se oye un pequeño salto, y depende de que la rejilla de ambos decks esté bien colocada.
- Los valores de **reverb y eco** se eligieron sin escuchar y pueden necesitar ajuste (ganancias en `Effects.kt` y `Deck.kt`).
- La reproducción **no continúa con la app en segundo plano**: haría falta un servicio en primer plano con notificación.

## Ícono

Ícono adaptativo vectorial (`res/drawable/ic_launcher_*.xml` + `res/mipmap-anydpi-v26/ic_launcher.xml`): dos discos que se solapan (deck A azul, deck B naranja) con una forma de onda encima, sobre degradado oscuro. Incluye versión monocroma para los íconos temáticos de Android 13+.

## Ideas siguientes

Scratch audible al arrastrar el disco, time-stretch para mantener el tono, bucles, hot cues, automatización de efectos por zonas, servicio en primer plano.
