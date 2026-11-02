# Subo (expresdm)

El acceso con Google está implementado mediante Credential Manager y
verificación en el servidor. Su activación requiere configurar los clientes
OAuth; consulta [GOOGLE_LOGIN.md](../GOOGLE_LOGIN.md). Mientras estén pendientes,
la app permite seguir entrando con correo y contraseña.

## Destino en el mapa y administración

Toca un punto libre del mapa para elegir el destino. Con tu ubicación activa,
la hoja muestra viajes con un micro, dos micros con transbordo y los tramos a
pie, con instrucciones numeradas, pasaje total y duración estimada. Al tocar
una opción se dibuja el viaje. Sin ubicación se conserva el destino y se pide
activarla; el cálculo aparece al recibir la posición. La selección de bajada
del aviso de llegada sigue teniendo prioridad cuando está activa.

La [web de administración](../admin-web/README.md) gestiona rutas y datos del
micro, incluidas fotos, paraderos, recorrido, estado y sentido de circulación.
Requiere la migración y función Supabase actualizadas. El catálogo recibido se
guarda en el teléfono para consultarlo después sin conexión.

App Android de transporte público para Trujillo, La Libertad.

Abre directamente en un mapa con la ubicación del usuario en tiempo real y las
rutas que pasan cerca. Permite buscar un lugar y ver qué micros pasan por ahí,
trazar cualquier ruta en el mismo mapa, avisar de lo que pasa en la calle con
alertas, y grabar rutas nuevas yendo en el micro. Un asistente con IA, abierto
desde una burbuja sobre el mapa, recomienda qué micro tomar.

Hay tres pestañas: **Rutas** (el mapa), **Alertas** y **Perfil**.

## Mapas sin API key

