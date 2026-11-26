# UI specification

Both phone apps follow this specification, so that they look and work the same.

## App name and icon

- Store name (Google Play and the App Store): "Tiny TVBox Remote Control". About shows this name.
- Home screen name: "TVBox Remote". The store name is too long for the label under the icon. Alerts and the widget also use "TVBox Remote".
- Icon: a glowing blue D-pad on a dark navy rounded square. The master art is `store/app-store/icon-1024.png`. `tools/make_icons.py` makes all icon files from the logo image.
- iOS: one 1024 × 1024 px icon without transparency. iOS applies its own corner mask.
- Android: an adaptive icon. The foreground is the D-pad art on navy (`#021A45`), with the D-pad ring at about 52 dp of the 108 dp layer, inside the 66 dp safe zone. The themed icon (Android 13 and later) is a one-color D-pad outline.
- Google Play listing icon: `store/google-play/icon-512.png`.

## Colors and shapes

The default theme is dark. The values in this table are the dark theme with the blue accent. For the light theme and the other accents, see "Themes and accents".

| Token | Value | Use |
|---|---|---|
| background | `#0E0F12` | Screen background |
| surface | `#1C1E24` | Buttons, D-pad ring |
| surfacePressed | `#2A2D35` | Button while pressed |
| onSurface | `#E8EAF0` | Icons and text |
| muted | `#8A8F9C` | Secondary text |
| accent | `#4F8CFF` | OK button, active toggles, links |
| power | `#E5484D` | Power icon |
| ok | `#30A46C` | Status dot: connected |
| warn | `#F5A524` | Status dot: connecting |

- Round buttons are 72 pt (dp) in diameter. The icon is 30 pt.
- The D-pad is a ring of 264 pt. The OK button in the center is 104 pt.
- A press gives a short haptic tick (if haptics are on), a soft tap sound (if the press sound is on), and shows `surfacePressed`.
- Arrow buttons, volume up, and volume down repeat while you hold them: first repeat after 400 ms, then every 110 ms.

## Themes and accents

The user picks a theme and an accent color in Preferences. The default is the dark theme with the blue accent. The light theme is for bright rooms, so its contrast comes first and its light effects are subtle.

### Theme

| Choice | Effect |
|---|---|
| Dark (default) | The colors of "Colors and shapes" above. |
| Light | The light tokens below. |
| System | Dark or light, the same as the phone setting. |

| Token | Dark | Light |
|---|---|---|
| background | `#0E0F12` | `#F2F3F5` |
| surface | `#1C1E24` | `#FFFFFF` |
| surfacePressed | `#2A2D35` | `#E4E6EA` |
| onSurface | `#E8EAF0` | `#14161B` |
| bodyText | `#C9CCD4` | `#2A2E36` |
| muted | `#8A8F9C` | `#565C68` |
| power | `#E5484D` | `#D9363C` |
| ok (status dot) | `#30A46C` | `#218A57` |
| warn (status dot) | `#F5A524` | `#B7730A` |
| backgroundTop | `#16181D` | `#F8F9FA` |
| backgroundBottom | `#0A0B0E` | `#E6E8EC` |
| bodyTop | `#272A33` | `#FFFFFF` |
| bodyBottom | `#17191E` | `#E9EBEF` |
| bodyPressedTop | `#17191E` | `#E1E3E8` |
| bodyPressedBottom | `#22252D` | `#F1F2F4` |
| shadow | black 50% | black 16% |
| rim (top of the stroke) | white 12% | white 90% |
| sheen | white 8% | white 45% |
| stick well (center, edge) | `#07080A`, `#1B1D23` | `#D5D8DE`, `#EEF0F3` |

In the light theme, the background grain is black only, at 0 to 2% opacity.

### Accent

Each accent has a color for each theme, the two ends of the OK button gradient, and the text color on the OK button (onAccent).

