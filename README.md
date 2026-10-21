<h1>
  <img alt="Hub Service logo" src="./icon.png" width="70" valign="middle">
  &nbsp;hub-service-kotlin
</h1>

Kotlin port of [hub-service](https://github.com/v57/hub-service) — connect to a
[hub](https://github.com/v57/hub-lite) (default `127.0.0.1:1997`), host your own
post/stream API endpoints, register apps, and call API through the hub.

hub-server auth is supported: every (re)connect sends `auth` + `v` headers signed
by the ed25519 keychain (`auth` key file in the cwd, or `$HUBKEY`), bound to
the hub's key once it is pinned (`v: 1`). hub-lite ignores the headers. The
wire contract matches the TS reference (`hub/profile/update`,
`hub/service/update`, AppInterface + element DSL). Built on
[channel-kotlin](https://github.com/v57/channel-kotlin) via a Gradle composite
build.

## Install

`hub-service-kotlin` is a Gradle (Kotlin JVM) library. It expects
`channel-kotlin` as a sibling checkout and wires it in with
`includeBuild("../../Channel/channel-kotlin")`:

```kotlin
// settings.gradle.kts
rootProject.name = "my-service"
includeBuild("../../Channel/channel-kotlin")
```

```kotlin
// build.gradle.kts
dependencies {
  implementation(":channel-kotlin:unspecified")
}
```

## Usage

Start a hub first (`new Hub()` from hub-lite, listens on 1997), then:

```kotlin
import v57.hubservice.Action
import v57.hubservice.App
import v57.hubservice.AppHeader
import v57.hubservice.Service
import v57.hubservice.button
import v57.hubservice.cell
import v57.hubservice.textField
import java.util.concurrent.CountDownLatch

fun main() {
  val service = Service(
    address = 1997,
    name = "Echo",
    icon = linkedMapOf("symbol" to "speaker.wave.2", "text" to "E"),
  )
    .post("echo") { body -> body }
    .post("echo/upper") { body -> body.toString().uppercase() }
    .app(
      App(
        header = AppHeader(name = "Echo", path = "echo/ui"),
        body = listOf(
          "Hello from Kotlin",
          textField(
            "echo",
            placeholder = "Text to echo",
            action = Action(path = "echo/upper", body = "value", output = "value"),
          ),
          cell("\$Echo", "value"),
          button("Upper", Action(path = "echo/upper", body = "value", output = "value")),
        ),
        data = linkedMapOf("hello" to "world"),
      ),
    )
    .start()

  println("echo service started (hub-lite :1997)")
  CountDownLatch(1).await()
}
```

Call it through the hub (any language):

```kotlin
val response = service.send("echo/upper", "hello") // "HELLO"
```

## API

- `Service(address?, name?, icon?, sign?, hubKey?)` — `address` is a port
  (Int) or a `ws://` URL (String); default `1997` or `$HUB`. `name`/`icon` set
  the profile (`hub/profile/update` is only sent when set)
- Hub-bound auth: the first connect uses `v: 0`, asks `hub/info` for the hub
  key, pins it in `hubs.json` beside the key file and reconnects with `v: 1`
  (`hubKey` supplies it up front, `forgetHubKey()` clears the pin,
  `lastError` reports a refused connect). `Keychain.sign()` / `signHub(key)`
  make the tokens
- Device mode (on by default): every connect also sends a `device` header, a
  7-day certificate for the service key signed by the device key
  (`~/.hub/device.key`, `$HUB_DEVICE_KEY` or `deviceKeyPath`, created 0600;
  `deviceKey = KeyFile` for a key kept elsewhere), renewed at connect with
  less than a day left. `instance` names the service on its device (default:
  profile name, else the working directory's name, else `service`); `deviceName`,
  `platform`, `parent` override the suggestions; `device = false` turns it off
- `post(path, settings? = null) { body -> response }` — register a
  request/response endpoint; throw `ChannelException` to signal an error
- `post(path, settings? = null) { body, request -> response }` — the handler
  also gets the `Request`: `caller` (who is calling, from the hub's
  `context.hub.caller`: `key`, `deviceId`, `deviceName`, `groups`, `owner`,
  and `via`/`claimed` through a merged hub; null on hubs that don't send it,
  and unless the request's `context.hub.hubKey` is the pinned hub key),
  `hubKey` and the whole `context`
- `stream(path, settings? = null) { body, emit -> ... }` — register a
  streaming endpoint; call `emit(value)` per yielded value (also
  `{ body, request, emit -> }`)
- `stop()` — stops the poll thread and closes the connection
- Hub Network shares (TS `publishShare` parity): `share(name, protocol, host,
  port, policy?)` declares one before `start()` (republished on every
  connect); `publishShare(...)`, `updateShare(name, policy?, port?)` and
  `removeShare(name)` change the set at runtime. The local hub-net agent
  attests each share over its Unix socket (`$HUB_NET_AGENT_SOCKET`, else
  `/var/run/hub-net/agent.sock` on macOS, `/run/hub-net/agent.sock` on Linux;
  `network.agentSocket` overrides it), then the set goes to the hub with
  `network/shares/replace`. `network.publications` lists the results
- `app(app: App)` — header goes to the hub apps list; the app path streams the
  AppInterface (`header`, `body`, `top`, `bottom`, `data` — all five keys)
- `send(path, body?)` — client call through the hub
  (`IllegalStateException` if not started, `ChannelException` on remote error)
- `values(path, body?)` — stream call through the hub
- `start()` — connect + poll thread; re-announces `hub/profile/update` (if
  set) and `hub/service/update` on every (re)connect (the hub drops services
  on disconnect). The Kotlin client has no onConnect callback, so the
  connected transition is detected by polling `Client.poll()` +
  `Client.isConnected()`, and reconnects are dialed by the poll thread.

`ApiSettings(permissions = ServicePermissions(name = ...), limit = ...)` carries
wire-parity fields hub-lite ignores; `permissions.group` is auto-filled from
the first path segment when missing (TS parity).

Element helpers (TS app.ts shapes): `text` (plain string shorthand),
`progress`, `textField`, `button`, `slider`, `list`, `picker`, `cell`,
`files`, `fileOperation`, `hstack`, `vstack`, `zstack`, `spacer`.

## Test

```bash
./scripts/test            # unit tests (JVM)
./gradlew run             # example service (needs a hub on 1997)
# cross-language check (TS):
bun ../hub-service/tests/verify.ts echo echo/upper '"hello"' '"HELLO"'
bun ../hub-service/tests/appcheck.ts echo/ui Echo
```
