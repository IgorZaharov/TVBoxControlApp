# Tiny TVBox Remote Control

Tiny TVBox Remote Control (on the home screen: "TVBox Remote") turns a phone into a remote control for Android TV and Google TV boxes. It has a D-pad, volume, power, home, back, and settings keys, a keyboard that types on the TV, and a gyro mouse. The main screen shows only the large remote buttons. The menu holds the other functions, Help, and About. A landscape controller mode works as a game controller, and a home screen widget gives a small remote without opening the app.

## Apps

| Folder | App | Platform |
|---|---|---|
| `android/app` | Phone remote | Android 9 (API 28) or later |
| `ios` | Phone remote | iOS 16 or later |
| `android/receiver` | Box receiver, for the mouse over Wi-Fi | Android TV, Android 7 (API 24) or later |

## How the phone connects

| Function | Wi-Fi (Android and iPhone) | Bluetooth (Android only) |
|---|---|---|
| Keys, volume, power | Android TV Remote protocol. The box shows a 6-character pairing code. | The phone acts as a Bluetooth HID keyboard and remote. |
| Keyboard | Full Unicode text | US keyboard characters only |
| Gyro mouse | Needs the box receiver app. The box shows a 4-digit pairing code. | The phone acts as a Bluetooth mouse. No app on the box. |

Apple does not let an iPhone act as a Bluetooth keyboard or mouse. Thus the iPhone app works over Wi-Fi only.

The [docs](docs) folder describes the design:

- [ARCHITECTURE.md](docs/ARCHITECTURE.md): the apps, the transports, and the security model.
- [ANDROID_TV_REMOTE_PROTOCOL.md](docs/ANDROID_TV_REMOTE_PROTOCOL.md): pairing and control messages of the Android TV Remote protocol.
- [RECEIVER_PROTOCOL.md](docs/RECEIVER_PROTOCOL.md): the protocol between the phone and the box receiver.
- [UI_SPEC.md](docs/UI_SPEC.md): screens, colors, and sizes that both phone apps share.

The [legal](legal) folder has the privacy policy and the terms of use for the app stores.

## Build the Android apps

You need JDK 17 and the Android SDK (platform 35). Run these commands in the `android` folder.

```
./gradlew :app:assembleDebug :receiver:assembleDebug
```

The APKs are in `android/app/build/outputs/apk/debug/` and `android/receiver/build/outputs/apk/debug/`. To run the unit tests:

```
./gradlew :app:testDebugUnitTest :receiver:testDebugUnitTest
```

To install the receiver on a box and turn on its service, read [android/receiver/README.md](android/receiver/README.md).

## Build the iOS app

You need a Mac with Xcode 15 or later and XcodeGen. Read [ios/README.md](ios/README.md).

## Status

- The Android apps build, and their unit tests pass.
- The iOS app and its widget were written on Windows. They are not compiled or tested yet. Expect small compile fixes in Xcode.
- No app was tested on a real TV box yet. This includes the home screen widgets and the controller mode.
- The Android Bluetooth device now includes a game controller. A box that paired with an older build can keep the old device description. If the controller does not work over Bluetooth, remove the phone from the Bluetooth list of the box and pair again.
