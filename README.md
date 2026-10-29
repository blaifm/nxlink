# NX Link

App Android (Material 3) para pasar fotos de las camaras Samsung NX por WiFi en
Android moderno (10+), reviviendo el protocolo MobileLink de la extinta
*Samsung Smart Camera App* (2018). Probada con una **NX3000**; deberia funcionar
con la familia Smart Camera (NX300, NX mini, WB...).

## Funciones

- **MobileLink** ("seleccionar en el smartphone"): galeria en cuadricula con
  miniaturas, seleccion multiple y descarga a `Pictures/NX300`.
- **SelectivePush** ("seleccionar en la camara"): la app levanta el servidor que
  la camara espera en el puerto 18100 y recibe lo que marques en su pantalla.
- Un solo boton: la app pregunta a la camara en que modo esta y actua.
- Barra de progreso real por bytes, log de protocolo integrado (icono de la toolbar).

## Uso

1. Activa MobileLink en la camara.
2. Conecta el movil al WiFi de la camara (`AP_SSC_...`); acepta "mantener
   conexion sin internet" si Android avisa.
3. Abre NX Link y pulsa **Buscar camara**. Si la camara pide confirmacion, aceptala.

## Protocolo (ingenieria inversa de la app oficial 1.4.0)

- Descubrimiento real: `HEAD /mode/control` por TCP al **7788** de la camara
  (la IP es el gateway DHCP). Responde con `Operation-Mode` y `Default-URL`.
  Cabeceras clave: `User-Agent: SEC_MODE_<id>`, `Access-Method : manual` y un
  `CALLBACK` donde la camara notifica cambios de modo (servidor propio en el 7789).
- Si `Default-URL` viene "unavailable", la URL llega por el NOTIFY del callback
  o vive en `http://<camara>:7676/smp_2_`.
- La descripcion UPnP se pide con `User-Agent: SEC_RVF_ML_<id>` (el "handshake"
  que empareja; un 401 significa pendiente/denegado).
- A partir de ahi es DLNA estandar: `ContentDirectory:1` + `Browse` paginado
  (bloques de 100 desde ObjectID `0`) y descarga HTTP del `<res>` mas grande.
- SelectivePush: `HEAD /sp/control` al 8100 anunciando `Data-Server <movil>:18100`;
  la camara sube cada fichero como una peticion HTTP con `Expect: 100-continue`
  (hay que responder `100 Continue` y cerrar con `200 OK Sub-ErrorCode: 0`).
- La parte que la app de 2018 no podia hacer en Android moderno:
  `ConnectivityManager.bindProcessToNetwork()` sobre la red WiFi sin internet.

## Compilar

Proyecto Gradle estandar: abrir en Android Studio y Run, o
`./gradlew assembleRelease`. Firma con `debug.keystore` (clave de desarrollo,
pass `nxlink123`) para poder actualizar siempre sobre la misma instalacion.
`build.sh` es el build manual original (aapt2+javac+d8) de las v1.x, ya sin uso.
