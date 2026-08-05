package v57.hubservice

import channel.CallResult
import channel.Channel
import channel.Client
import channel.ConnectOptions
import channel.Values
import java.util.LinkedHashSet
import kotlin.concurrent.thread

/**
 * Registers API endpoints on a hub (hub-lite/hub-pro) and serves calls
 * forwarded by it; also lets this process call API through the hub.
 *
 * Lite port of hub-service (TS reference): no auth, no keychain, no crypto.
 * The wire contract matches the TS/Go reference — `hub/profile/update` and
 * `hub/service/update` are re-sent on every (re)connect (the hub drops
 * services on disconnect).
 *
 * @param address a port (Int) or a ws:// URL (String); default 1997 or $HUB.
 * @param name    profile name shown in the hub.
 * @param icon    profile icon: a String or a Map like
 *                `linkedMapOf("symbol" to "speaker.wave.2", "text" to "E")`.
 */
class Service(
  address: Any? = null,
  val name: String? = null,
  val icon: Any? = null,
) {
  private val channel = Channel<Any?>()
  private val addressValue: Any = address ?: System.getenv("HUB") ?: 1997

  internal val profile: Profile? = if (name != null || icon != null) Profile(name, icon) else null

  // Every registered handler path (post, stream, app) in registration order;
  // the services list is built from it sorted by path (TS parity).
  private val paths = LinkedHashSet<String>()
  private val settings = linkedMapOf<String, ApiSettings>()
  private val apps = mutableListOf<AppHeader>()

  /**
   * Registers a request/response API endpoint. [handler] receives the decoded
   * request body and returns the response body (throw [ChannelException] to
   * signal an error to the caller).
   */
  fun post(path: String, settings: ApiSettings? = null, handler: (Any?) -> Any?): Service {
    addPath(path, settings)
    channel.post<Any?, Any?, Unit>(path) { req -> handler(req.body) }
    return this
  }

  /**
   * Registers a streaming API endpoint. [handler] receives the decoded request
   * body and an emit function to stream values back to the caller.
   */
  fun stream(path: String, settings: ApiSettings? = null, handler: (Any?, (Any?) -> Unit) -> Unit): Service {
    addPath(path, settings)
    channel.stream<Any?, Any?, Unit>(path) { req ->
      handler(req.body) { item -> req.emitter.emit(item) }
    }
    return this
  }

  /** Builds the current `hub/service/update` payload (services + apps). */
  fun update(): Map<String, Any?> {
    val services = paths.sorted().map { path ->
      val entry = linkedMapOf<String, Any?>("path" to path)
      settings[path]?.let { st ->
        st.permissions?.let { p ->
          val perms = linkedMapOf<String, Any?>()
          if (!p.group.isNullOrEmpty()) perms["group"] = p.group
          perms["name"] = p.name
          if (p.allows != null) perms["allows"] = p.allows
          entry["permissions"] = perms
        }
        if (st.limit != null) entry["limit"] = st.limit
      }
      entry
    }
    return linkedMapOf(
      "services" to services,
      "apps" to apps.map { it.toMap() },
    )
  }

  private fun addPath(path: String, settings: ApiSettings?) {
    if (settings != null) {
      val perms = settings.permissions
      val st = if (perms != null && perms.group.isNullOrEmpty()) {
        settings.copy(permissions = perms.copy(group = path.substringBefore('/')))
      } else {
        settings
      }
      this.settings[path] = st
    }
    paths += path
  }
}
