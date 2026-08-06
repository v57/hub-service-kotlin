package v57.hubservice.example

import v57.hubservice.Action
import v57.hubservice.App
import v57.hubservice.AppHeader
import v57.hubservice.Service
import v57.hubservice.button
import v57.hubservice.cell
import v57.hubservice.textField
import java.util.concurrent.CountDownLatch

/**
 * Echo service: registers `echo` + `echo/upper` posts and an app at
 * `echo/ui`, then connects to the hub (default 127.0.0.1:1997). Verified by
 * the cross-language TS harness:
 *
 *   bun ../hub-service/tests/verify.ts echo echo/upper '"hello"' '"HELLO"'
 *   bun ../hub-service/tests/appcheck.ts echo/ui Echo
 */
fun main() {
  val service = Service(
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

  println("echo service started (hub ${System.getenv("HUB") ?: "127.0.0.1:1997"})")
  CountDownLatch(1).await()
}