Los mapas usan **OpenStreetMap** a través de la librería [osmdroid](https://github.com/osmdroid/osmdroid).

Es software libre: no requiere clave de API, ni cuenta de Google Cloud, ni
tarjeta de crédito, ni tiene cuota de uso facturable. Lo único que exige el
servidor de teselas es que la app se identifique con un *user agent* propio,
cosa que se configura en [`ExpresdmApp.kt`](app/src/main/java/com/peru/expresdm/ExpresdmApp.kt).

La ubicación del usuario se obtiene del `LocationManager` del sistema
operativo, así que la app tampoco depende de Google Play Services.

## Inicio: el mapa

[`RoutesFragment`](app/src/main/java/com/peru/expresdm/ui/routes/RoutesFragment.kt)
es la primera pantalla:

- **El mapa ocupa toda la pantalla** y muestra al usuario con un punto azul. El
  punto no desaparece nunca, tampoco con una ruta elegida.
- **El mapa es libre**: se puede acercar hasta el nivel 21 y mover con los dedos.
  En cuanto el usuario lo toca, la app deja de seguirle y de reencuadrar; el
  botón de ubicación lo devuelve a su posición.
- **Encima del mapa**, el buscador de lugares y una hoja deslizable con las
  **rutas cercanas**: las que tienen una parada a menos de 1,5 km, ordenadas por
  distancia y con cuánto se tarda a pie ("350 m · 5 min a pie · Av. España").
  Se ven de 5 en 5: si hay más, una flecha enseña las 5 siguientes ("6–10 de 14")
  y otra vuelve a las anteriores.
- Si ninguna ruta pasa a menos de 1,5 km, se muestran igualmente las 3 más
  próximas y la hoja lo advierte. Sin permiso de ubicación se muestran todas,
  con un botón para activarla.
- Las **alertas** vigentes aparecen como marcadores de colores. Al tocar una se
  ve qué pasa y se puede confirmar que *sigue ahí* o marcar que *ya no está*.
- La **burbuja del asistente** muestra su nombre la primera vez y luego se
  encoge para no tapar el mapa.

La cercanía se calcula en [`NearbyRoutes`](app/src/main/java/com/peru/expresdm/data/NearbyRoutes.kt)
midiendo la distancia a la **parada** más cercana, no al trazado: la gente sube
en las paradas, y además los trazados del catálogo son aproximados. La lista
solo se recalcula cuando el usuario se ha movido más de 25 m, para que no salte
con cada lectura del GPS.

El centro útil del mapa se desplaza a la franja que no tapan el buscador ni la
hoja (`MapView.setMapCenterOffset`), así que tanto el punto del usuario como los
encuadres quedan a la vista.

## Buscar un lugar y ver qué micros pasan

El buscador no busca rutas por su nombre: busca **lugares del mapa**.

1. El usuario escribe "Real Plaza", "UPN" u "Hospital Belén" y pulsa buscar.
2. La hoja muestra los lugares encontrados, del más cercano al más lejano, y el
   mapa los marca. Las paradas del catálogo que coinciden salen primero y
   funcionan sin conexión.
3. Al elegir un lugar, la hoja pasa a **"Pasan por aquí"**: las rutas con una
   parada a menos de 600 m de ese sitio (o cuyo trazado grabado pasa por ahí),
   y el mapa las dibuja alrededor del lugar.

Los lugares salen de **Nominatim**, el buscador de OpenStreetMap
([`PlaceSearch`](app/src/main/java/com/peru/expresdm/data/PlaceSearch.kt)).
Es gratuito y sin clave, pero su política de uso es estricta y la app la
respeta:

- **nada de autocompletar**: solo se consulta al pulsar buscar;
- **como mucho una petición por segundo**, con las peticiones en fila;
- *user agent* propio y **caché** de lo ya buscado;
- búsqueda limitada al área de Trujillo.

El mismo servicio traduce posiciones a nombres de calle, para las alertas y las
paradas de las rutas grabadas. Para una app con muchos usuarios habría que
montar un buscador propio.

## Cómo llegar: el viaje completo, con transbordos

Al elegir un lugar en el buscador, si la app sabe dónde estás, la hoja ya no
lista solo "qué micros pasan por ahí": calcula **cómo llegar**
([`JourneyPlanner`](app/src/main/java/com/peru/expresdm/data/JourneyPlanner.kt)).

Cada opción es un viaje de principio a fin: caminar hasta el paradero, tomar el
micro, cambiar si hace falta, bajar y caminar lo que reste. La tarjeta muestra
el tiempo total, el pasaje sumado de los micros que se usan y la cadena de
tramos; al tocarla, el viaje se dibuja en el mapa con cada micro en su color y
los tramos a pie punteados.

Cómo decide:

- Busca **viajes directos** (un micro cuya parada quede a menos de 900 m del
  origen y otra a menos de 900 m del destino) y **con un transbordo**, cambiando
  en el par de paradas más cercanas entre dos líneas, si se cruzan andando en
  menos de 500 m.
- Descarta lo absurdo: subirse a un micro que aleja del destino, o tramos de
  menos de 400 m que se hacen antes andando.
- Si el destino está a menos de 1,5 km, propone **ir a pie** como una opción más.
- Ordena por tiempo total, luego por menos transbordos y luego por menos caminata.

> **Los tiempos son estimaciones.** En Trujillo los micros no publican horarios,
> así que la app reparte la duración de la ruta completa entre sus paradas y suma
> unos minutos de espera por cada micro. La pantalla lo dice en lugar de fingir
> una hora de llegada exacta.

La calidad de las respuestas depende del catálogo: con las 6 rutas de ejemplo,
cuyas paradas son aproximadas, sirve para probar la función. Con rutas reales
grabadas, empieza a servir de verdad.

## Elegir una ruta: se traza en el mismo mapa

Las rutas no tienen pantalla propia. Al tocar una en la lista (o en el mapa, o
en *Ver ruta* desde el asistente y el perfil):

- **La ruta se dibuja en el mapa por las calles por donde pasa**, con sus
  paradas numeradas; las demás rutas se ocultan.
- **Una fila de puntitos** marca por dónde caminar desde la ubicación del
  usuario hasta la parada donde sube, que queda resaltada.
- El mapa encuadra la ruta **con el usuario dentro**. Si después llega el
  trazado por calles, se reencuadra, salvo que el usuario ya haya movido el mapa.
- La hoja pasa a mostrar esa ruta: *Camina 540 m · 8 min hasta Av. España*,
  duración, pasaje, horario, alertas activas en el recorrido y paradas. Tocar
  una parada lleva el mapa hasta ella. La **✕** o el botón *atrás* vuelven a la
  lista.

Los caminos por calles salen de
[`StreetRouter`](app/src/main/java/com/peru/expresdm/data/StreetRouter.kt), que
usa el servicio OSRM público de **FOSSGIS** (`routing.openstreetmap.de`): libre,
sin clave y basado en OpenStreetMap. Sus condiciones piden un *user agent*
propio, **como mucho una petición por segundo**, nada de uso intensivo y mostrar
la atribución. Por eso las peticiones se espacian, el trazado de cada ruta se
pide una sola vez y se guarda en caché, y el camino a pie solo se recalcula
cuando el usuario se ha movido más de 25 m. Las rutas grabadas por usuarios ya
traen su trazado real y no consultan el servicio.

Sin conexión, o si el servicio falla, la ruta se dibuja con rectas entre paradas
y la hoja lo indica.

## Aviso de llegada

Con una ruta elegida, la tarjeta **Avísame al llegar** pide la bajada
**tocando el mapa**, sobre el recorrido del micro. Desde ahí la app sigue la
ubicación del usuario y, al quedar a **menos de 250 m** de ese punto, manda una
**notificación sin sonido ni vibración**: "Estás llegando a Av. España".

### Elegir la bajada en el mapa

Antes se elegía de una lista de paradas. El problema es que en Trujillo uno no
se baja "en la parada 4": se baja donde le queda la esquina. Ahora:

- La hoja se baja y una tarjeta pide tocar el mapa donde uno se baja.
- El toque no se queda donde cayó el dedo: se mueve al punto del recorrido más
  cercano ([`DropOffPicker`](app/src/main/java/com/peru/expresdm/data/DropOff.kt)),
  así que la bajada siempre cae sobre la ruta. Si el toque queda a más de
  **400 m** del recorrido, la app avisa de que por ahí no pasa ese micro en vez
  de aceptar un punto imposible.
- El punto se nombra solo: si cayó a menos de **150 m** de una parada, toma su
  nombre; si no, se le pregunta la calle a Nominatim ("Av. Jesús de Nazareth").
- Si el usuario venía de buscar un lugar, la bajada ya viene propuesta a la
  altura de ese lugar, y solo hay que moverla si no era ahí.
- Se puede tocar las veces que haga falta antes de confirmar; el botón *Avisarme
  aquí* es el que enciende el aviso.

- Funciona con la pantalla apagada y la app cerrada: lo hace
  [`ArrivalAlarmService`](app/src/main/java/com/peru/expresdm/tracking/ArrivalAlarmService.kt),
  un servicio en primer plano con una notificación fija que muestra cuánto falta
  y un botón *Cancelar*.
- Mientras está activo, una barra sobre el mapa lo recuerda.
- Las lecturas del GPS muy imprecisas no disparan el aviso
  ([`ArrivalRules`](app/src/main/java/com/peru/expresdm/data/ArrivalRules.kt)).
- Se apaga solo al avisar, al cancelarlo o a las 3 horas, para no gastar batería
  si el usuario se olvida.

El canal de la notificación se crea sin sonido. El usuario puede cambiarlo en
los ajustes de notificaciones de Android si más adelante quiere que suene.

## Perfil: quién eres y lo que llevas hecho

La pestaña **Perfil**
([`ProfileFragment`](app/src/main/java/com/peru/expresdm/ui/profile/ProfileFragment.kt))
reúne la identidad, la actividad y las rutas de siempre:

- **Identidad.** Un círculo con las iniciales del nombre
  ([`Initials`](app/src/main/java/com/peru/expresdm/ui/Initials.kt)), el nombre
  y el correo. Sin sesión iniciada, una silueta y la invitación a entrar; si la
  app se compiló sin servidor, se dice claro que todo se guarda en el teléfono.
- **Cambiar el nombre.** El lápiz abre un diálogo y guarda el nombre nuevo en el
  servidor (`POST /api/me`). Es el nombre que verán los demás en lo que publique,
  así que el servidor lo limpia antes de guardarlo (sin espacios de sobra, 40
  caracteres como mucho) y devuelve el que quedó, que es el que muestra la app.
  El token lleva el id, no el nombre: la sesión abierta sigue valiendo.
- **Tu actividad.** Tres números
  ([`ProfileStats`](app/src/main/java/com/peru/expresdm/data/ProfileStats.kt)):
  rutas grabadas, alertas propias vigentes y rutas distintas consultadas. Son
  cuentas de **lo que hay ahora**, no un historial: una alerta caduca y deja de
  sumar, y una ruta borrada deja de contar como usada. Mientras estén los tres a
  cero, en vez de tres ceros secos se explica de dónde saldrán.
- **Cerrar sesión** vive abajo, con el resto de opciones y en rojo, en lugar de
  compartir botón con *Iniciar sesión*: así no se cierra la sesión por error al
  buscar el perfil.

El historial de verdad (viajes hechos, búsquedas, alertas publicadas aunque ya
hayan caducado) todavía no existe; estos números son lo que se puede decir hoy
sin inventar.

## Favoritas automáticas

No hay botón de favorito: las favoritas salen del uso. Cada vez que el usuario
elige una ruta cuenta como un uso
([`RouteUsageStore`](app/src/main/java/com/peru/expresdm/data/RouteUsageStore.kt)),
y a partir de **2 usos** la ruta aparece en *Perfil → Rutas favoritas*,
ordenadas de la más usada a la menos, hasta 5.

## Alertas

La pestaña **Alertas** ([`ReportsFragment`](app/src/main/java/com/peru/expresdm/ui/reports/ReportsFragment.kt))
sustituye al antiguo muro de reportes. Una alerta no es un mensaje suelto: es
**algo que pasa en un punto del mapa** y que deja de importar al cabo de un rato.

- **Tipos**: tráfico, accidente, obras, micro lleno, zona peligrosa y otro. Los
  filtros de arriba agrupan la lista por tipo, con cuántas hay de cada uno.
- **Publicar**: *Reportar* abre una hoja donde se elige el tipo y, si se quiere,
  se añade un detalle. La alerta se marca **donde está el usuario** y se le pone
  el nombre de la calle.
- **Caducan solas** según su tipo
  ([`AlertRules`](app/src/main/java/com/peru/expresdm/data/RoadAlert.kt)):
  un micro lleno a la hora, el tráfico a las 2 h, un accidente a las 3 h, una
  zona peligrosa a las 6 h y unas obras al día.
- **Sigue ahí / Ya no está**: confirmar que sigue ahí alarga su vida; marcar que
  ya no está la oculta.
- **Ver en el mapa** lleva al inicio y centra el mapa en la alerta.

El detalle opcional pasa por la moderación antes de publicarse.

Con el [servidor](../backend/README.md) configurado, **las alertas son de todos**:
se piden las que hay cerca del usuario, publicar y votar escriben allí, y las
reglas de caducidad las decide el servidor. Lo último recibido queda guardado en
el teléfono, así que sin conexión la app sigue mostrando algo en lugar de una
pantalla vacía. Sin servidor (`BACKEND_URL` vacío), cada usuario ve solo las
suyas, como antes.

## Agregar una ruta grabándola

En la hoja del inicio, **Agregar ruta** graba una ruta nueva yendo en el micro:

1. Tras confirmar, la app pide la ubicación precisa (y las notificaciones) y
   empieza a grabar. Una barra sobre el mapa muestra la distancia, el tiempo y
   los puntos, y el recorrido se dibuja en directo.
2. La grabación sigue con la pantalla bloqueada: la hace
   [`RouteRecorderService`](app/src/main/java/com/peru/expresdm/tracking/RouteRecorderService.kt),
   un servicio en primer plano con notificación permanente. Los puntos se van
   guardando en disco; si Android cierra la app a mitad de camino, al volver la
   grabación sigue ahí para guardarla.
3. **Finalizar y guardar** abre un formulario
   ([`NewRouteFragment`](app/src/main/java/com/peru/expresdm/ui/newroute/NewRouteFragment.kt))
   con el recorrido en un mapa. Pide nombre, origen, destino, pasaje y horario.
4. **Las paradas se detectan solas**
   ([`StopDetector`](app/src/main/java/com/peru/expresdm/data/StopDetector.kt)):
   son los sitios donde el micro estuvo quieto **30 segundos o más**, más el
   origen y el destino. Se les pone el nombre de la calle, y el usuario puede
   corregirlos o quitar las que sobren.
5. Al guardar, la ruta entra en el catálogo, aparece en las listas y se traza en
   el mapa del inicio con su recorrido real.

Mientras graba se descartan las lecturas con más de 40 m de error. Estando
quieto se guarda un punto cada 10 s, que es lo que permite ver las paradas.

La ruta se guarda primero en el teléfono
([`UserRouteStore`](app/src/main/java/com/peru/expresdm/data/UserRouteStore.kt)),
para que no se pierda si falla la red, y se sube al servidor con el mismo id si
hay sesión iniciada. Desde ese momento la ven los demás usuarios. Sin sesión, la
ruta sigue siendo tuya y funciona igual, solo que no se comparte.

## Cuenta y servidor

La app funciona sin cuenta: ver el mapa, buscar lugares, trazar rutas y recibir
el aviso de llegada no necesitan registrarse. La cuenta hace falta para lo que
escribe en el servidor o gasta su cuota de IA: **publicar alertas, votarlas,
compartir rutas y usar el asistente**.

- *Perfil → Iniciar sesión* abre una hoja para entrar o crear la cuenta
  ([`AccountSheet`](app/src/main/java/com/peru/expresdm/ui/account/AccountSheet.kt)).
- La sesión se guarda en el teléfono
  ([`SessionStore`](app/src/main/java/com/peru/expresdm/data/net/SessionStore.kt)),
  así que no hay que entrar en cada arranque.
- Toda la comunicación pasa por
  [`ApiClient`](app/src/main/java/com/peru/expresdm/data/net/ApiClient.kt), sin
  librerías de red añadidas.

La dirección del servidor va en `BACKEND_URL`, dentro de `local.properties`
(que no se sube a git). **Si se deja vacía, la app guarda todo en el teléfono y
funciona igual que antes**: es lo que hace que el proyecto siga compilando y
usándose sin levantar nada.

| `BACKEND_URL` | Dónde corre el servidor | Qué implica |
|---|---|---|
| vacío | en ningún sitio | Todo local, como antes del servidor |
| `https://<proyecto>.supabase.co/functions/v1/server` | **Supabase** | Compartido entre todos los usuarios, por HTTPS |
| `http://10.0.2.2:54321/functions/v1/server` | Supabase local (`supabase start`) en la PC, visto desde el emulador | Para desarrollar sin tocar la nube |

El servidor ya no está dentro de este proyecto: vive en la carpeta hermana
[`../backend/`](../backend/README.md), con su propio README.

## Inteligencia artificial (Gemini)

Dos funciones usan la API de Gemini, con el modelo `gemini-3.5-flash-lite`:

- **Asistente de rutas** ([`AssistantRepository`](app/src/main/java/com/peru/expresdm/ai/AssistantRepository.kt)).
  Se abre desde la burbuja del mapa. El usuario pregunta a dónde quiere ir y
  recibe la ruta que le sirve, con pasaje y duración; *Ver ruta* cierra el chat y
  la traza en el mapa. El catálogo real se envía en cada consulta y el id que
  devuelve el modelo se valida contra el repositorio, así que la app nunca
  muestra una ruta inventada. La conversación se conserva al cerrar la burbuja.
- **Moderación de alertas** ([`ModerationService`](app/src/main/java/com/peru/expresdm/ai/ModerationService.kt)).
  Antes de publicar, el detalle pasa por dos filtros: uno local e instantáneo
  ([`ProfanityFilter`](app/src/main/java/com/peru/expresdm/ai/ProfanityFilter.kt))
  y, si hay conexión, una revisión con Gemini que detecta insultos disfrazados,
  amenazas, datos personales, publicidad y discriminación.

### La clave de API

Va en `local.properties`, que no se versiona. Copia `local.properties.example`
y rellena `GEMINI_API_KEY`. Sin clave la app compila y funciona igual: el
asistente avisa de que no está configurado y la moderación se queda en el
filtro local.

**Con el servidor configurado, la clave ya no viaja en el APK**: la app manda la
pregunta al servidor y este llama a Gemini con su propia clave. Es la forma de
publicar la app sin regalar la cuota.

> **Advertencia de seguridad.** Sin servidor (`BACKEND_URL` vacío), la clave va
> dentro del APK y es extraíble: descomprimirlo y buscar en `classes*.dex` la
> muestra en texto plano. Cualquiera que instale la app puede sacarla y gastar la
> cuota del proyecto. Ese modo sirve para desarrollar, no para publicar.

### Cuando la IA no está disponible

Si Gemini falla (sin red, cuota agotada, 503 por saturación), el asistente lo
dice con un mensaje concreto y la moderación **publica** el texto que ya haya
pasado el filtro local. Es deliberado: bloquear todas las alertas porque falló
un servicio externo dejaría la app inservible. La contrapartida es que, con
Gemini caído, un texto ofensivo que el filtro local no reconozca puede colarse.

## Cómo compilar y ejecutar

Requiere **JDK 21**. El JBR que trae Android Studio es Java 25, que Gradle 8.13
todavía no admite; si el build falla con un error que solo dice `25.0.3`, es eso.

En Android Studio: *Settings → Build, Execution, Deployment → Build Tools →
Gradle → Gradle JDK* y elegir el JDK 21 (Android Studio detecta los que estén
en `~/.jdks`).

Desde la terminal:

```bash
export JAVA_HOME="$HOME/.jdks/jdk-21.0.12.1+1"   # ajusta a tu ruta
./gradlew assembleDebug        # genera app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # APK firmado, si local.properties trae el keystore
./gradlew testDebugUnitTest    # pruebas
./gradlew lintDebug            # análisis estático
```

### Emulador en un equipo AMD

El emulador x86_64 necesita aceleración por hardware. En CPUs AMD hace falta el
*Android Emulator hypervisor driver*, que se instala una sola vez **como
administrador**, y tener la virtualización (SVM) activada en la BIOS:

```
%LOCALAPPDATA%\Android\Sdk\extras\google\Android_Emulator_Hypervisor_Driver\silent_install.bat
```

Después:

```bash
$ANDROID_HOME/emulator/emulator.exe -avd RutaNorte_API35
```

Como alternativa, un teléfono real con *Depuración USB* activada no necesita
nada de esto: `adb install app/build/outputs/apk/release/app-release.apk`.

## Estructura

Una sola `Activity` que aloja un grafo de navegación; cada pantalla es un
`Fragment`. Así la barra inferior reutiliza los destinos en lugar de apilar
pantallas, y el botón atrás se comporta como es debido.

```
app/src/main/java/com/peru/expresdm/
├── ExpresdmApp.kt          Arranque: tema, repositorios y osmdroid
├── MainActivity.kt         Host de navegación + barra inferior
├── ai/                     Integración con Gemini
│   ├── GeminiClient.kt     Llamada HTTP y tipos de error
│   ├── AssistantRepository.kt  Asistente de rutas
│   ├── ModerationService.kt    Moderación en dos capas
│   └── ProfanityFilter.kt  Filtro local, sin red ni dependencias
├── data/                   Modelo y repositorios (hoy locales)
│   ├── BusRoute.kt         Ruta, paradas y trazado grabado
│   ├── RouteRepository.kt  Catálogo: rutas de la app + grabadas
│   ├── UserRouteStore.kt   Rutas grabadas, en disco
│   ├── NearbyRoutes.kt     Rutas cerca del usuario o de un lugar
│   ├── StreetRouter.kt     Caminos por calles (OSRM)
│   ├── PlaceSearch.kt      Búsqueda de lugares y calles (Nominatim)
│   ├── RoadAlert.kt        Alertas, caducidad y votos
│   ├── AlertRepository.kt  Alertas guardadas en disco
│   ├── RecordingDraft.kt   Grabación en curso, en disco
│   ├── StopDetector.kt     Paradas a partir de una grabación
│   ├── RouteUsageStore.kt  Uso de cada ruta y favoritas automáticas
│   └── AppSettings.kt      Tema e idioma elegidos
├── tracking/
│   └── RouteRecorderService.kt  Grabación GPS en segundo plano
└── ui/
    ├── routes/             Inicio: mapa, búsqueda, ruta elegida y grabación
    ├── reports/            Alertas y hoja para publicarlas
    ├── newroute/           Formulario de la ruta grabada
    ├── assistant/          Chat del asistente (burbuja)
    ├── settings/           Tema e idioma
    └── profile/            Perfil y rutas favoritas
```

Cuando exista un backend, solo cambian los repositorios de `data/`: la UI
consume flujos y no sabe de dónde salen los datos.

## Diseño

Paleta de seis colores: **blanco, negro, azul, celeste, morado y plomo**.

| Rol | Claro | Oscuro | Uso |
|---|---|---|---|
| Primario | Azul `#2563EB` | Azul `#60A5FA` | Acciones, pestaña activa, distancia a pie |
| Secundario | Morado `#7C3AED` | Morado `#A78BFA` | Grabación de rutas, lugares buscados, asistente |
| Terciario | Celeste `#0EA5E9` | Celeste `#38BDF8` | Destacados |
| Neutro | Plomo `#64748B` | Plomo `#98A2B3` | Texto secundario, bordes |
| Superficie | Blanco `#FFFFFF` | Negro `#151A21` | Tarjetas, hoja del inicio |
| Fondo | `#F6F7F9` | Negro `#0B0E13` | Lienzo |

Los colores se declaran una vez en
[`values/colors.xml`](app/src/main/res/values/colors.xml) y se reparten en roles
del tema en [`values/themes.xml`](app/src/main/res/values/themes.xml) y
[`values-night/themes.xml`](app/src/main/res/values-night/themes.xml). Los tipos
de alerta tienen sus propios colores semánticos en
[`values/alert_colors.xml`](app/src/main/res/values/alert_colors.xml), con
variante nocturna.

En oscuro los acentos suben al tono 300: un azul `#2563EB` sobre negro no llega
al contraste mínimo legible.

## Tema e idioma

*Perfil → Ajustes* ([`SettingsFragment`](app/src/main/java/com/peru/expresdm/ui/settings/SettingsFragment.kt)):

- **Tema**: claro, oscuro o según el sistema. Se aplica con
  `AppCompatDelegate.setDefaultNightMode` y se guarda en
  [`AppSettings`](app/src/main/java/com/peru/expresdm/data/AppSettings.kt); se
  restaura al arrancar antes de crear la primera pantalla, para que la app no
  parpadee del claro al oscuro al abrirse.
- **Idioma**: español, inglés o el del teléfono. Usa
  `AppCompatDelegate.setApplicationLocales`, la misma API del ajuste "Idioma por
  aplicación" de Android 13+, así que queda sincronizado con los Ajustes del
  sistema. En Android 12 y anteriores lo recuerda AppCompat.

Las traducciones viven en [`values/strings.xml`](app/src/main/res/values/strings.xml)
(español) y [`values-en/strings.xml`](app/src/main/res/values-en/strings.xml)
(inglés). `./gradlew lintDebug` avisa si alguna cadena queda sin traducir.

El asistente y la moderación también responden en el idioma activo: la
instrucción que se envía al modelo cambia según el locale.

## Capturas sin emulador

```bash
./gradlew testDebugUnitTest -Pscreenshots   # deja los PNG en app/build/screenshots/
```

`ScreenshotTest` renderiza el inicio, las alertas, el perfil (recién instalado y
con sesión, favoritas y actividad) y los ajustes con el sistema de vistas real
de Android (Robolectric), en claro, oscuro y español.
Útil para revisar el diseño cuando no hay emulador disponible; el mapa no
aparece ahí porque osmdroid necesita red y GPU.

Va detrás de la propiedad `-Pscreenshots` porque necesita la librería nativa de
Robolectric, y Windows la bloquea en equipos con Smart App Control o WDAC
activos. Así el resto de las pruebas se ejecutan en cualquier máquina.