| Accent | Dark: accent, OK top, OK bottom, onAccent | Light: accent, OK top, OK bottom, onAccent |
|---|---|---|
| Blue (default) | `#4F8CFF`, `#6FA2FF`, `#3A74E8`, white | `#2563EB`, `#4A7FF0`, `#1D4FC4`, white |
| Green | `#3DBE7E`, `#5BD495`, `#24965F`, `#14161B` | `#127A46`, `#1E9A5A`, `#0F6B3D`, white |
| Orange | `#FF9A3D`, `#FFB15E`, `#F07F1A`, `#14161B` | `#A84C0A`, `#D9691A`, `#A64708`, white |
| Red | `#FF5A5F`, `#FF7A7E`, `#E0383E`, white | `#C9272D`, `#E0383E`, `#A81E23`, white |
| RGB (rainbow) | See below | See below |

The accent replaces `accent` everywhere: the OK button, the press glow, the D-pad arc light, the signal line, the light at the top of the remote background, active toggles, sliders and links. The power button keeps its own `power` color.

RGB (rainbow): the accent moves slowly through all hues, one full cycle in 8 s.

- The color is HSL with saturation 85%. Its lightness starts at 62% (dark theme) or 36% (light theme). The app then changes the lightness in steps of 2% until the color has a contrast of at least 4.5:1 with `background`.
- The OK button gradient is the accent 12% lighter at the top and 12% darker at the bottom. onAccent is white or `#14161B`, whichever has the higher contrast with the accent.
- The app updates the color about 20 times per second, only while the app is visible.
- Reduced motion: the color stays at hue 220 (blue) and does not move.

### Contrast

All values above were checked against WCAG 2.2:

- Text in `onSurface`, `bodyText` and `muted`: at least 4.5:1 on `background`, `surface` and the button body, in both themes.
- The accent as text (links, values): at least 4.5:1 on `background`, in both themes and with every accent.
- The "OK" label: bold, 22 pt. It counts as large text, so it needs 3:1 on the middle of the OK gradient. Every accent passes, and in the light theme every accent passes 4.5:1.
- Icons and status dots: at least 3:1 on what is behind them.

## Text and languages

All user-visible text is in resource files, not in code.

- Android: `res/values/strings.xml` (English) and `res/values-uk/strings.xml` (Ukrainian).
- iOS: `Sources/Shared/Localization/en.lproj/Localizable.strings` and `Sources/Shared/Localization/uk.lproj/Localizable.strings`. The app and the widget share these files.

To add a language, copy the English file into a new language folder and translate it. Brand and protocol names stay in English: TVBox Remote, Android TV, Google TV, Wi-Fi, Bluetooth.

## Premium look

The remote looks like a machined object: a dark textured body with raised buttons that catch the light.

### Background texture

The app background has four layers, from bottom to top:

1. A vertical gradient from `#16181D` (top) to `#0A0B0E` (bottom).
2. A soft radial light at the top center: accent color at 7% opacity, radius 70% of the screen width.
3. The texture of the Background preference:
   - Gradient (default): no texture. The background is the gradient, the accent light and the grain, as in the first version of the app.
   - Leather: a slight pebble leather, described below.
   - Satin: soft satin folds that run from the top left to the bottom right, with a broad sheen on the ridges. The image is 600 × 1300 px and is not a tile: it fills the screen (scaled to fill, the rest cropped), so the folds never repeat. White sheen at most 22%, black fold shadows at most 30%. Dark theme: full strength. Light theme: 70%. The file is `satin_folds.png` on Android and `SatinFolds` on iOS. `tools/make_satin.py` makes it.

   The leather is a seamless 384 × 384 px tile, shown at 128 × 128 pt (dp), repeated over the screen. Light comes from the top left: the pebble tops have white highlights (at most 8%), the grooves between them are black (at most 20%). Dark theme: the tile at full strength. Light theme: the tile at 55%. The file is `leather_grain.png` on Android and `LeatherGrain` on iOS. `tools/make_leather.py` makes it.
4. A fine grain: a 128 × 128 px noise tile, generated once in code, repeated over the screen. Each pixel is white or black at 0 to 4% opacity. The grain is fixed and does not move.

All screens use this background: Remote, Controller, Preferences, Devices, Mouse, Help and About. Sheets and dialogs use the plain `surface` color.

### Raised buttons

Every round button and the volume pill have these layers:

