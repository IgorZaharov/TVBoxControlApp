# Android TV Remote protocol (version 2)

This file describes the parts of the protocol that the phone apps use. Google does not publish the protocol. This description follows the open-source `androidtvremote2` Python library, which reads the protocol from the Google TV remote app.

## Discovery

The box announces the Bonjour service `_androidtvremote2._tcp`. The announced port is the control port (normally 6466). The pairing port is 6467 on the same host.

## Framing

Each message is a protobuf message. Before each message there is its length as a protobuf varint. A reader buffers bytes, reads one varint, then reads that many bytes.

## TLS

Both ports use TLS (1.2 or 1.3).

- The phone presents a self-signed RSA 2048 client certificate. The phone makes the certificate on first start and keeps it. The box remembers paired clients by this certificate.
- The box presents a self-signed server certificate. The phone accepts it during pairing and stores its SHA-256 fingerprint. On later connections, the phone refuses a certificate with a different fingerprint.

## Protobuf messages

The apps use a small hand-written protobuf codec. There is no code generator. Field numbers are below. Types are proto3.

### Pairing (port 6467)

```
message PairingMessage {
  int32 protocol_version = 1;          // always 2
  int32 status = 2;                    // 200 OK, 400 error, 401 bad configuration, 402 bad secret
  PairingRequest pairing_request = 10;
  PairingRequestAck pairing_request_ack = 11;
  PairingOption pairing_option = 20;
  PairingConfiguration pairing_configuration = 30;
  PairingConfigurationAck pairing_configuration_ack = 31;
  PairingSecret pairing_secret = 40;
  PairingSecretAck pairing_secret_ack = 41;
}
message PairingRequest { string service_name = 1; string client_name = 2; }
message PairingRequestAck { string server_name = 1; }
message PairingEncoding { int32 type = 1; uint32 symbol_length = 2; }   // type 3 = hexadecimal
message PairingOption {
  repeated PairingEncoding input_encodings = 1;
  repeated PairingEncoding output_encodings = 2;
  int32 preferred_role = 3;            // 1 = input
}
message PairingConfiguration { PairingEncoding encoding = 1; int32 client_role = 2; }
message PairingConfigurationAck {}
message PairingSecret { bytes secret = 1; }
message PairingSecretAck { bytes secret = 1; }
```

Pairing sequence. Every message from the phone has `protocol_version = 2` and `status = 200`.

1. Phone sends `pairing_request { service_name: "atvremote", client_name: "<phone name>" }`.
2. Box sends `pairing_request_ack`.
3. Phone sends `pairing_option { input_encodings: [{ type: 3, symbol_length: 6 }], preferred_role: 1 }`.
4. Box sends `pairing_option`.
5. Phone sends `pairing_configuration { encoding: { type: 3, symbol_length: 6 }, client_role: 1 }`.
6. Box sends `pairing_configuration_ack` and shows a 6-character hexadecimal code on the TV.
7. The user types the code. The phone sends `pairing_secret { secret: <hash> }`.
8. Box sends `pairing_secret_ack`. Pairing is complete. The phone closes the connection.

If the box sends a status other than 200, pairing failed.

### Pairing secret

```
hash = SHA-256( client_modulus
              + client_exponent
              + server_modulus
              + server_exponent
              + bytes_from_hex(code[2..6]) )
```

- Each modulus and exponent is the unsigned big-endian value with no leading zero bytes. For the exponent 65537 the bytes are `01 00 01`.
- The keys come from the RSA public keys of the two TLS certificates.
- Check: `hash[0]` must equal `bytes_from_hex(code[0..2])`. If it does not, the code is wrong. Tell the user and do not send the secret.

### Remote control (port 6466)

```
message RemoteMessage {
  RemoteConfigure remote_configure = 1;
  RemoteSetActive remote_set_active = 2;
  RemoteError remote_error = 3;
  RemotePingRequest remote_ping_request = 8;
  RemotePingResponse remote_ping_response = 9;
  RemoteKeyInject remote_key_inject = 10;
  RemoteImeKeyInject remote_ime_key_inject = 20;
  RemoteImeBatchEdit remote_ime_batch_edit = 21;
  RemoteImeShowRequest remote_ime_show_request = 22;
  RemoteStart remote_start = 40;
  RemoteSetVolumeLevel remote_set_volume_level = 50;
  RemoteAppLinkLaunchRequest remote_app_link_launch_request = 90;
}
message RemoteConfigure { int32 code1 = 1; RemoteDeviceInfo device_info = 2; }
message RemoteDeviceInfo {
  string model = 1; string vendor = 2; int32 unknown1 = 3; string unknown2 = 4;
  string package_name = 5; string app_version = 6;
}
message RemoteSetActive { int32 active = 1; }
message RemoteError { bool value = 1; }
message RemotePingRequest { int32 val1 = 1; int32 val2 = 2; }
message RemotePingResponse { int32 val1 = 1; }
message RemoteKeyInject { int32 key_code = 1; int32 direction = 2; }  // direction: 1 start long, 2 end long, 3 short
message RemoteImeKeyInject { RemoteAppInfo app_info = 1; RemoteTextFieldStatus text_field_status = 2; }
message RemoteAppInfo { int32 counter = 1; string app_package = 12; }   // other fields ignored
message RemoteImeBatchEdit { int32 ime_counter = 1; int32 field_counter = 2; repeated RemoteEditInfo edit_info = 3; }
message RemoteEditInfo { int32 insert = 1; RemoteImeObject text_field_status = 2; }
message RemoteImeObject { int32 start = 1; int32 end = 2; string value = 3; }
message RemoteImeShowRequest { RemoteTextFieldStatus remote_text_field_status = 2; }
message RemoteTextFieldStatus { int32 counter_field = 1; string value = 2; int32 start = 3; int32 end = 4; string label = 6; }
message RemoteStart { bool started = 1; }
message RemoteSetVolumeLevel { uint32 volume_max = 6; uint32 volume_level = 7; bool volume_muted = 8; }
message RemoteAppLinkLaunchRequest { string app_link = 1; }
```

