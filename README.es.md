# NX Link

🇬🇧 [English version](README.md)

App Android (Material 3) para pasar fotos de las cámaras Samsung NX por WiFi en
Android moderno (10+), reviviendo el protocolo MobileLink de la extinta
*Samsung Smart Camera App* (2018). Interfaz en inglés y español (según el
idioma del móvil).

## Compatibilidad

**Solo probada con una Samsung NX3000.** El protocolo está clonado de la app
oficial, que cubría toda la familia Smart Camera, y NX Link implementa sus dos
variantes: la de cámaras "renovadas" (NX mini, NX3000, NX3300) y la de las
anteriores (NX300, NX30, NX2000, compactas WB con WiFi..., que usan SSDP en el
puerto 1901 y User-Agent `SEC_DSC_`). En teoría deberían funcionar; en la
práctica nadie lo ha verificado aún. Si lo pruebas con otro modelo, abre un
issue con el log de la app (botón de la toolbar) tanto si va como si no.

## Funciones

- **MobileLink** ("seleccionar en el smartphone"): galería en cuadrícula con
  miniaturas, selección múltiple y descarga a `Pictures/NX300`.
- **SelectivePush** ("seleccionar en la cámara"): la app levanta el servidor
  que la cámara espera en el puerto 18100 y recibe lo que marques en su
  pantalla.
- Un solo botón: la app pregunta a la cámara en qué modo está y actúa.
- Barra de progreso real por bytes, log de protocolo integrado (icono de la
  toolbar).

## Uso

1. Activa MobileLink en la cámara.
2. Conecta el móvil al WiFi de la cámara (`AP_SSC_...`); acepta "mantener
   conexión sin internet" si Android avisa.
3. Abre NX Link y pulsa **Buscar cámara**. Si la cámara pide confirmación,
   acéptala.

## Protocolo (ingeniería inversa de la app oficial 1.4.0)

- Descubrimiento real: `HEAD /mode/control` por TCP al **7788** de la cámara
  (la IP es el gateway DHCP). Responde con `Operation-Mode` y `Default-URL`.
  Cabeceras clave: `User-Agent: SEC_MODE_<id>`, `Access-Method : manual` y un
  `CALLBACK` donde la cámara notifica cambios de modo (servidor propio en el
  7789).
- Si `Default-URL` viene "unavailable", la URL llega por el NOTIFY del callback
  o vive en `http://<cámara>:7676/smp_2_`.
- La descripción UPnP se pide con `User-Agent: SEC_RVF_ML_<id>` (el "handshake"
  que empareja; un 401 significa pendiente/denegado).
- A partir de ahí es DLNA estándar: `ContentDirectory:1` + `Browse` paginado
  (bloques de 100 desde ObjectID `0`) y descarga HTTP del `<res>` más grande.
- SelectivePush: `HEAD /sp/control` al 8100 anunciando
  `Data-Server <móvil>:18100`; la cámara sube cada fichero como una petición
  HTTP con `Expect: 100-continue` (hay que responder `100 Continue` y cerrar
  con `200 OK Sub-ErrorCode: 0`).
- La parte que la app de 2018 no podía hacer en Android moderno:
  `ConnectivityManager.bindProcessToNetwork()` sobre la red WiFi sin internet.

## Compilar

Proyecto Gradle estándar: abrir en Android Studio y Run, o
`./gradlew assembleRelease`. Firma con `debug.keystore` (clave de desarrollo,
pass `nxlink123`) para poder actualizar siempre sobre la misma instalación.
`build.sh` es el build manual original (aapt2+javac+d8) de las v1.x, ya sin uso.

## Nota legal

Implementación original e independiente creada para interoperar con cámaras
que sus dueños ya poseen, algo expresamente permitido en la UE (Directiva
2009/24/CE, art. 6). No contiene código ni recursos de Samsung y no está
afiliada ni respaldada por Samsung.