| Layer | Value |
|---|---|
| Drop shadow | Black at 50%, blur 14, offset y 6 (shrinks to blur 6, offset y 2 while pressed) |
| Body | Linear gradient, top `#272A33`, bottom `#17191E` |
| Rim | 1 px stroke, white 12% at the top, fading to white 0% at the bottom |
| Sheen | A radial white highlight at 8% opacity, radius 60% of the button. It follows the tilt of the phone (see Motion). |
| Press glow | A ring of light outside the button, in the button's glow color, while the finger is down. Hidden at rest. |
| Press flash | A momentary glow on each finger down (see "Press glow"). |

While pressed, the body gradient flips (top `#17191E`, bottom `#22252D`), so that the button looks pushed in.

Glow colors: the power button glows in `power`. All other buttons glow in `accent`.

The OK button body is an accent gradient: top `#6FA2FF`, bottom `#3A74E8`. It has a constant soft accent halo (accent 25%, blur 24). The halo becomes stronger (accent 55%) while the button is pressed.

The D-pad ring is one raised surface. A press on an arrow does not move the ring. It lights an arc of the ring's inner edge on that side, in `accent`.

### Press glow

Each press of a button on the Remote and Controller screens gives a momentary glow around that button.

- It starts on finger down, also for a short tap, and always plays to its end (520 ms).
- It has two parts, both in the button's glow color: a soft halo (65%, blur 10) and a thin bright edge (50%, 1.5 pt). Both have the shape of the button.
- It grows from 3 pt to 12 pt outside the edge of the button (ease out). Its opacity goes up to full in the first 15% of the time, then fades out (ease in).
- D-pad arrows (Remote and Controller): a band of light outside the ring rim on the side of the arrow, 64 degrees wide.
- Volume pill: the glow goes around the whole pill.
- Controller sticks: the knob glows when the finger lands.
- A held button keeps the press glow ring. Repeats while held do not start a new flash.
- Reduced motion: the glow does not grow. It stays 3 pt outside the edge and fades in and out in 300 ms.
- The glow is decorative. It has no accessibility label.

### D-pad glow

A soft light breathes slowly around the D-pad ring, in the accent color, with every accent.

- A thin rim on the outer edge of the ring: accent at 55%, 1.5 pt wide.
- A blurred halo outside the ring: accent, blur 22 pt. Its opacity goes from 18% to 42% and back in 5 s (sine ease in and out).
- With the RGB accent, the halo and the rim change hue with the accent.
- Reduced motion: the halo stays at 30% and does not breathe.
- The glow is decorative. It has no accessibility label.

### Signal line

A thin vertical line on the remote screen shows each command that the app sends to the box.

- Position: centered horizontally, and vertically in the middle of the free height between the top row and the D-pad ring. The line is vertical.
- Size: 4 pt wide, round ends, 64 pt high. The line uses the free height above the D-pad and does not push the D-pad down. On a short screen it gets shorter, so that it keeps at least 16 pt clear above and below it, but it is never shorter than 24 pt.
- At rest: a track of white at 6% opacity (black at 8% in the light theme).
- When the app sends a command (a key, a text change, a mouse action), a pulse of light runs up the line from the bottom to the top. The pulse is an accent gradient, 40% of the line high, white at its top end, with a soft glow. At the same time the whole track glows briefly.
- A new command during a pulse starts a second pulse. At most three pulses show at the same time.
- If a press is not sent (no box is connected), no pulse shows.
- The line is decorative for screen readers. It has no accessibility label.

## Motion

Motion gives feedback for a press, and shows that a command went to the box. The values come from the Animate UI motion tokens.

| Role | Android (Compose) | iOS (SwiftUI) | Use |
|---|---|---|---|
| press | `spring(dampingRatio = 0.64f, stiffness = 550f)` | `.interpolatingSpring(stiffness: 550, damping: 30)` | Button scale and body change |
| pop | `spring(dampingRatio = 0.72f, stiffness = 400f)` | `.interpolatingSpring(stiffness: 400, damping: 29)` | Press glow coming in |
| fade out | `tween(260, easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f))` | `.timingCurve(0.23, 1, 0.32, 1, duration: 0.26)` | Press glow going out |
| flash | `tween(520, easing = LinearEasing)`, then the curve in "Press glow" | `TimelineView`, 0.52 s, the same curve | Press flash |
| pulse | `tween(450, easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f))` | `.timingCurve(0.23, 1, 0.32, 1, duration: 0.45)` | Signal line pulse |
| highlight | `spring(dampingRatio = 0.89f, stiffness = 500f)` | `.interpolatingSpring(stiffness: 500, damping: 40)` | Sheen following the tilt |

