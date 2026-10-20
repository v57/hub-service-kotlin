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
 * Port of hub-service (TS reference) with hub-pro auth: on every (re)connect
 * the client attaches fresh auth headers (`auth: <signed token>` + `v`) so
 * the connection is keyed — owner keys get services ENABLED immediately,
 * non-owner keys register as pending host approval. hub-lite ignores the
 * headers entirely (regression-safe). The wire contract matches the TS/Go
 * reference — `hub/profile/update` and `hub/service/update` are re-sent on
 * every (re)connect (the hub drops services on disconnect).
 *
 * Hub-bound auth (Docs/Devices/protocol.md section 2): with no pinned key for
 * the address, the service connects with `v: 0`, calls `hub/info`, pins the
 * returned key in `hubs.json` beside the key file and reconnects at once with
 * `v: 1` tokens signed over `hub1:<hub key>:<expiry>`. A hub that doesn't
 * answer `hub/info` (hub-lite) keeps getting v0. A refused v1 connect is
 * reported ([lastError]), never silently downgraded; call [forgetHubKey]
 * when the hub key really changed.
 *
 * Device mode (protocol.md section 3, on by default): every connect also
 * carries a `device` header, a certificate for the service key signed by the
 * device key. It is created at connect when missing or with less than 24
 * hours left (7-day lifetime). A device key that can't be read or created
 * turns device mode off for this service.
 *
 * @param address a port (Int) or a ws:// URL (String); default 1997 or $HUB.
 * @param name    profile name shown in the hub.
 * @param icon    profile icon: a String or a Map like
 *                `linkedMapOf("symbol" to "speaker.wave.2", "text" to "E")`.
 * @param sign    token factory for hub-pro auth (default: the keychain
 *                `auth` file in the cwd, signing v1 tokens once the hub key
 *                is pinned); a fresh token is signed for every (re)connect. A
 *                custom factory always connects with `v: 0`.
 * @param hubKey  the hub's SPKI if known up front (invitation or config);
 *                null = learn and pin it via `hub/info`.
 * @param device  device mode: send a device certificate with every connect.
 * @param deviceKey a caller-provided device key (e.g. kept in an app's own
 *                keychain); null = the device key file.
 * @param deviceKeyPath the device key file; null = [Device.defaultKeyPath].
 * @param instance the instance name on this device (`s3-local/Backup Disk`);
 *                default: the profile name, else the working directory's
 *                name, else `service`.
 * @param deviceName the suggested device name; null = the computer name.
 * @param platform the certificate platform; null = this OS.
 * @param parent  the declared parent device name (containers and VMs).
 */
