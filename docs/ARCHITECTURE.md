# Architecture

TVBox Remote is a set of three native apps:

| App | Folder | Platform | Language and UI |
|---|---|---|---|
| Phone remote | `android/app` | Android phone, API 28 or later | Kotlin, Jetpack Compose |
| Phone remote | `ios` | iPhone, iOS 16 or later | Swift, SwiftUI |
| Box receiver | `android/receiver` | Android TV box, API 24 or later | Kotlin, Android views |

The phone remote controls an Android TV or Google TV box. The box receiver is optional. It is necessary only for the gyro mouse over Wi-Fi.

## Transports

A transport is one way to send commands to the box. The phone apps use three transports.

### 1. Android TV Remote (Wi-Fi, both phones)

This is the protocol of the Google TV remote app. The box runs the service already, so the user installs nothing on the box.

- Discovery: Bonjour (mDNS) service `_androidtvremote2._tcp`.
- Pairing: TLS on port 6467. The box shows a 6-character code. The user types the code on the phone.
- Control: TLS on port 6466. The phone sends key events, text for the on-screen keyboard, and volume keys.
- The protocol has no mouse.

The file [ANDROID_TV_REMOTE_PROTOCOL.md](ANDROID_TV_REMOTE_PROTOCOL.md) describes the messages.

### 2. Bluetooth HID (Android phone only)

HID (Human Interface Device) is the Bluetooth profile for keyboards and mice. An Android phone can act as a HID device through the `BluetoothHidDevice` API. The phone shows to the box as one combined device:

- A keyboard: arrow keys, Enter, Escape (Back), and text.
- A mouse: relative movement, buttons, and wheel. The gyro mouse uses this.
- A consumer control: power, home, back, volume, and media keys.

Apple does not let an iPhone act as a Bluetooth HID device. Thus the iOS app has no Bluetooth transport.

### 3. Box receiver (Wi-Fi, both phones)

The receiver is a small app on the box. It draws a mouse cursor and taps the screen through an Android accessibility service.

- Discovery: Bonjour service `_tvboxremote._tcp`.
- Control: newline-delimited JSON over TCP, port 6470.
- Pairing: the box shows a 4-digit code. The user types the code on the phone. The receiver then gives the phone a token for later connections.

The file [RECEIVER_PROTOCOL.md](RECEIVER_PROTOCOL.md) describes the messages.

## Which transport does what

| Function | Android TV Remote | Bluetooth HID | Box receiver |
|---|---|---|---|
| D-pad, OK, Back, Home | Yes | Yes | No |
| Volume, mute | Yes | Yes | No |
| Power on and off | Yes | Yes (power key) | No |
| Settings key | Yes | Partly, depends on the box | No |
| Text keyboard | Yes, full Unicode | Yes, US ASCII only | No |
| Gyro mouse | No | Yes | Yes |

On Wi-Fi, the phone uses Android TV Remote for keys and text, and the receiver for the mouse. On Bluetooth, the phone uses HID for all functions.

## Gyro mouse

The phone points at the TV with the screen up. The gyroscope gives rotation speed in radians per second.

- Turn left or right (rotation about the screen normal, axis z): `dx = -gz * speed * dt`.
- Tilt up or down (rotation about the phone's left-right axis, axis x): `dy = -gx * speed * dt`.

The app ignores very small rotation (a dead zone) to stop drift. It keeps the fraction of a pixel between samples. It sends about 60 to 100 updates per second.

## User interface

The main screen is a remote control with large buttons:

- Top bar: the connected box, a status dot, and the menu button.
- Power button.
- D-pad with OK in the center. Arrow buttons repeat while you hold them.
- Back, Home, Settings.
- Volume up, volume down, mute.
- Keyboard button and Mouse button.

The menu holds the other functions: Devices, Media keys, Preferences, Help, and About.

The mouse screen has a large touch pad. A tap is a click. A two-finger drag scrolls. The gyro moves the cursor.

## Security

- Android TV Remote uses TLS with a client certificate. The phone makes a self-signed certificate on first start. After pairing, the phone stores the SHA-256 fingerprint of the box certificate and refuses a different certificate later.
- The receiver protocol is plain TCP on the local network. A token from pairing protects it, but the traffic is not encrypted. Use it only on a home network that you trust.