- Press: a button scales to 0.97 on finger down, and back to 1 on release. The volume pill segments and the D-pad arrows do not scale. Their surface is too large, so only the light changes.
- Press glow: comes in on `pop`, goes out on `fade out`.
- Press flash: see "Press glow".
- Dynamic reflection: the sheen on each button moves with the tilt of the phone, at most 30% of the button radius from the center. The app reads the gravity sensor at the normal UI rate, only while the remote screen or the controller screen is visible. It turns the sensor axes to the screen axes, so that the light is right in landscape too.
- Reduced motion: if the system setting asks for less motion (Android: "Remove animations", iOS: "Reduce Motion"), buttons do not scale, the sheen stays at the top left, and the signal pulse does not move. The whole signal line fades in and out instead (300 ms). Colors and glows still change.

## Home screen widget

The widget is a small remote for the home screen.

- Content: the device name, Power, a D-pad with OK, Back, Home, and a button that opens the app.
- A press sends the key to the device of the widget. The app does not open.
- If the app is not connected, the widget connects first. The first press can take about one second.
- If the widget cannot reach the device, a short message shows ("Cannot reach Living Room TV").
- If there are no saved devices, the widget shows "Add a TV box in the app". A tap opens the app.
- Saved devices: each device that the app connected to at least once. The most recent device is first. "Forget" removes a device.

Android:

- Two widgets in the widget list: "TVBox Remote" (default 3 × 3 cells) and "TVBox Remote, compact" (4 × 2 cells: the D-pad on the left, the name, Power, Back and Home on the right, as the iPhone medium widget). You can resize both. A widget lower than 150 dp uses the compact card.
- The D-pad buttons fill their cells: Android 12 and later size each arrow to 92 % and OK to 98 % of a third of the D-pad. The whole cell takes the press, not only the round button.
- A press shows on the button: an accent ripple, the flipped gradient and a 2 dp accent ring (white on OK). A widget cannot draw the press glow of the app.
- A press starts a short foreground service that sends the key. Android blocks the network of a closed app in the background, so a plain broadcast waited until the app opened. The service stops 8 s after the last press, before Android shows its notification (after about 10 s).
- With two or more saved devices (Android 12 and later), swipe up or down on the widget to go to the next device. The devices are a card stack.
- On Android 9 to 11, a tap on the device name goes to the next device.
- Bluetooth devices work too. The widget connects as a Bluetooth keyboard if necessary.

iOS (iOS 17 and later):

- Sizes: medium (D-pad on the left, the other buttons on the right) and large.
- A press shows the system press effect of iOS on the button while the key goes out (`invalidatableContent`).
- Wi-Fi devices only.
- Edit the widget to choose its device. A small "next device" button in the widget header also goes to the next saved device.

## Controller mode

A full-screen game controller in the style of a generic console gamepad. The screen is always landscape.

### Entry and exit

- The remote screen has a Controller button (gamepad icon) in the top row, after Mouse.
- The controller screen turns the phone to landscape, hides the system bars, and keeps the screen on.
- An Exit button at the top center, or the system Back gesture, goes back to the remote screen in portrait.

### Default layout

| Area | Controls |
|---|---|
| Top left | L2 (top), L1 (below it) |
| Top right | R2 (top), R1 (below it) |
| Top center | Select, Exit, Start |
| Left, upper | Left stick |
| Left, lower | D-pad |
| Right, upper | Face buttons: Y (top), X (left), B (right), A (bottom) |
| Right, lower | Right stick |

- Face buttons are raised round buttons (64 pt) with a colored letter: A green `#30A46C`, B red `#E5484D`, X blue `#4F8CFF`, Y yellow `#F5A524`. The press glow uses the letter color.
- Sticks: a base of 132 pt and a knob of 60 pt. The point where the finger lands is the center of the stick, so a tap does not move it. The knob follows the finger inside the base and springs back to the center on release (press spring). A tap on a stick without movement is a stick click (L3 or R3).
- Shoulder buttons: wide rounded rectangles, 96 × 44 pt.
- All controls work at the same time with several fingers.
- Each press gives a haptic tick, if haptics are on.

