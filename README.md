# NX Link

🇪🇸 [Versión en español](README.es.md)

Android app (Material 3) to transfer photos from Samsung NX cameras over WiFi on
modern Android (10+), reviving the MobileLink protocol of the discontinued
*Samsung Smart Camera App* (2018). UI in English and Spanish (follows the
device language).

## Compatibility

**Only tested with a Samsung NX3000.** The protocol was cloned from the official
app, which covered the whole Smart Camera family, and NX Link implements both of
its dialects: the "renewal" cameras (NX mini, NX3000, NX3300) and the older ones
(NX300, NX30, NX2000, WiFi-enabled WB compacts..., which use SSDP on port 1901
and the `SEC_DSC_` User-Agent). In theory they should work; in practice nobody
has verified it yet. If you try another model, please open an issue with the
app log (toolbar button) whether it works or not.

## Features

- **MobileLink** ("select on smartphone"): thumbnail grid gallery,
  multi-select, downloads to `Pictures/NX300`.
- **SelectivePush** ("select on camera"): the app runs the server the camera
  expects on port 18100 and receives whatever you pick on its screen.
- One button: the app asks the camera which mode it is in and acts accordingly.
- Real byte-based progress bar, built-in protocol log (toolbar icon).

## Usage

1. Enable MobileLink on the camera.
2. Join the camera's WiFi from the phone (`AP_SSC_...`); accept "keep
   connection without internet" if Android asks.
3. Open NX Link and tap **Search camera**. Accept the confirmation on the
   camera if it asks.

## Protocol (reverse-engineered from the official app 1.4.0)

- Actual discovery: `HEAD /mode/control` over TCP to the camera's port **7788**
  (the camera IP is the DHCP gateway). It answers with `Operation-Mode` and
  `Default-URL`. Key headers: `User-Agent: SEC_MODE_<id>`,
  `Access-Method : manual` and a `CALLBACK` where the camera notifies mode
  changes (the app runs its own server on 7789).
- If `Default-URL` comes back "unavailable", the URL arrives via the callback
  NOTIFY or lives at `http://<camera>:7676/smp_2_`.
- The UPnP description is fetched with `User-Agent: SEC_RVF_ML_<id>` (the
  pairing "handshake"; a 401 means pending/denied).
- From there it is standard DLNA: `ContentDirectory:1` + paginated `Browse`
  (blocks of 100 from ObjectID `0`) and HTTP download of the largest `<res>`.
- SelectivePush: `HEAD /sp/control` to port 8100 announcing
  `Data-Server <phone>:18100`; the camera uploads each file as an HTTP request
  with `Expect: 100-continue` (reply `100 Continue`, then close with
  `200 OK Sub-ErrorCode: 0`).
- The part the 2018 app could not do on modern Android:
  `ConnectivityManager.bindProcessToNetwork()` on the internet-less WiFi.

## Building

Standard Gradle project: open in Android Studio and Run, or
`./gradlew assembleRelease`. Signed with `debug.keystore` (development key,
pass `nxlink123`) so any build updates over the same installed app.
`build.sh` is the original manual build (aapt2+javac+d8) from v1.x, now unused.

## Legal note

This is an original, independently written implementation created for
interoperability with cameras their owners already have, which is expressly
permitted in the EU (Directive 2009/24/EC, art. 6). It contains no Samsung
code or assets and is not affiliated with or endorsed by Samsung.

## Credits & related work

- [samsung-nx-hacks](https://github.com/ge0rg/samsung-nx-hacks) and the
  [Hacking the NX300](https://op-co.de/blog/posts/hacking_the_nx300/) series by
  Georg Lukas — the reference documentation of the Samsung NX ecosystem, great
  prior work and camera-side counterpart to this app.
- The MobileLink/SelectivePush protocol details in NX Link were obtained by
  decompiling the official Samsung Smart Camera App 1.4.0 for interoperability.
