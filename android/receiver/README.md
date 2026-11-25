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

To install it without a computer:

1. On the box, open a browser (or an app that downloads files from a link) and select the address bar.
2. On the phone, open TVBox Remote, then the mouse screen. Select "Type link on the box". The phone types the link into the address bar. You can also select "Copy link" and paste it.
3. Download the file and open it. If the box asks, allow the browser to install unknown apps.

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