### What each transport sends

Bluetooth (Android only): the app adds a game controller to its Bluetooth device. The TV device sees a standard gamepad.

| Control | Bluetooth report |
|---|---|
| A, B, X, Y | Buttons 1, 2, 4, 5 |
| L1, R1, L2, R2 | Buttons 7, 8, 9, 10 |
| Select, Start | Buttons 11, 12 |
| L3, R3 | Buttons 14, 15 |
| D-pad | Hat switch, 8 directions |
| Left stick | X and Y axes |
| Right stick | Z and Rz axes |

Wi-Fi: the Android TV Remote protocol has keys but no analog axes.

- Buttons send gamepad key codes: A 96, B 97, X 99, Y 100, L1 102, R1 103, L2 104, R2 105, L3 106, R3 107, Start 108, Select 109. The D-pad sends 19 to 22.
- A press sends "key down" (START_LONG) and the release sends "key up" (END_LONG), so that the TV sees how long you hold a button.
- The left stick acts as a D-pad: past 50% of its travel, it holds the arrow key of its direction (8 directions become up to two arrows).
- The right stick moves the mouse pointer through the receiver app, if it is connected. Without the receiver, the right stick shows as unavailable.
- A small note under Select and Start says: "Over Wi-Fi, the left stick works as a D-pad."

### Layout editor

Preferences has a "Controller layout" item. It opens the editor, in landscape.

- The editor shows the controller. The controls do not send anything.
- Drag a control to move it. A tap selects a control and shows a "Size" slider (60% to 160%) for it.
- "Reset" puts all controls back to the default layout. "Done" saves and closes. "Cancel" closes without saving.
- The Size slider and the three buttons are in one panel in the middle of the screen, between the sticks and below Select and Start. There, the panel covers no control of the default layout.
- The app keeps, for each control, its center as a fraction of the screen width and height (0 to 1) and its size factor. A control cannot go past the screen edge.
- The layout is the same for all devices.

## Remote screen (main screen)

From top to bottom:

1. Top bar. Left: the status dot and the box name ("Not connected" if none). A tap on the name opens Devices. Right: a menu button (three dots).
2. Row: Power (left). Keyboard, Mouse and Controller (right).
3. D-pad with OK.
4. Row: Back, Home, Menu (Android TV key 82, the menu of the current app).
5. Volume pill: a wide rounded bar with Volume down, Mute, Volume up.

The signal line (see Premium look) is above the D-pad.

Layout for one-hand use: items 1 and 2 stay at the top. Items 3 to 5 form one group at the bottom of the screen, so that the D-pad is in thumb reach. All free height goes between item 2 and the D-pad. The signal line sits in the middle of that free height.

| Space in the bottom group | Size |
|---|---|
| D-pad to the Back, Home, Menu row | 20 pt |
| That row to the volume pill | 16 pt |
| Volume pill to the volume text | 8 pt |
| Volume text to the bottom of the safe area | 16 pt |

Check: on a 390 × 844 and on a 360 × 800 screen, the whole bottom group shows, and the center of the D-pad is in the lower 60% of the screen.

If no box is connected, a tap on any control opens Devices.

## Menu

The menu button opens a list with these items:

- Devices
- Media keys
- Preferences
- Help
- About

## Devices screen

- Section "Wi-Fi": boxes found through `_androidtvremote2._tcp`. Each row shows the name and IP address. If a receiver at the same IP address is found, the row shows "Mouse ready".
- Section "Bluetooth" (Android only): paired Bluetooth devices, a "Make phone visible" button, and help text.
- Known devices come first in each section. A known device is one that the app connected to before (the saved devices of the widget, the most recent first), or a paired Wi-Fi box. The row has an accent tint, an accent icon, and the text "Connected before". On Android the row also has a 1 dp accent border. On iOS it has a 4 pt accent edge at the left.
- A row tap connects. If the box is not paired, pairing starts.
- "Add by IP address" opens a dialog for a manual IP address.
- "Forget" (long press or swipe) removes the stored pairing.

## Pairing dialogs

