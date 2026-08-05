package v57.hubservice

import channel.defaultJsonCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceTest {

  @Test
  fun servicesListIncludesRegisteredPaths() {
    val s = Service()
    s.post("echo") { it }
    s.stream("echo/values") { _, _ -> }
    s.app(App(header = AppHeader(name = "Echo", path = "echo/ui"), body = listOf("hi")))
    val u = s.update()
    assertEquals(3, (u["services"] as List<*>).size, "post + stream + app path")
    val apps = u["apps"] as List<*>
    assertEquals(1, apps.size)
    val app0 = apps[0] as Map<*, *>
    assertEquals("echo/ui", app0["path"])
    assertEquals("app", app0["type"])
  }

  @Test
  fun settingsApplyToUpdatePayload() {
    val s = Service()
    s.post("echo", ApiSettings(permissions = ServicePermissions(name = "Echo", allows = true), limit = 5)) { it }
    val u = s.update()
    val services = u["services"] as List<*>
    assertEquals(1, services.size)
    val svc = services[0] as Map<*, *>
    assertEquals(5, svc["limit"])
    val perms = svc["permissions"] as Map<*, *>
    assertEquals("echo", perms["group"], "group auto-filled from first path segment")
    assertEquals("Echo", perms["name"])
    assertEquals(true, perms["allows"])
  }

  @Test
  fun profileOnlyWhenSet() {
    assertNull(Service().profile, "no name/icon -> no profile")
    val named = Service(name = "Echo")
    assertNotNull(named.profile)
    assertEquals("Echo", named.profile?.name)
    val iconed = Service(icon = "speaker.wave.2")
    assertNotNull(iconed.profile)
    assertEquals("speaker.wave.2", iconed.profile?.icon)
  }

  @Test
  fun updateJSONMatchesWireContract() {
    val s = Service()
    s.post("echo", ApiSettings(permissions = ServicePermissions(name = "Echo", allows = true), limit = 5)) { it }
    val json = defaultJsonCodec().encode(s.update())
    val want = """{"services":[{"path":"echo","permissions":{"group":"echo","name":"Echo","allows":true},"limit":5}],"apps":[]}"""
    assertEquals(want, json)
  }

  @Test
  fun sendBeforeStartThrows() {
    val s = Service()
    assertFailsWith<IllegalStateException> { s.send("echo", "x") }
    assertFailsWith<IllegalStateException> { s.values("echo", "x") }
  }

  @Test
  fun appInterfaceShape() {
    val app = App(
      header = AppHeader(name = "Echo", path = "echo/ui"),
      body = listOf("hi", textField("echo", placeholder = "p", action = Action(path = "echo/upper"))),
      data = linkedMapOf("hello" to "world"),
    )
    val iface = app.interfaceMap()
    // All 5 keys always present (TS parity).
    assertEquals(setOf("header", "body", "top", "bottom", "data"), iface.keys)
    val header = iface["header"] as Map<*, *>
    assertEquals("app", header["type"])
    assertEquals("Echo", header["name"])
    assertEquals("echo/ui", header["path"])
    val json = defaultJsonCodec().encode(iface)
    val want = """{"header":{"type":"app","name":"Echo","path":"echo/ui"},"body":["hi",{"type":"textField","value":"echo","placeholder":"p","action":{"path":"echo/upper"}}],"top":null,"bottom":null,"data":{"hello":"world"}}"""
    assertEquals(want, json, "top/bottom null and all five keys on the wire")
  }

  @Test
  fun elementHelpersMatchWireShapes() {
    val elements = listOf<Any?>(
      progress("50%", min = 0, max = 100),
      button("Upper", Action(path = "echo/upper", body = "value", output = "value")),
      slider("5", defaultValue = 5, min = 0, max = 10, step = 1, action = Action(path = "echo")),
      list("rows", cell(title = "t")),
      picker(listOf("a", "b"), "a"),
      files("f.txt", action = Action(path = "files/open")),
      fileOperation(format = "json", action = Action(path = "files/save")),
      hstack(listOf("a"), spacing = 8),
      vstack(listOf("a")),
      zstack(listOf("a")),
      spacer(),
    )
    val json = defaultJsonCodec().encode(elements)
    assertTrue(json.contains(""""type":"progress""""), json)
    assertTrue(json.contains(""""type":"slider""""), json)
    assertTrue(json.contains(""""type":"hstack""""), json)
    assertTrue(json.contains(""""type":"fileOperation""""), json)
    assertTrue(json.contains(""""type":"spacer""""), json)
  }
}