Features bit mask: ping 1, key 2, IME 4, power 32, volume 64, app link 512. The apps support `615` (all of these). They use the part of it that the box also gives in `code1` of its `remote_configure`.

Session sequence:

1. Box sends `remote_configure` with its features in `code1` and its `device_info` (the vendor tells an NVIDIA box). Phone answers `remote_configure { code1: 615 AND box features, device_info: { model, vendor, unknown1: 1, unknown2: "1", package_name: "atvremote", app_version: "1.0.0" } }`.
2. Box sends `remote_set_active`. Phone answers `remote_set_active { active: <the same features> }`.
3. Box sends `remote_start { started }`. `started` tells if the box is on.
4. Box sends `remote_ping_request { val1 }` about every 5 seconds. Phone must answer `remote_ping_response { val1 }`. If no ping comes for 16 seconds, the connection is dead.
5. Box sends `remote_set_volume_level` when the volume changes.
6. Box sends `remote_ime_key_inject` when a text field gets focus. The phone stores `app_info.counter` as the IME counter and `text_field_status.counter_field` as the field counter.
7. Box sends `remote_ime_show_request` when the on-screen keyboard opens. The phone stores `remote_text_field_status.counter_field` as the field counter, and it can open its own keyboard.
8. Box sends `remote_ime_batch_edit` when the text of a field changes. The phone takes `ime_counter` and `field_counter` from it only for a counter that steps 6 and 7 did not give.

The box drops a batch edit with old counters. Thus the apps take the counters from the focused field first (`ImeCounters` in both apps), as androidtvremote2 does. An app that read them only from `remote_ime_batch_edit` could not type at all.

### Keys

The phone sends `remote_key_inject { key_code, direction: 3 }` for a short press. For a long press, it sends direction 1, then direction 2.

The key codes are the Android `KeyEvent` codes:

| Key | Code | Key | Code |
|---|---|---|---|
| Home | 3 | Back | 4 |
| D-pad up | 19 | D-pad down | 20 |
| D-pad left | 21 | D-pad right | 22 |
| D-pad center (OK) | 23 | Volume up | 24 |
| Volume down | 25 | Power | 26 |
| Enter | 66 | Delete | 67 |
| Menu | 82 | Search | 84 |
| Play/pause | 85 | Next | 87 |
| Previous | 88 | Rewind | 89 |
| Fast forward | 90 | Mute | 164 |
| Info | 165 | Channel up | 166 |
| Channel down | 167 | Guide | 172 |
| Captions | 175 | Settings | 176 |
| TV input | 178 | Assistant | 219 |
| All apps | 284 | | |

NVIDIA SHIELD ignores key 176 (Settings). For a box with the vendor "NVIDIA", the Settings button sends the app link `intent:#Intent;action=android.settings.SETTINGS;launchFlags=0x10000000;end` instead. This link is not checked on a box yet.

### Text

The box inserts the text of a batch edit at the cursor of the focused field. It does not replace the field. If the phone sent the full field after each change, "test" became "ttetestest" on the box. Thus the phone sends only the change:

1. For each character that the user removed at the end, the phone sends key 67 (Delete).
2. Then the phone sends the new characters in one batch edit:

```
remote_ime_batch_edit {
  ime_counter: <stored ime_counter>
  field_counter: <stored field_counter>
  edit_info: [{ insert: 1, text_field_status: { start: len, end: len, value: "<new characters>" } }]
}
```

`len` is the number of characters (code points) in the value: the cursor is after the new text. The phone compares the field with the text that it sent before, from the first character that is different (`TextChange` in both apps). When the phone keyboard opens, that text is empty. When the box opens its keyboard with text in the field, that text is the start. To send Enter, the phone sends key 66.