- Android TV pairing, in three states:
  1. "Asking the TV for a code…" with a spinner, until the box confirms the request. Button: Cancel.
  2. "Enter the code shown on your TV". Six characters, hexadecimal, upper case, monospace font. Under the field: "No code on the TV? Wake the TV, then tap Ask again." Buttons: Ask again (a new request, and the TV shows a new code), Cancel, Pair.
  3. "Paired", "Connecting to <box>…" with a spinner. A box that has just paired can refuse the first connection for a moment, so the phone retries 3 times, 1, 2 and 3 s apart. It never asks for a second code on its own.
- Receiver pairing: "Enter the 4-digit code shown on your TV". Numeric keyboard.

## Keyboard sheet

A bottom sheet with one text field. The field has focus and the system keyboard opens.

- Each change sends the text to the box (see the protocol files).
- The Enter key of the system keyboard sends Enter (key 66).
- A "Delete" button sends Delete (key 67). A "Clear" button clears the field.

## Media keys sheet

A grid of round buttons: Previous, Rewind, Play/Pause, Fast forward, Next, Channel up, Channel down, Input, Guide, Search, All apps, Settings.

## Mouse screen

- Top bar: "Mouse", a "Gyro" switch (on by default), and "Done".
- A large touch pad fills the screen.
  - With gyro on, the gyro moves the cursor.
  - One-finger drag also moves the cursor.
  - Tap: click. Long press: long press.
  - Two-finger drag: scroll.
- Bottom row: Back, a wide "Click" button, Home.
- The screen stays on while it is open. The gyro runs only while this screen is open.
- If no mouse transport is available, the screen shows: "Install TVBox Remote Receiver on your box to use the mouse over Wi-Fi." (On Android, it adds: "Or connect with Bluetooth.") With a Wi-Fi box, it also shows the receiver install options (see "Receiver install").

### Receiver install

The receiver is not in an app store. The Mouse screen offers three ways to get it on the box:

- Copy link: copies `https://github.com/IgorZaharov/TVBoxControlApp/releases/latest/download/receiver.apk`.
- Type link on the box (Wi-Fi box only): the box takes text from the phone only for the field that has the focus while the box keyboard is open. So the phone waits up to 60 s for the box to report a field (a batch edit or an IME show request), then types the link into it and sends Enter. A dialog says what to do on the box, with a spinner while the phone waits. It ends with "typed" or "the box keyboard did not open". While the phone waits, the phone keyboard sheet does not open. Some apps on the box do not take text from the phone; then the field stays empty.
- Download from this phone: the phone downloads receiver.apk from GitHub (or uses the copy from an earlier download), and serves it on the Wi-Fi on port 8080 (8000, 8888 or 8081 to 8099 if 8080 is in use). Every path gets the APK as an attachment named receiver.apk, so the address alone works. A full screen shows:
  - The address, for example `192.168.1.23:8080`, in 40 pt bold monospace in the accent color, and the full `http://` form under it.
  - Four numbered steps: install Downloader from the Play Store (or use a browser), type the address and select Go, select Install and allow unknown apps, then open the receiver and turn on the mouse service.
  - "Waiting for the box…", then "The box downloaded the receiver." after a full download.
  - The phone and the box must be on the same Wi-Fi network. The screen stays on while it is open, and the server stops when it closes.

### Mouse setup

When the mouse is not ready on Wi-Fi (no receiver found), the Mouse screen shows "Set up the mouse" and big choice buttons:

- "Connect via Bluetooth" (Android only): no app on the box. It opens Devices at the Bluetooth section.
- "Install auxiliary Receiver on device": opens the receiver install guide.

An iPhone cannot act as a Bluetooth mouse, so iOS shows only the receiver button and says why. Copy link, Type link on the box and Try again stay below the buttons. "Download from this phone" stays in the Type link dialog.

### Receiver install guide

A full-screen dim with one card (the coach card style, see "First-run tutorial"). The phone serves receiver.apk while the guide is open, as in "Download from this phone", and the screen stays on. Six steps, each with a drawing of the TV screen (dark, whatever the phone theme is), the thing to select lit with an accent ring, and a hand-drawn arrow at it:

