# PocketGammu – developer notes

Version 1.0. A clean rebuild of GammuDroid: a Material 3 Expressive Android app for tinkering with
classic Nokia phones (DCT3, DCT4 and Series 40) over USB OTG cables and IrDA dongles.

| | |
|---|---|
| Version | 1.0 (`versionCode 10`) |
| Min SDK | 26 (Android 8.0) · target 36 · compile 37 |
| Code | Kotlin + Java, no native code (runs on any ABI) |
| Toolchain | AGP 9.5.1 · Gradle 9.6.0 · Kotlin 2.3.10 · JDK 17+ |
| Engine | GammuDroid's Java port of Gammu 1.45 (`core/engine`) |
| UI | Jetpack Compose, Material 3 `1.5.0-rc01` (Expressive APIs) |

## Building

Open the folder in Android Studio and let it sync, then run the `app` configuration.
From a terminal:

```
./gradlew :app:assembleDebug
./gradlew :core:nokia:test :core:gammu:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:assembleRelease      # minified, signed with the debug key for sideloading
```

`AppSmokeTest` (Robolectric) launches the real app and opens every screen, panel and
sheet, including drawing on the logo canvas. `EngineTest` runs the engine against
simulated phones: a 3510i on FBUS (auto-detection, file listing, downloads including
firmware with short parts and no checksum), the 6110-family infrared wake-up and an IrDA
phone behind a fake dongle. Run both before each release.

README screenshots come from `ReadmeShots` (Robolectric, against a simulated 3510i). They are
left out of normal test runs; refresh them with
`./gradlew :app:testDebugUnitTest --tests '*ReadmeShots*' -PreadmeShots`, then copy
`app/build/readme-shots/*.png` (scaled to 540 px wide) into `docs/screenshots/`.

Generated colour schemes can be rebuilt with `cd tools && npm i && npm run colors`.

## Modules

```
app/                  screens, navigation, ViewModels, SMS sender
  ui/home             GammuDroid's connection flow: status card (model, firmware, module,
                      cable; Read network & IMEI), device list, connection card
                      (types filtered to the device, phone family, cable power), tips, log
  ui/logo             logo editor (canvas, tools, layers, text, image import; operator,
                      caller, startup and 72×28 picture messages)
  ui/logo/model       pure-Kotlin document model + painter (unit-tested)
  ui/ringtone         ringtone editor (note track, keyboard, preview player)
  ui/files            Series 40 file manager
  ui/settings         theme, colour scheme, haptics
  ui/common           shared send sheet (cable or //SCKL SMS)
core/engine           GammuDroid 1.0's engine (Java): Session, FBUS2, Phonet, N6110 / N7110 /
                      N6510, Series 40 filesystem, IrDA stack (IrLAP, IrLMP, IAS, TinyTP) and
                      USB drivers (PL2303, STIr4200, Nokia USB) — see core/engine/README.md
core/gammu            PhoneRepository (one engine thread, byte-level log), PhoneEngine adapter,
                      connection models, USB detection
core/model            settings models (ThemeMode, ColorPalette …) — pure Kotlin
core/nokia            Nokia formats — pure Kotlin, no Android:
                      OTA bitmaps, operator/caller logos, Smart Messaging ringtones,
                      RTTTL, MIDI → melody, NRT, //SCKL, image dithering, BMP export
core/designsystem     theme (5 palettes × light/dark), expressive shapes, components,
                      haptics gate, background blur
core/data             DataStore settings
```

Dependencies only point downwards: `app → core:*`, `gammu → engine, nokia`,
`designsystem/data → model`. No feature reaches into another feature.

## Conventions

- **Expressive APIs live in `core/designsystem` only.** Screens use `PgButton`,
  `ConnectedToggleGroup`, `ToolToggle`, `ShapeIcon`, `PgDialog`, `PgBottomSheet` and so
  on. If an experimental Material 3 signature changes, only that module needs fixing.
- **Shapes have meaning.** `PgShapeSet` maps states and file types to Material shapes
  (connected = Sunny, connecting = rotating 9-sided cookie, error = SoftBurst …).
- **Haptics go through `LocalPgHaptics`**, so the Settings switch disables all of them.
- **Pop-ups blur what's behind them.** Sheets and menus call `RegisterOverlay()`,
  dialogs use the platform's cross-window blur. Needs Android 12+; older versions fall
  back to a plain scrim.
- **One thread for the engine.** All phone calls go through `PhoneRepository`. Like
  GammuDroid, an error is reported and the session stays connected.
- **Colour roles come in pairs.** Text and icons use the `on…` role of the colour they sit
  on (`onSecondaryContainer` on `secondaryContainer`, `onSurface` on surfaces). The Fidelity
  schemes make containers mid-tone, so an `on…Container` colour on a surface can vanish.
- **System bar icons follow the app's theme**, not the phone's (`PocketGammuTheme`).
- **Vector art from PNGs:** traced with potrace; Android resource strings are capped at
  32 767 characters, so long path data is split into several `<path>` elements, keeping
  each hole with its outline (even-odd fill).
- Versioning: bump `versionName` and `versionCode` with each release.

## Status of 1.0

| Area | State |
|---|---|
| Theme, 6 palettes (System, Teto, Miku, Neru, Gumi, Rei), dark mode, haptics, blur | Done; every role pair ≥ 4.5:1 (text) / 3:1 (icons) |
| Vector headline (tinted to the theme) and adaptive app icon with themed (monochrome) layer | Done |
| Home: GammuDroid connection flow, shareable byte-level log | Done |
| Logo editor (tools, layers, text, undo/redo, zoom, Save as BMP) | Done |
| Text: outline (1–3 px), width, letter spacing, inverted, imported .ttf/.otf fonts | Done |
| Image import: touch move / pinch scale / twist rotate / side-handle width, 5 dither modes | Done |
| Ringtone editor: import TXT/MID/NRT, export RTTTL, keyboard entry, sine preview | Done |
| //SCKL SMS sending (logos + ringtones, concatenated) | Done |
| Picture messages (72×28 + text; SMS port 158A, or cable into picture slots on N6110-class phones) | Done |
| Engine: fbus, dku5, fbuspl2303, dlr3, dku2, irdaphonet, fbusirda | Done (GammuDroid's, field-tested there) |
| Serial cables: PL2303 (GammuDroid driver), FTDI / CH340 / CP210x / CDC (usb-serial-for-android) | Done |
| File manager (browse, upload, download, delete, new folder) | Done; 3510i confirmed on 0.4.0 |
| IrDA: STIr4200 | Works (6150 fbusirda confirmed on 0.3.3) |
| IrDA: KingSun KS-959 | Removed in 1.0 (driver never worked on Android) |
| AT connections (`at`, `irdaat`, `dku2at`), M-Bus | Not in GammuDroid's engine; dropped in 0.4 |
| SigmaTel STIr4210/4220/4116 | Not supported (need firmware patch upload) |

## Licence

The engine is a port of Gammu (GNU GPL version 2), so PocketGammu is released under the
GPL-2.0 as well. See LICENSE.
