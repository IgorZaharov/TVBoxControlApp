# TVBox Remote for iOS

TVBox Remote is a native iPhone app that controls Android TV and Google TV boxes over Wi-Fi. It uses SwiftUI and has no third-party dependencies.

## Features

- Finds boxes on the Wi-Fi network with Bonjour (`_androidtvremote2._tcp`), or by IP address.
- Pairs with the box through the Android TV Remote protocol (version 2). The TV shows a 6-character code.
- Remote screen with large buttons: Power, D-pad and OK, Back, Home, Settings, volume, and mute. Arrows and volume repeat while you hold them.
- Keyboard: the text appears on the TV as you type.
- Media keys, Preferences, Help, and About in the menu.
- Mouse: gyro pointer and touch pad. The mouse needs the TVBox Remote Receiver app on the box (`android/receiver`).
- Controller mode: a landscape game controller with two sticks, a D-pad, A, B, X, Y, Start, Select, and the shoulder buttons. You can move and resize the controls in Preferences.
- Home Screen widget (iOS 17 and later): a small remote with a D-pad, Power, Back, and Home. A press sends the key without opening the app.
- Themes and accents in Preferences: System, Dark (default) or Light, and a Blue (default), Green, Orange, Red or RGB accent. RGB moves slowly through all hues. The widget always uses the dark theme with the blue accent.
- English and Ukrainian text. The app follows the language of the iPhone.

An iPhone cannot act as a Bluetooth remote, mouse, or keyboard, because iOS does not allow it. Thus this app has no Bluetooth transport.

## Requirements

- A Mac with Xcode 15 or later.
- XcodeGen: `brew install xcodegen`.
- An iPhone with iOS 16 or later. The simulator cannot find boxes on your network reliably, and it has no gyroscope.

## Build

1. Generate the Xcode project:

   ```
   cd ios
   xcodegen
   open TVBoxRemote.xcodeproj
   ```

2. In Xcode, select the target TVBoxRemote, open Signing & Capabilities, and select your team. Do the same for the target TVBoxRemoteWidget. XcodeGen writes the project again each time you run it, and then a team that you set in Xcode is lost. To keep it, add `DEVELOPMENT_TEAM: <your team ID>` under `settings: base:` in `project.yml`. The App Group and the keychain group need a team: without one, the app and the widget do not share data, and the app logs "App Group ... is not available".
3. Make sure that both targets have the App Group `group.com.tvboxremote.shared` and the keychain group `com.tvboxremote.shared`. The entitlements files in `Config/` list both. Xcode registers the App Group for your team when you build the first time.
4. If Xcode says that a bundle identifier is not available, change `PRODUCT_BUNDLE_IDENTIFIER` of both targets in `project.yml` (the widget identifier must start with the app identifier). If you change the App Group, also change `SharedConfig.appGroup` and both entitlements in `project.yml`. Then run `xcodegen` again.
5. Select your iPhone and press Run.
6. To run the unit tests, press Command-U.

XcodeGen writes `Generated/Info.plist`, `Generated/Widget-Info.plist`, and `TVBoxRemote.xcodeproj`. Git ignores them. Edit `project.yml` instead. XcodeGen also writes the two files in `Config/` from `project.yml`.

## Targets

| Target | Type | iOS | Sources |
|---|---|---|---|
| TVBoxRemote | App | 16.0 | `Sources`, `Resources` |
| TVBoxRemoteWidget | Widget extension, embedded in the app | 17.0 | `Sources/Shared`, `Widget` |
| TVBoxRemoteTests | Unit tests | 16.0 | `Tests` |

The app and the widget share data in two places:

- The App Group UserDefaults: saved boxes, pinned certificate fingerprints, preferences, and the controller layout. On the first start after an update, the app copies the values of older builds from its standard UserDefaults.
- The keychain group: the client key and certificate. On the first start after an update, the app moves the items of older builds to this group. The widget only reads them. It never pairs and never makes a new key.

The widget lists each paired box that the app connected to at least once, the most recent first. Forget in Devices removes a box from the widget too.

The app icon set is empty. Add a 1024 x 1024 PNG to `Resources/Assets.xcassets/AppIcon.appiconset` before you publish the app.

## First start

1. iOS asks for access to the local network. Tap Allow. Without it, the app finds no boxes.
2. Tap the box name at the top, then tap your box.
3. Type the code that the TV shows.

## Source layout

| Folder | Contents |
|---|---|
| `Sources/App` | App entry, `AppModel` (state on the main actor), preferences, orientation lock |
| `Sources/Shared` | Code and text that the widget compiles too: Android TV Remote session, protobuf codec, key codes, DER and client certificate, saved boxes, theme, `L10n` |
| `Sources/Shared/Localization` | `Localizable.strings` and `InfoPlist.strings` for `en` and `uk` |
| `Sources/Protocol` | Android TV Remote pairing, receiver client |
| `Sources/Discovery` | Bonjour browsing and address resolution |
| `Sources/Motion` | Gyroscope to cursor mapping, tilt for the button sheen |
| `Sources/Controller` | Controller mode: layout, stick mapping, touch engine, screen, layout editor |
| `Sources/UI` | SwiftUI screens and the raised button components |
| `Widget` | Widget extension: timeline, views, App Intents, the widget connection |
| `Tests` | Unit tests |

## Text and languages

All user-visible text is in `Sources/Shared/Localization/<language>.lproj/Localizable.strings`. The code uses keys, for example `L10n.t("devices.title")`. To add a language, copy the `en.lproj` folder to a new folder, for example `de.lproj`, translate the values, and add the language code to `CFBundleLocalizations` in `project.yml`. Brand and protocol names stay in English.

The protocols are described in [`../docs`](../docs).

## Security

- On first start, the app makes an RSA 2048 key and a self-signed certificate. Both stay in the keychain of the iPhone.
- After pairing, the app stores the SHA-256 fingerprint of the box certificate. Later, it refuses a box with a different certificate.
- The receiver protocol is plain TCP on the local network, protected by a pairing token. Use it only on a home network that you trust.