class Service(
  address: Any? = null,
  val name: String? = null,
  val icon: Any? = null,
  val sign: (() -> String)? = null,
  hubKey: String? = null,
  device: Boolean = true,
  private val deviceKey: KeyFile? = null,
  private val deviceKeyPath: String? = null,
  private val instance: String? = null,
  private val deviceName: String? = null,
  private val platform: String? = null,
  private val parent: String? = null,
) {
  private val channel = Channel<Any?>()
  private val addressValue: Any =
    address ?: System.getenv("HUB")?.toIntOrNull() ?: System.getenv("HUB") ?: 1997

  internal val profile: Profile? = if (name != null || icon != null) Profile(name, icon) else null

  private val pins: HubPins? = if (sign == null) HubPins.beside(Keychain.keyPath()) else null

  /** The hub key v1 tokens are bound to, or null while connecting with v0. */
  @Volatile
  var hubKey: String? = hubKey ?: pins?.get(normalizeAddress(addressValue))
    private set

  /** The last connect error (e.g. a v1 connect the hub refused with HTTP 401), or null. */
  @Volatile
  var lastError: String? = null
    private set

  /** The address as `hubs.json` keys it ([normalizeAddress]). */
  val addressKey: String get() = normalizeAddress(addressValue)

  // A custom token factory hides the service key the certificate must name.
  @Volatile
  private var deviceMode = device && sign == null
  private var resolvedDeviceKey: KeyFile? = null
  private var certificate: String? = null
  private var certificateExpiry = 0L
  private var resolvedDeviceName: String? = null

  /**
   * The instance name the certificate carries: the option, else the profile
   * name, else the working directory's name, else `service`.
   */
  fun instance(): String =
    instance?.takeIf { it.isNotEmpty() }
      ?: name?.takeIf { it.isNotEmpty() }
      ?: java.nio.file.Path.of("").toAbsolutePath().fileName?.toString()?.takeIf { it.isNotEmpty() }
      ?: "service"

  /** The device key in use, or null before the first connect or with device mode off. */
  val currentDeviceKey: KeyFile? @Synchronized get() = resolvedDeviceKey

  /**
   * The `device` header for the next connect, or null with device mode off.
   * Reuses the current certificate while it has more than 24 hours left.
   */
  @Synchronized
  internal fun deviceHeader(): String? {
    if (!deviceMode) return null
    return try {
      val key = resolvedDeviceKey
        ?: (deviceKey ?: Device.loadOrCreateKey(deviceKeyPath?.let { java.nio.file.Path.of(it) } ?: Device.defaultKeyPath()))
          .also { resolvedDeviceKey = it }
      val now = Keychain.now()
      val current = certificate
      if (current != null && certificateExpiry - now >= Device.RENEW_BEFORE) return current
      val suggestedName = resolvedDeviceName ?: (deviceName ?: Device.defaultName()).also { resolvedDeviceName = it }
      val exp = now + Device.CERTIFICATE_LIFETIME
      val payload = Device.certificatePayload(
        key.publicKey, Keychain.publicKey(), instance(), suggestedName, platform ?: Device.defaultPlatform(), parent, now, exp,
      )
      Device.certificate(key, payload).also {
        certificate = it
        certificateExpiry = exp
      }
    } catch (e: Exception) {
      System.err.println("hub-service: device mode off: ${e.message}")
      deviceMode = false
      null
    }
  }

  /**
   * Clears the pinned hub key for this address, so the next connect uses v0
   * and pins whatever key `hub/info` returns. Use it when a hub's key changed
   * on purpose.
   */
  fun forgetHubKey() {
    hubKey = null
    pins?.remove(addressKey)
  }

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

  @Volatile
  private var stopped = false
  private var pollThread: Thread? = null

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
   * Registers a request/response API endpoint whose [handler] also receives
   * the [Request]: the caller identity the hub forwards ([Request.caller],
   * only when the request's hub key is the pinned one) and the whole request
   * context.
   */
  fun post(path: String, settings: ApiSettings? = null, handler: (Any?, Request) -> Any?): Service {
    addPath(path, settings)
    channel.post<Any?, Any?, Any?>(path) { req -> handler(req.body, Request.of(req.path, req.body, req.context, hubKey)) }
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
   * Registers a streaming API endpoint whose [handler] also receives the
   * [Request] with the caller identity.
   */
  fun stream(path: String, settings: ApiSettings? = null, handler: (Any?, Request, (Any?) -> Unit) -> Unit): Service {
    addPath(path, settings)
    channel.stream<Any?, Any?, Any?>(path) { req ->
      handler(req.body, Request.of(req.path, req.body, req.context, hubKey)) { item -> req.emitter.emit(item) }
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
    pollThread = thread(name = "hub-service") {
      try {
        pollLoop()
      } catch (e: InterruptedException) {
        // stop()
      }
    }
    return this
  }

  /** Stops the poll thread and closes the connection. */
  fun stop() {
    stopped = true
    pollThread?.interrupt()
    client?.let { runCatching { it.stop() } }
    client = null
  }

  private fun pollLoop() {
    while (!stopped && !Thread.currentThread().isInterrupted) {
      var c = client
      if (c == null || !c.isConnected()) {
        if (c != null) {
          // Connection lost: the transport closed and the peer stopped.
          wasConnected = false
          client = null
        }
        val headers = authHeaderMap()
        c = try {
          connectClient(headers)
        } catch (e: Exception) {
          reportConnectError(headers, e)
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
        lastError = null
        if (learnHubKey(c)) {
          // Pinned the hub key: reconnect at once with a v1 token.
          runCatching { c.stop() }
          wasConnected = false
          client = null
          continue
        }
        sendUpdates()
      } else if (!connected) {
        wasConnected = false
      }
      Thread.sleep(50)
    }
  }

  private fun connectClient(headers: Map<String, String>): Client<Any?> {
    val options = ConnectOptions<Any?>(headers = rawHeaders(headers))
    return when (val a = addressValue) {
      is Int -> channel.connect(a, options)
      is String -> channel.connect(a, options)
      else -> throw IllegalArgumentException("address must be an Int port or a ws:// String URL")
    }
  }

  /**
   * Fresh hub-pro auth headers for every (re)connect: a freshly signed token
   * and the protocol version, `1` with a token bound to the pinned hub key,
   * else `0`. [pollLoop] builds them on every dial (initial connect and every
   * reconnect), so each connection carries its own token. hub-lite ignores
   * them (regression-safe).
   */
  internal fun authHeaderMap(): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    val key = hubKey
    val custom = sign
    when {
      custom != null -> {
        headers["auth"] = custom()
        headers["v"] = "0"
      }
      key != null -> {
        headers["auth"] = Keychain.signHub(key)
        headers["v"] = "1"
      }
      else -> {
        headers["auth"] = Keychain.sign()
        headers["v"] = "0"
      }
    }
    deviceHeader()?.let { headers["device"] = it }
    return headers
  }

  /** [authHeaderMap] as the raw header lines the channel-kotlin client appends to the upgrade request. */
  internal fun authHeaders(): String = rawHeaders(authHeaderMap())

  private fun rawHeaders(headers: Map<String, String>): String =
    headers.entries.joinToString("\r\n") { (name, value) -> "$name: $value" }

  /**
   * On a v0 connection with no pinned key, asks `hub/info` for the hub key
   * and pins it. Returns true when a key was newly pinned.
   */
  private fun learnHubKey(c: Client<Any?>): Boolean {
    val pins = pins ?: return false
    if (hubKey != null) return false
    val result = runCatching { c.send(Any::class.java, "hub/info", null, Unit) }.getOrNull() ?: return false
    // hub-lite or an older hub-pro answers with an error: stay on v0.
    val key = ((result as? CallResult.Ok)?.value as? Map<*, *>)?.get("key") as? String ?: return false
    hubKey = key
    // An unwritable pin file keeps the key for this process only.
    runCatching { pins.put(addressKey, key) }
    return true
  }

  private fun reportConnectError(headers: Map<String, String>, error: Exception) {
    val message = generateSequence<Throwable>(error) { it.cause }.joinToString(": ") { it.message.toString() }
    // hub-pro refuses a bad token with HTTP 401 ("invalid websocket status 401" here); its reason
    // (invalid signature, key revoked) stays on the hub, so any failed v1 dial is reported once.
    if (headers["v"] == "1" && message != lastError) {
      System.err.println(
        "hub-service: v1 connect to $addressKey (hub key $hubKey) failed: $message. " +
          "If the hub key changed or a different server answers, call forgetHubKey().",
      )
    }
    lastError = message
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

  companion object {
    /**
     * Normalizes a hub address like channel TS does (protocol.md section 2): a
     * bare port becomes `ws://localhost:<port>`; a URL keeps its form with the
     * scheme and host lowercased and no trailing slash.
     */
    fun normalizeAddress(address: Any): String {
      if (address is Int) return "ws://localhost:$address"
      var text = address.toString().trim()
      if (text.isNotEmpty() && text.all { it.isDigit() }) return "ws://localhost:$text"
      val schemeEnd = text.indexOf("://")
      if (schemeEnd >= 0) {
        val authorityEnd = text.indexOf('/', schemeEnd + 3).let { if (it < 0) text.length else it }
        text = text.substring(0, authorityEnd).lowercase() + text.substring(authorityEnd)
      }
      return text.trimEnd('/')
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
