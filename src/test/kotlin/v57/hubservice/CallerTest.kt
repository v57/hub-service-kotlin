package v57.hubservice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallerTest {
  @Test
  fun noHubContextMeansNoCaller() {
    assertNull(Caller.fromContext(null))
    assertNull(Caller.fromContext(mapOf("service" to "x")))
    val request = Request.of("echo", "hi", null)
    assertNull(request.caller)
    assertNull(request.hubKey)
    assertEquals(emptyMap<String, Any?>(), request.context)
  }

  @Test
  fun anonymousCallerHasNoKeyOrDevice() {
    val caller = Caller.fromContext(mapOf("hub" to mapOf("caller" to mapOf("groups" to listOf<String>(), "owner" to false))))!!
    assertNull(caller.key)
    assertFalse(caller.hasDevice)
    assertFalse(caller.owner)
    assertEquals(emptyList(), caller.groups)
  }

  @Test
  fun mergedHubCallerKeepsTheUnverifiedClaim() {
    val caller = Caller.fromContext(
      mapOf(
        "hub" to mapOf(
          "caller" to mapOf(
            "key" to "merging",
            "groups" to listOf("g1", 5),
            "owner" to false,
            "via" to "merged-hub",
            "claimed" to mapOf("key" to "remote", "device" to mapOf("id" to "d1", "name" to "Phone"), "owner" to true),
          ),
        ),
      ),
    )!!
    assertEquals("merging", caller.key)
    assertEquals(listOf("g1"), caller.groups)
    assertTrue(caller.viaMergedHub)
    assertEquals("remote", caller.claimed?.key)
    assertEquals("Phone", caller.claimed?.deviceName)
    assertTrue(caller.claimed!!.owner)
  }
}