1. Open Google Play on the TV.
2. Install Downloader by AFTVnews.
3. Type the address: the phone's address in 24 sp monospace with an accent outline, and "Waiting for the box". "Let the phone type it" uses Type link on the box. When the box downloads the file, the guide moves on to step 4 by itself.
4. Select Install, and allow Downloader to install unknown apps if the TV asks.
5. Turn on the mouse service, with the "Allow restricted settings" hint for a gray switch.
6. Enter the code from the TV. The drawing shows dots, not digits. The phone looks for the receiver every 3 s from step 4. When the receiver asks for its code, the guide moves on to step 6 by itself, and the code dialog opens over it. When the mouse is ready, the guide closes.

Back, step dots and Next are at the bottom. A step change slides 24 dp and fades: the old step leaves in 150 ms, the new one comes in over 300 ms after 150 ms. Under reduced motion, steps only fade.

## First-run tutorial

The tour runs on the first launch only. A phone that paired with a box before (an update) skips it. Help has "Show the tutorial again" and "Show the mouse guide again".

| Step | Screen | Spotlight | Card |
|---|---|---|---|
| 1 of 3 | Remote | The box name at the top left | "Pair your TV box" |
| 2 of 3 | Devices, boxes found | All Wi-Fi rows | "Select your TV box" |
| 2 of 3 | Devices, no box after 3 s | "Add by IP address" | "No box found yet", with where the box shows its address |
| Done | Remote, after the link works | None, centered card | A check that draws itself, "You are all set!", Done |

The mouse walk-through runs the first time the Mouse screen shows the setup. Its card points at the choice buttons. On Android, "Connect via Bluetooth" then opens Devices with a second card on the Bluetooth button ("Make phone visible", or Allow or Turn on Bluetooth first). It ends when the Bluetooth link works or the user leaves Devices.

Each card has one quiet action: "Skip tour" in the tour, "Not now" in the mouse walk-through. No card shows while a dialog or sheet is open, for example the pairing dialog.

Look:

- The screen dims: black `#040508` at 66 %, in 400 ms on the soft ease-out `cubic-bezier(0.215, 0.61, 0.355, 1)`.
- The spotlight is a hole in the dim, 8 dp around the target, with a corner radius of half its height (at most 22 dp). Its edge is a 1.5 dp accent ring with a soft accent glow, and a second ring pulses out 10 dp and fades every 1.8 s. The spotlight glides to the next target on a critically damped spring.
- The arrow is hand-drawn: a 3 dp accent line with round ends on a gentle cubic bend, a fainter 1.5 dp second line 2.7 dp beside it, a wide faint stroke under it as glow, and a head of two 12 dp wings at 28 degrees. It draws itself in 780 ms (line, then head) on `cubic-bezier(0, 0, 0.58, 1)`, then nudges 4 dp toward the target every 2.4 s.
- The card: the surface color at 86 % (iOS: a thin material under the surface at 60 %), corner radius 20 dp, a 1.5 dp accent outline at 70 % and a 16 dp accent glow. It follows the theme and the accent, RGB too. It rises 12 dp and fades in on a spring (damping 0.72, stiffness 400) 550 ms after the step starts.
- Text: a 12 sp step label in capitals, a 17 sp semibold title, a 15 sp body with 22 sp lines.
- Under reduced motion, nothing moves: the arrow is drawn at once and the card fades in 200 ms.

Placement (`CoachLayout` in both apps, tested): the card goes on the side of the target with more free space, 64 dp away to leave room for the arrow, at most 340 dp wide, 16 dp from the screen edges and the system bars. With less space it comes closer, but never closer than 16 dp, so it never covers the target. The arrow runs from the card edge to the target edge.

Touch: the control in the spotlight takes the tap. Taps on the dimmed rest do nothing, so the user follows the step or skips it.

## Preferences

The Preferences screen uses the remote background (gradient, accent light, grain). Its options sit on matte glass cards.

### Glass card

