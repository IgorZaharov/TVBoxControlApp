# Release signing

The Release workflow (`.github/workflows/release.yml`) makes signed builds on GitHub. You do not need a Mac.

- iOS: the workflow signs the app and uploads it to TestFlight.
- Android: the workflow signs the app bundle (`.aab`). You upload it to Google Play yourself.

The workflow reads the keys from GitHub secrets. A GitHub secret is a value that only workflows can read. This document tells you how to make each key on Windows and how to add it as a secret.

Keep all key files outside the repository, for example in `C:\signing`. The repository is public. If a key file gets into a commit, anyone can sign apps as you.

## Secrets

| Secret | Platform | What it contains |
|---|---|---|
| `APPLE_TEAM_ID` | iOS | Your 10-character Apple Team ID |
| `IOS_DIST_CERT_P12_BASE64` | iOS | The Apple Distribution certificate with its private key (`.p12`), as base64 text |
| `IOS_DIST_CERT_PASSWORD` | iOS | The password of the `.p12` file |
| `IOS_APP_PROFILE_BASE64` | iOS | The App Store profile for `com.tvboxremote.ios`, as base64 text |
| `IOS_WIDGET_PROFILE_BASE64` | iOS | The App Store profile for `com.tvboxremote.ios.widget`, as base64 text |
| `ASC_KEY_ID` | iOS | The Key ID of the App Store Connect API key |
| `ASC_ISSUER_ID` | iOS | The Issuer ID of the App Store Connect API key |
| `ASC_KEY_P8_BASE64` | iOS | The App Store Connect API key file (`.p8`), as base64 text |
| `ANDROID_KEYSTORE_BASE64` | Android | The upload keystore (`.jks`), as base64 text |
| `ANDROID_KEYSTORE_PASSWORD` | Android | The keystore password |
| `ANDROID_KEY_ALIAS` | Android | The key alias in the keystore, for example `upload` |
| `ANDROID_KEY_PASSWORD` | Android | The key password |

## How to add a secret

Use the GitHub CLI (`gh`) in PowerShell, in the repository folder `D:\Projects\TVBoxControlApp`.

For a text value, run the command without a value. `gh` asks for the value and does not show it on the screen:

```powershell
gh secret set APPLE_TEAM_ID
```

For a file, encode it as base64 and send it to `gh`:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\signing\ios_dist.p12")) | gh secret set IOS_DIST_CERT_P12_BASE64
```

You can also add secrets on the GitHub website: open the repository, then Settings > Secrets and variables > Actions > New repository secret.

To make sure that all secrets are there, run `gh secret list`. It shows only the names, never the values.

## iOS

You need a paid Apple Developer Program membership.

### 1. Find the Team ID

1. Sign in to https://developer.apple.com/account.
2. Open Membership details. Copy the Team ID.
3. Add it as the secret `APPLE_TEAM_ID`.

### 2. Make the App Group and the App IDs

The app and the widget share data through an App Group. Both App IDs must have it.

1. Open Certificates, Identifiers & Profiles > Identifiers.
2. Click +, select App Groups, and register `group.com.tvboxremote.shared`.
3. Click +, select App IDs > App. Register the bundle ID `com.tvboxremote.ios` (Explicit).
4. In its capabilities, select App Groups. Click Configure and select `group.com.tvboxremote.shared`. Save.
5. Do steps 3 and 4 again for the bundle ID `com.tvboxremote.ios.widget`.

### 3. Make the distribution certificate

On a Mac, Keychain Access makes the certificate request. On Windows, OpenSSL does it. OpenSSL comes with Git for Windows. Run these commands in Git Bash:

```bash
mkdir -p /c/signing && cd /c/signing
openssl genrsa -out ios_dist.key 2048
openssl req -new -key ios_dist.key -out ios_dist.csr -subj "/emailAddress=YOUR_EMAIL/CN=YOUR_NAME/C=YOUR_COUNTRY_CODE"
```

1. In Certificates, Identifiers & Profiles > Certificates, click +.
2. Select Apple Distribution. Upload `C:\signing\ios_dist.csr`.
3. Download the certificate. Save it as `C:\signing\distribution.cer`.

Join the certificate and the private key into one `.p12` file. Run these commands in Git Bash:

```bash
cd /c/signing
openssl x509 -inform DER -in distribution.cer -out distribution.pem
openssl pkcs12 -export -inkey ios_dist.key -in distribution.pem -out ios_dist.p12 \
  -keypbe PBE-SHA1-3DES -certpbe PBE-SHA1-3DES -macalg sha1
