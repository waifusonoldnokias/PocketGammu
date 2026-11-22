# core:engine — GammuDroid's engine

Ported from GammuDroid 1.0 (`org.retro.gammulink.core`, `.core.irda`, `.usb`) into
`com.pocketgammu.engine`, `.irda` and `.usb`. Pure Java; only the `usb` package touches
Android (the USB host API).

```
Session              Gammu's state machine (GSM_InitConnection, GSM_WaitFor, dispatch)
Fbus2, Phonet        protocols (FBUS2 incl. fbusirda, Phonet for dku2/irdaphonet)
N6110, N7110, N6510  phone modules; NokiaCommon, Models (Gammu's Nokia model table)
S40Files             Series 40 filesystem 1 and 2 (N6510); S40FileOps (copy/move/rename)
GsmBitmap, Ringtone, RingtoneIO, Sms, Sckl, Midi
irda/                IrPort, Sir, IrLap (primary), IrdaStack (IrLMP, IAS, TinyTP), RawIrDevice
usb/                 UsbDrivers (detection), Pl2303, Stir4200, NokiaUsb, SerialPortDevice
```

The app reaches it only through `core:gammu`'s `PhoneEngine` / `PhoneRepository`.

## PocketGammu changes to GammuDroid's code

- `usb/SerialPortDevice` (new): FTDI / CH340 / CP210x / CDC cables through
  usb-serial-for-android; `UsbDrivers.Kind.SERIAL`. PL2303 still uses GammuDroid's driver.
- `S40Files.download1`: read until the size from the file info is reached (some firmware
  sends parts shorter than 1000 bytes); if the phone doesn't answer the checksum request but
  every byte arrived, keep the file; on a checksum mismatch keep the file with a warning
  (as `gammu --getfiles` does).
- `S40Files.list1`: also skip stale IDs (ERR_FILENOTEXIST), not only GMSTemp.
- `irda/IrLap` + `IrdaStack`: when discovery finds no phone, log what the dongle heard
  (nothing / only its own echoes / damaged frames).
- IrDA discovery nickname "PocketGammu".

Tests live in `core/gammu/src/test` (`EngineTest`) because they are written in Kotlin.

- KingSun KS-959 support was removed in PocketGammu 1.0 (GammuDroid's `usb/Ks959` driver is
  not included). SigmaTel STIr4200-family dongles are the supported IrDA option.