| Layer | Dark theme | Light theme |
|---|---|---|
| Frost | The background under the card, blurred. iOS: the ultra-thin system material. Android 12 and later: the card draws the part of the background under it with a 16 dp blur. Android 11 and earlier: no blur, the fill covers the texture. | The same |
| Fill (over the frost) | Vertical gradient, `#262930` at 80% at the top to `#1B1D23` at 74% at the bottom | Vertical gradient, white 90% at the top to white 82% at the bottom |
| Border | 1 pt, white 12% at the top fading to white 4% at the bottom | 1 pt, white 100%, plus a hairline of black 6% outside it |
| Shadow | Black 45%, blur 24, offset y 10 | Black 10%, blur 20, offset y 8 |
| Corner radius | 20 pt | 20 pt |

- Text on the cards meets the contrast rules of "Themes and accents" on the fill color over the background.
- Rows in one card are separated by a hairline (onSurface at 8%) inset 64 pt from the left.
- Each row has a leading icon in a 36 pt rounded square (radius 10): accent at 14% fill, the icon in the accent color.
- Rows are at least 56 pt high. A tap on a row with a switch toggles the switch.

### Content

Five cards, each with a small section title above it (13 pt, semibold, muted, upper case):

1. Appearance
   - Theme: System, Dark or Light. Default Dark. A segmented control in a glass track (a pill, 44 pt high). Each segment has an icon (auto, moon, sun) and a label. The selected segment is a raised body (see "Raised buttons") with a 1.5 pt accent border.
   - Accent color: Blue, Green, Orange, Red or RGB. Default Blue. See "Accent picker".
   - Background: Gradient, Leather or Satin. Default Gradient. Three tiles in one row. Each tile shows a sample of the background at its real scale, as a window into a phone-size screen: the dark theme on the left half and the light theme on the right half, so both themes show at once. The name is under the sample. The selected tile has a 2 pt accent border and the check badge of the accent picker.
2. Language
   - App language: System, English or Українська. Default System (the language of the phone). The row shows the current choice under its title and a chevron on the right. Language names always show in their own language.
   - Android: a tap opens a dialog with the three choices as radio buttons. The app uses the per-app language of Android (AppCompat on Android 12 and earlier), so the choice also shows in the system settings. After a change, the screen reloads in the new language and stays on Preferences. On Android 12 and earlier, a home screen widget that updates before the app opens after a restart shows the phone language until the app opens.
   - iOS: iOS keeps the per-app language in the Settings app. A tap opens the page of the app in Settings. The row shows the language that the app uses now.
3. Remote
   - Haptics (on or off). Default on.
   - Press sound (on or off). Default off. A soft tap of 35 ms on each button press (`tap.wav`, made by `tools/make_tap.py`, peak -12 dBFS). Android plays it at half of the system sound volume (SoundPool, sonification). iOS plays it as a system sound. Silent mode mutes it on both.
   - Keep screen on while the remote is open. Default off.
4. Pointer
   - Gyro speed: slider from 0.5 to 3.0. Default 1.0.
   - Touch pad speed: slider from 0.5 to 3.0. Default 1.0.
5. Controller
   - Controller layout: opens the layout editor (see Controller mode).

Switches and sliders use the accent color.

### Accent picker

Five tiles in one row, inside the Appearance card.

- Each tile is a small glass card (radius 16, at least 56 × 84 pt) with a jewel and the color name under it (12 pt).
- The jewel: a ring 34 pt wide, stroke 3 pt, in the accent color of the current theme. Inside the ring, a soft radial glow of the same color at 35%. The RGB jewel ring is a color wheel.
- Selected tile: a 1.5 pt accent border, an outer accent glow (35%, blur 14), the name in onSurface, semibold, and a check badge on the top right corner (20 pt circle in the accent color, check mark in onAccent). The selection is clear without color: the badge and the border show it.
- Other tiles: the name in muted.
- A press scales the tile to 0.97 (press spring). The tile does not move other content.
- Screen readers: each tile is a radio button with its color name, and the selected tile says "selected".

The app keeps every setting between starts: the items above, the Gyro switch of the Mouse screen (default on), and the controller layout (saved when you tap Done in the editor).

## Help and About

Help has these sections: Getting started, Pairing with Wi-Fi, Bluetooth (Android only), Mouse and receiver, Keyboard, Troubleshooting.

About shows: the store name "Tiny TVBox Remote Control", version, a one-line description, the protocols that the app uses, a privacy note ("The app talks only to devices on your local network. It collects no data."), and a credit to the `androidtvremote2` project for the protocol research.
