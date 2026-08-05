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

  @Volatile
  private var client: Client<Any?>? = null

  @Volatile
  private var wasConnected = false

  private val started = java.util.concurrent.atomic.AtomicBoolean(false)

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

  /**
   * Registers an app: its header goes to the hub apps list and its path
   * serves the AppInterface as a stream (TS parity).
   */
  fun app(app: App): Service {
    apps += app.header
    addPath(app.header.path, null)
    channel.stream<Any?, Any?, Unit>(app.header.path) { req ->
      req.emitter.emit(app.interfaceMap())
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

  /**
   * Calls an API through the hub (client side). Returns the decoded response,
   * or throws [IllegalStateException] if the service is not started and
   * [ChannelException] if the hub reports a remote error.
   */
  fun send(path: String, body: Any? = null): Any? {
    val c = client ?: throw IllegalStateException("Service not started")
    return when (val result = c.send(Any::class.java, path, body, Unit)) {
      is CallResult.Ok -> result.value
      is CallResult.RemoteErr -> throw ChannelException(result.error)
    }
  }

  /** Opens a stream call through the hub (client side). */
  fun values(path: String, body: Any? = null): Values<Any> {
    val c = client ?: throw IllegalStateException("Service not started")
    return c.values(Any::class.java, path, body, Unit)
  }

  /**
   * Connects to the hub and starts the poll thread. The poll thread drives
   * the connection and, on every isConnected false→true transition (per
   * connection), sends `hub/profile/update` (if a profile is set) followed by
   * `hub/service/update`. The Kotlin client has no onConnect callback
   * (ConnectOptions carries only state + headers), so the transition is
   * detected by polling [Client.poll] + [Client.isConnected]. The client does
   * not auto-reconnect — when the connection drops the poll thread dials a
   * fresh connection, which re-triggers the updates (the hub drops services
   * on disconnect).
   */
  fun start(): Service {
    if (started.getAndSet(true)) return this
    thread(name = "hub-service") { pollLoop() }
    return this
  }

  private fun pollLoop() {
    while (!Thread.currentThread().isInterrupted) {
      var c = client
      if (c == null || !c.isConnected()) {
        if (c != null) {
          // Connection lost: the transport closed and the peer stopped.
          wasConnected = false
          client = null
        }
        c = try {
          connectClient()
        } catch (e: Exception) {
          Thread.sleep(1000)
          continue
        }
        client = c
      }
      try {
        c.poll()
      } catch (e: Exception) {
        // Socket error mid-poll: tear the transport down and redial.
        runCatching { c.stop() }
        wasConnected = false
        client = null
        Thread.sleep(1000)
        continue
      }
      val connected = c.isConnected()
      if (connected && !wasConnected) {
        wasConnected = true
        sendUpdates()
      } else if (!connected) {
        wasConnected = false
      }
      Thread.sleep(50)
    }
  }

  private fun connectClient(): Client<Any?> = when (val a = addressValue) {
    is Int -> channel.connect(a, ConnectOptions())
    is String -> channel.connect(a, ConnectOptions())
    else -> throw IllegalArgumentException("address must be an Int port or a ws:// String URL")
  }

  private fun sendUpdates() {
    val c = client ?: return
    try {
      profile?.let { c.send(Any::class.java, "hub/profile/update", it.toMap(), Unit) }
    } catch (e: Exception) {
      // The hub has no profile endpoint wired (hub-lite always has); ignore.
    }
    try {
      c.send(Any::class.java, "hub/service/update", update(), Unit)
    } catch (e: Exception) {
      // Will be re-sent on the next reconnect.
    }
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
