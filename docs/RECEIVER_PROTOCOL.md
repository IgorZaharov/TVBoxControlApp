# Box receiver protocol (version 1)

The box receiver app and the phone apps use this protocol for the mouse over Wi-Fi.

## Discovery and transport

- The receiver announces the Bonjour service `_tvboxremote._tcp` with the service name of the box (for example `Living Room TV`).
- The default port is 6470.
- The transport is TCP. The phone turns off Nagle's algorithm (`TCP_NODELAY`) for low latency.
- Each message is one JSON object on one line, encoded as UTF-8, ending with `\n`.
- Field `t` gives the message type. A side ignores unknown types and unknown fields.

## Phone to receiver

| `t` | Fields | Meaning |
|---|---|---|
| `hello` | `v`: 1, `name`: phone name, `token`: string or absent | First message. |
| `pair` | `code`: 4 digits | The code that the box shows. |
| `move` | `dx`, `dy`: numbers, in screen pixels | Move the cursor by this amount. |
| `click` | none | Tap at the cursor. |
| `longpress` | none | Hold at the cursor for 600 ms. |
| `scroll` | `dx`, `dy`: numbers, in screen pixels | Swipe from the cursor. Positive `dy` scrolls the content down (finger moves up). |
| `cursor` | `show`: boolean | Show or hide the cursor. |
| `ping` | none | Keep-alive. The phone sends it every 5 seconds. |

The receiver ignores `move`, `click`, `longpress`, `scroll`, and `cursor` until the phone is authorized.

## Receiver to phone

| `t` | Fields | Meaning |
|---|---|---|
| `welcome` | `v`: 1, `name`: box name, `w`, `h`: screen size in pixels | The phone is authorized. |
| `pair_required` | none | The token is absent or wrong. The box shows a 4-digit code on the TV. |
| `paired` | `token`: string | The code was correct. Store the token for this box. A `welcome` message follows. |
| `error` | `code`, `msg`: strings | See the error codes below. `msg` is for people, not for programs. |
| `pong` | none | Answer to `ping`. |

## Error codes

| `code` | Meaning | Does the receiver close the connection? |
|---|---|---|
| `bad_code` | The pairing code is not correct. | No |
| `pairing_locked` | 5 wrong codes in total, from all connections. Pairing is locked for 5 minutes. The receiver hides the code. A phone with a valid token can still connect. | Yes, at once |
| `code_expired` | The pairing code is older than 2 minutes. The receiver sends `pair_required` again and shows a new code. | No |
| `not_pairing` | The phone sent `pair`, but pairing is not in progress. | No |
| `bad_message` | The line is not a JSON object with a string field `t`. | No |
| `too_many_clients` | 4 phones are connected already. | Yes, at once |

## Limits

- A line can have at most 4096 bytes. The receiver closes the connection if a line is longer.
- The receiver closes a connection after 30 seconds with no message. Phones send `ping` every 5 seconds to keep the connection open.
- The receiver ignores a second `hello` on the same connection.
- The receiver clamps `dx` and `dy` to the range -10000 to 10000.
- The receiver accepts at most 4 phones at the same time.

## Sequence

1. The phone connects and sends `hello`.
2. If the token is known, the receiver sends `welcome`. Go to step 5.
3. If not, the receiver sends `pair_required` and shows a 4-digit code on the TV. The code is valid for 2 minutes. There is only one active code. If a code is active, a new phone or a reconnecting phone gets the same code and the remaining tries.
4. The phone asks the user for the code and sends `pair`. If the code is correct, the receiver sends `paired`, then `welcome`. If the code is wrong, it sends `error` with code `bad_code`. The receiver counts wrong codes from all connections together. After 5 wrong codes, it locks pairing for 5 minutes, hides the code, sends `error` with code `pairing_locked`, and closes the connection. While pairing is locked, a `hello` without a valid token gets `pairing_locked` too. The receiver forgets wrong codes that are older than 5 minutes.
5. The phone sends `move`, `click`, and the other commands.

The server runs inside the accessibility service of the receiver. If the user turns the service off, the receiver does not listen and does not announce itself. The phone then finds no receiver. Thus there is no error code for this condition.

## Example

```
-> {"t":"hello","v":1,"name":"Pixel 8"}
<- {"t":"pair_required"}
-> {"t":"pair","code":"4821"}
<- {"t":"paired","token":"9f1c2b..."}
<- {"t":"welcome","v":1,"name":"Living Room TV","w":1920,"h":1080}
-> {"t":"cursor","show":true}
-> {"t":"move","dx":12.5,"dy":-3.0}
-> {"t":"click"}
```