```

OpenSSL asks for an export password. The `-keypbe`, `-certpbe` and `-macalg` options are necessary. Without them, macOS cannot read the file.

Add the secrets:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\signing\ios_dist.p12")) | gh secret set IOS_DIST_CERT_P12_BASE64
```

```powershell
gh secret set IOS_DIST_CERT_PASSWORD
```

Keep `ios_dist.key` and `ios_dist.p12` in a safe place. If you lose them, revoke the certificate and make a new one.

### 4. Make the two provisioning profiles

1. In Certificates, Identifiers & Profiles > Profiles, click +.
2. Select App Store Connect (Distribution). Select the App ID `com.tvboxremote.ios`.
3. Select the certificate from step 3. Name the profile `TVBox Remote App Store`.
4. Download it as `C:\signing\app.mobileprovision`.
5. Do steps 1 to 4 again for `com.tvboxremote.ios.widget`. Name it `TVBox Remote Widget App Store`, and save it as `C:\signing\widget.mobileprovision`.

Add the secrets:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\signing\app.mobileprovision")) | gh secret set IOS_APP_PROFILE_BASE64
```

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\signing\widget.mobileprovision")) | gh secret set IOS_WIDGET_PROFILE_BASE64
```

If you change the App IDs or make a new certificate, make the profiles again and replace the two secrets.

### 5. Make the app in App Store Connect

1. Sign in to https://appstoreconnect.apple.com. Open Apps, click +, and select New App.
2. Platform: iOS. Name: `Tiny TVBox Remote Control`. Bundle ID: `com.tvboxremote.ios`. SKU: any unique text, for example `tvboxremote-ios`.

The upload fails if this app record does not exist.

### 6. Make the App Store Connect API key

The workflow uses this key to upload the build.

1. In App Store Connect, open Users and Access > Integrations > App Store Connect API.
2. Click + (Generate API Key). Name: `GitHub Actions`. Access: App Manager.
3. Copy the Issuer ID (above the list) and the Key ID (in the row of the key).
4. Click Download. Save the file as `C:\signing\AuthKey.p8`. You can download it only one time.

Add the secrets:

```powershell
gh secret set ASC_KEY_ID
```

```powershell
gh secret set ASC_ISSUER_ID
```

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\signing\AuthKey.p8")) | gh secret set ASC_KEY_P8_BASE64
```

## Android

Google Play App Signing keeps the key that signs the app on phones. You sign each upload with your own upload key. If you lose the upload key, Google Play support can reset it.

### 1. Make the upload keystore

Run this command in PowerShell. `keytool` asks for a keystore password, your name and your organization:

```powershell
& "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\keytool.exe" -genkeypair -v -keystore C:\signing\upload.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload
```

The keystore is in PKCS12 format, so the key password is the same as the keystore password.

### 2. Add the secrets

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\signing\upload.jks")) | gh secret set ANDROID_KEYSTORE_BASE64
```

```powershell
gh secret set ANDROID_KEYSTORE_PASSWORD
```

```powershell
gh secret set ANDROID_KEY_PASSWORD
```

```powershell
gh secret set ANDROID_KEY_ALIAS --body upload
```

### 3. The first upload to Google Play

Google Play does not accept API uploads before the first release exists. Upload the first `.aab` by hand:

1. In Google Play Console, make the app. Name: `Tiny TVBox Remote Control`.
2. Open Test and release > Testing > Internal testing, and make a new release.
3. Accept Play App Signing. Upload `app-release.aab` from the workflow artifact `TVBoxRemote-android-release`.

## Run the Release workflow

```powershell
gh workflow run Release -f platform=both
```

You can also push a tag, for example `v1.0.0`. A tag builds both apps and uploads the iOS build.

- The workflow uses its run number as the build number (iOS `CFBundleVersion`, Android `versionCode`). Each run is higher than the run before it.
- The version name comes from `MARKETING_VERSION` in `ios/project.yml` and `versionName` in `android/app/build.gradle.kts`. Change it there for a new version.
- After an iOS upload, Apple processes the build for 5 to 30 minutes. Then it shows in App Store Connect > TestFlight.
- The signed files are in the workflow artifacts `TVBoxRemote-ios-appstore` and `TVBoxRemote-android-release`.

If a secret is missing, the first step of the job fails and names the secret.
