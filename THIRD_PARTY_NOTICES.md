# Third-party notices

ADOS Android GCS is licensed under GPL-3.0-only (see `LICENSE`). It bundles or
links the third-party components below under their own licences. Library
versions are pinned in `gradle/libs.versions.toml`.

## Bundled assets

| Component | Version | Licence | Location | Upstream |
|---|---|---|---|---|
| Inter (Regular, Medium, SemiBold) | 4.1 | SIL Open Font License 1.1 | `core/designsystem/src/main/res/font/inter_*.ttf`; licence `core/designsystem/src/main/assets/licenses/inter-OFL.txt` | https://github.com/rsms/inter |
| JetBrains Mono (Regular, Medium, SemiBold) | 2.304 | SIL Open Font License 1.1 | `core/designsystem/src/main/res/font/jetbrains_mono_*.ttf`; licence `core/designsystem/src/main/assets/licenses/jetbrains-mono-OFL.txt` | https://github.com/JetBrains/JetBrainsMono |
| Space Grotesk (Regular, Medium, SemiBold) | 2.0.0 | SIL Open Font License 1.1 | `core/designsystem/src/main/res/font/space_grotesk_*.ttf`; licence `core/designsystem/src/main/assets/licenses/space-grotesk-OFL.txt` | https://github.com/floriankarsten/space-grotesk |

The Space Grotesk SemiBold file is a static instance (weight 600) of the
upstream variable font `SpaceGrotesk[wght].ttf` from the 2.0.0 release, made
with `fontTools varLib.instancer`; upstream publishes no static SemiBold. The
licence declares no Reserved Font Name.

## MAVLink message definitions

| Component | Revision | Licence | Upstream |
|---|---|---|---|
| MAVLink XML message definitions (`minimal`, `standard`, `common`, `ardupilotmega` and their includes) | `20ed9b2760cffec2ac9becb9b8287cb2456d7ee7` | MIT for generator output and message definitions, per the exception in the upstream `COPYING` at that revision | https://github.com/mavlink/mavlink |

The XML files and the upstream `COPYING` are vendored in
`core/mavlink/definitions/`, unchanged except that the per-dialect contact
comment lines at the top of some files are removed. The Kotlin codecs in
`:core:mavlink` are generated at build time from these definitions.

## Libraries

| Library | Licence | Upstream |
|---|---|---|
| WebRTC Android SDK (`io.github.webrtc-sdk:android`) | BSD-3-Clause (WebRTC), MIT (packaging) | https://github.com/webrtc-sdk/android |
| MapLibre Native for Android (`org.maplibre.gl:android-sdk`) | BSD-2-Clause | https://github.com/maplibre/maplibre-native |
| usb-serial-for-android (`com.github.mik3y:usb-serial-for-android`) | MIT | https://github.com/mik3y/usb-serial-for-android |
| OkHttp, Okio | Apache-2.0 | https://github.com/square/okhttp |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | Apache-2.0 | https://github.com/JetBrains/kotlin |
| AndroidX (Compose, Material 3, Navigation 3, Lifecycle, Room, DataStore, WebKit, Hilt integration) | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| Dagger / Hilt | Apache-2.0 | https://github.com/google/dagger |
| HiveMQ MQTT Client | Apache-2.0 | https://github.com/hivemq/hivemq-mqtt-client |
| Convex Android client (`dev.convex:android-convexmobile`) | Apache-2.0 | https://github.com/get-convex/convex-mobile |

## Phone radio native components

The phone-radio receiver builds the vendored sources below from source with
the NDK. Each tree keeps its upstream licence file and file headers.

| Component | Version | Licence | Location | Upstream |
|---|---|---|---|---|
| devourer (userspace Realtek USB radio driver; `src/` and `hal/` subset: the 11ax Kestrel firmware blobs and vendor baseband/RF trees, which this build does not compile, are omitted) | commit `1c8ae577ea2ea1aaee6d8c56c14a20883f109e4f` | GPL-2.0-only | `radio/usb/src/main/cpp/vendor/devourer/` (licence `LICENSE`) | https://github.com/OpenIPC/devourer |
| libusb | 1.0.30 (release tarball `libusb-1.0.30.tar.bz2`, SHA-256 `fea36f34f9156400209595e300840767ab1a385ede1dc7ee893015aea9c6dbaf`; `libusb/` sources and `android/config.h`) | LGPL-2.1-or-later | `radio/usb/src/main/cpp/vendor/libusb/` (licence `COPYING`) | https://github.com/libusb/libusb |
| wfb-ng (receive core: `rx`, `wifibroadcast`, `zfex`, `radiotap` sources) | commit `af6ba85eface27279709477077d3362c69bb2576` | GPL-3.0-only | `radio/rx/src/main/cpp/vendor/wfb-ng/` (licence `LICENSE.txt`) | https://github.com/svpcom/wfb-ng |
| libsodium | 1.0.22 (release tarball `libsodium-1.0.22.tar.gz`, SHA-256 `adbdd8f16149e81ac6078a03aca6fc03b592b89ef7b5ed83841c086191be3349`; `src/libsodium/`) | ISC | `radio/rx/src/main/cpp/vendor/libsodium/` (licence `LICENSE`) | https://github.com/jedisct1/libsodium |

The GPL-2.0-only driver and libusb are linked only into `libados_usbradio.so`,
which is loaded solely by the USB radio service running in its own process
(`:radio`). The ADOS bridge around it (`radio/usb/src/main/cpp/usbradio_shim.cpp`
and the `:radio:usb` sources) is GPL-2.0-or-later. The main process talks to
that service only over Binder and a pipe of frame records.
