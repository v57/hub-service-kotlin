package v57.hubservice

import java.util.LinkedHashMap

/**
 * Raised by service handlers to signal an API error (the error text is sent
 * back to the caller as the remote error), and by [Service.send] when the
 * hub returns a remote error.
 */
class ChannelException(message: String) : RuntimeException(message)

/**
 * Profile sent to the hub as `hub/profile/update` on every (re)connect.
 * Only set when name and/or icon were provided to [Service].
 */
data class Profile(
  val name: String? = null,
  val icon: Any? = null,
) {
  fun toMap(): Map<String, Any?> {
    val m = LinkedHashMap<String, Any?>()
    if (name != null) m["name"] = name
    if (icon != null) m["icon"] = icon
    return m
  }
}

/** Identifies an app in the hub apps list (`hub/service/update` apps array). */
data class AppHeader(
  val type: String = "app",
  val name: String,
  val path: String,
) {
  fun toMap(): Map<String, Any?> = linkedMapOf("type" to type, "name" to name, "path" to path)
}

/**
 * Pro-only wire-parity permissions field; hub-lite ignores it. `group` is
 * auto-filled from the first path segment when missing (TS parity).
 */
data class ServicePermissions(
  val group: String? = null,
  val name: String? = null,
  val allows: Boolean? = null,
)

/** Settings for a registered API path (pro-only fields, wire parity). */
data class ApiSettings(
  val permissions: ServicePermissions? = null,
  val limit: Int? = null,
)

/**
 * An action references an API call from a UI element: the target [path] plus
 * optional [body] / [output] references (wire parity).
 */
data class Action(
  val path: String,
  val body: Any? = null,
  val output: Any? = null,
) {
  fun toMap(): Map<String, Any?> {
    val m = LinkedHashMap<String, Any?>()
    m["path"] = path
    if (body != null) m["body"] = body
    if (output != null) m["output"] = output
    return m
  }
}
