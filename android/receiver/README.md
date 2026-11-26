# TVBox Remote Receiver

TVBox Remote Receiver is a small app for an Android TV box. It lets the TVBox Remote phone app move a mouse cursor over Wi-Fi. The receiver draws the cursor on the TV and taps or swipes the screen at the cursor.

The receiver is necessary only for the mouse over Wi-Fi. The phone controls keys, volume, and text through Android TV Remote, which the box has already. An Android phone can also act as a Bluetooth mouse without the receiver.

## How it works

- The receiver is an Android accessibility service (`CursorAccessibilityService`). An accessibility service can draw overlay windows and send touch gestures to other apps.
- While the service is on, a TCP server listens on port 6470. If that port is in use, the server uses a free port.
- The receiver announces itself on the local network as the Bonjour service `_tvboxremote._tcp`, with the name of the box.
- The phone and the receiver speak the protocol in [docs/RECEIVER_PROTOCOL.md](../../docs/RECEIVER_PROTOCOL.md).
- A click is a 40 ms tap at the cursor. A long press is a 600 ms tap. A scroll is a 250 ms swipe from the cursor.
- The cursor hides after 8 seconds with no movement.

## Build

Run this command in the `android` folder:

```
./gradlew :receiver:assembleDebug
```

The APK is `android/receiver/build/outputs/apk/debug/receiver-debug.apk`.

## Install

The receiver is not in an app store. The newest signed receiver is always at this link:

```
https://github.com/IgorZaharov/TVBoxControlApp/releases/latest/download/receiver.apk
```

To install it without a computer, open TVBox Remote on the phone, then the mouse screen. It offers two ways:

- Download from this phone. The phone serves the receiver on the Wi-Fi under a short address, for example `192.168.1.23:8080`, and shows the steps:
  1. On the box, install the free app Downloader (by AFTVnews) from the Play Store. A browser also works.
  2. In Downloader, type the address from the phone and select Go.
  3. Select Install. If the box asks, allow Downloader to install unknown apps.
- Type link on the box. On the box, select the address field of a browser or Downloader. When the box keyboard opens, the phone types the link above and presses Enter. Some apps do not take text from the phone. Then use the first way.

### If Play Protect blocks the install

The box can show "App blocked to protect your device", with only a Got it button. Google Play Protect blocks apps that use an accessibility service when they come from a browser, Downloader, a file manager, or a messaging app. The receiver needs an accessibility service to move the cursor, so this block can happen. Play Protect does not block an install over ADB.

To install the receiver without a computer:

1. Select Got it.
2. On the box, open the Play Store. Open your profile, then Play Protect, then Settings. The menu names can be different on some boxes.
3. Turn off Scan apps with Play Protect.
4. Install the receiver again.
5. Turn Scan apps with Play Protect back on.

Install it from a computer with ADB (Android Debug Bridge):

1. On the box, open Settings, then Device Preferences, then About. Select Build seven times to turn on Developer options.
2. In Developer options, turn on USB debugging or network debugging.
3. On the computer, connect to the box: `adb connect <box IP address>`.
4. Install the app: `adb install receiver.apk`.

You can also copy the APK to a USB drive and open it with a file manager on the box.

## Turn on the service

1. Open TVBox Remote Receiver on the box.
2. Select "Open accessibility settings".
3. Select "TVBox Remote mouse" and turn it on.

On Android 13 and later, the switch can be gray for an app that is not from the Play Store. If the switch is gray, open Settings, then Apps, then TVBox Remote Receiver. Open the menu of the app info screen and select "Allow restricted settings". Then turn on the service.

If the box has no accessibility settings screen, turn on the service with ADB:

```
adb shell settings put secure enabled_accessibility_services com.tvboxremote.receiver/.CursorAccessibilityService
```

This command replaces the list of enabled accessibility services. If other services are on, add them to the value, separated by `:`.

## Pairing

1. On the phone, open TVBox Remote and open the mouse screen.
2. The phone finds the receiver and connects.
3. The TV shows a 4-digit code for 2 minutes. Type the code on the phone.
4. The phone stores a token. Next time, it connects without a code.

To remove all paired phones, select "Forget paired phones" on the receiver screen. A phone that is connected stays connected until it disconnects.

## Security

- The server accepts connections on all network interfaces of the box. A home router normally blocks connections from the internet. The traffic is not encrypted, so a device on the same network can see the commands and the token. Use the receiver only on a home network that you trust.
- A phone must pair with a code that the TV shows. The receiver counts wrong codes from all connections together, so a reconnect does not give more tries. After 5 wrong codes, pairing is locked for 5 minutes. Phones that paired before can still connect.
- The receiver stores only a SHA-256 hash of each token. It excludes its data from backups.
- The service does not read the content of the screen (`canRetrieveWindowContent` is false).
- To stop the receiver, turn off the accessibility service.

## Tests

Run the JVM unit tests in the `android` folder:

```
./gradlew :receiver:testDebugUnitTest
```

The tests cover the message parser, the pairing state machine, and the line reader.
