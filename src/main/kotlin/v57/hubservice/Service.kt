package v57.hubservice

import channel.CallResult
import channel.Channel
import channel.Client
import channel.ConnectOptions
import channel.Values
import java.util.LinkedHashSet
import kotlin.concurrent.thread

/**
 * Registers API endpoints on a hub (hub-server) and serves calls
 * forwarded by it; also lets this process call API through the hub.
 *
 * Port of hub-service (TS reference) with hub-server auth: on every (re)connect
 * the client attaches fresh auth headers (`auth: <signed token>` + `v`) so
 * the connection is keyed — owner keys get services ENABLED immediately,
 * non-owner keys register as pending host approval. The wire contract matches
 * the TS/Go reference — `hub/profile/update` and `hub/service/update` are re-sent
 * on every (re)connect (the hub drops services on disconnect).
 *
 * Auth v2 (Docs/Devices/protocol.md section 2): before every connect the service
 * asks `hub/info` on a short anonymous connection with a fresh nonce. Only an
 * answer with a valid proof of the key it claims gets a token, over that answer's
 * challenge; the first proven key is pinned in `hubs.json` beside the key file. A
 * hub that doesn't prove its key, or answers with another than the pinned one,
 * gets nothing and is reported ([lastError]) while the service keeps trying with
 * a growing delay, and calls wait. Call [forgetHubKey] when the hub key really
 * changed. Plain `ws://` beyond loopback, the overlay and the LAN isn't dialed
 * unless [allowPlaintext] says so.
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
 * @param sign    token factory for hub-server auth, given the key the hub proved
 *                and the challenge of this connect (default: the keychain `auth`
 *                file in the cwd); a fresh token is signed for every (re)connect.
 * @param hubKey  the hub's SPKI if known up front (invitation or config);
 *                null = pin the first key a hub proves.
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
 * @param allowPlaintext dial plain `ws://` beyond loopback, the overlay and the
 *                LAN, which would carry the service's key unencrypted: for private
 *                networks the SDK can't recognize, such as a container's
 *                `ws://hub:1997`. Also `HUB_ALLOW_PLAINTEXT=1`.
 */
class Service(
  address: Any? = null,
  val name: String? = null,
  val icon: Any? = null,
  val sign: ((hubKey: String, challenge: String) -> String)? = null,
  hubKey: String? = null,
  device: Boolean = true,
  private val deviceKey: KeyFile? = null,
  private val deviceKeyPath: String? = null,
  private val instance: String? = null,
  private val deviceName: String? = null,
  private val platform: String? = null,
  private val parent: String? = null,
  allowPlaintext: Boolean = false,
) {
  private val channel = Channel<Any?>()
  private val addressValue: Any =
    (address as? String)?.trim()?.toIntOrNull() ?: address ?: System.getenv("HUB")?.toIntOrNull() ?: System.getenv("HUB") ?: 1997

  private val allowPlaintext = allowPlaintext || System.getenv("HUB_ALLOW_PLAINTEXT") == "1"

  /** Why [start] dialed nothing. */
  @Volatile
  private var refusal: String? = null

  internal val profile: Profile? = if (name != null || icon != null) Profile(name, icon) else null

  private val pins: HubPins = HubPins.beside(Keychain.keyPath())

  /** The hub key this address is pinned to, or null until a hub proved one. */
  @Volatile
  var hubKey: String? = hubKey ?: pins.get(normalizeAddress(addressValue))
    private set

  /** The last connect error (a hub that didn't prove its key, a connect it refused with HTTP 401), or null. */
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
   * Clears the pinned hub key for this address, so the next connect pins what
   * the hub proves. Use it when a hub's key changed on purpose.
   */
  fun forgetHubKey() {
    hubKey = null
    pins.remove(addressKey)
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

  /** Hub Network share publication: agent socket settings and the current publications. */
  val network = NetworkPublisher()

  /**
   * Declares a Hub Network share before [start]: the local `host:port` is
   * published under [shareName] on connect and after every reconnect.
   * [policy] is `mapOf("mode" to "hub" | "private" | "disabled")`,
   * `mapOf("mode" to "group", "group" to ..)` or `mapOf("mode" to "keys",
   * "keys" to listOf(..))`; null means private.
   */
  fun share(shareName: String, protocol: String, host: String, port: Int, policy: Map<String, Any?>? = null): Service {
    network.declare(NetworkPublisher.Share(shareName, protocol, host, port, policy ?: PRIVATE))
    return this
  }

  /**
   * Publishes a Hub Network share now (TS `publishShare`): the local hub-net
   * agent attests the port, then the declared set goes to the hub. Returns
   * every current publication. Requires [start].
   */
  fun publishShare(
    shareName: String,
    protocol: String,
    host: String,
    port: Int,
    policy: Map<String, Any?>? = null,
  ): List<Map<String, Any?>> =
    network.publish(NetworkPublisher.Share(shareName, protocol, host, port, policy ?: PRIVATE), shareKey(), ::replaceShares)

  /** Changes a published share's [policy] and/or [port] (null keeps the current one) and republishes. */
  fun updateShare(shareName: String, policy: Map<String, Any?>? = null, port: Int? = null): List<Map<String, Any?>> {
    val current = network.declaredShares.firstOrNull { it.name == shareName }
      ?: throw IllegalArgumentException("Share '$shareName' not found")
    return network.publish(current.copy(policy = policy ?: current.policy, port = port ?: current.port), shareKey(), ::replaceShares)
  }

  /** Removes a share and republishes the rest. */
  fun removeShare(shareName: String): List<Map<String, Any?>> = network.remove(shareName, shareKey(), ::replaceShares)

  private fun shareKey(): KeyFile {
    check(sign == null) { "Share publication needs the keychain service key, not a custom sign" }
    return Keychain.key()
  }

  private fun replaceShares(body: Any?): Any? = send("network/shares/replace", body)

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
   * Calls an API through the hub (client side). While the service is started and
   * not connected yet the call waits. Returns the decoded response, or throws
   * [IllegalStateException] if the service is not started and [ChannelException]
   * if the hub reports a remote error.
   */
  fun send(path: String, body: Any? = null): Any? {
    val c = connectedClient()
    return when (val result = c.send(Any::class.java, path, body, Unit)) {
      is CallResult.Ok -> result.value
      is CallResult.RemoteErr -> throw ChannelException(result.error)
    }
  }

  /** Opens a stream call through the hub (client side). */
  fun values(path: String, body: Any? = null): Values<Any> {
    val c = connectedClient()
    return c.values(Any::class.java, path, body, Unit)
  }

  /** The connection. While the service is started and has none yet, the caller waits for one. */
  private fun connectedClient(): Client<Any?> {
    while (true) {
      refusal?.let { throw IllegalStateException(it) }
      client?.let { return it }
      if (!started.get() || stopped) throw IllegalStateException("Service not started")
      Thread.sleep(10)
    }
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
   * on disconnect). An address that would carry the key unencrypted isn't dialed.
   */
  fun start(): Service {
    refusal = null
    if (!allowPlaintext && HubTransport.of(addressValue) == HubTransport.REFUSED) {
      val reason = "$addressKey would carry this service's key unencrypted. Use wss://, or set allowPlaintext " +
        "(HUB_ALLOW_PLAINTEXT=1) on a private network"
      refusal = reason
      System.err.println("hub-service: $reason")
      return this
    }
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

  /** Stops the poll thread and closes the connection. A handshake waiting to try again ends with it. */
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
        val headers = authHeaderMap() ?: return
        c = try {
          connectClient(headers)
        } catch (e: Exception) {
          if (stopped) return
          reportConnectError(e)
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
        sendUpdates()
      } else if (!connected) {
        wasConnected = false
      }
      Thread.sleep(50)
    }
  }

  private fun connectClient(headers: Map<String, String>): Client<Any?> {
    val options = ConnectOptions<Any?>(headers = rawHeaders(headers), connectTimeoutMillis = CONNECT_TIMEOUT_MS)
    return when (val a = addressValue) {
      is Int -> channel.connect(a, options)
      is String -> channel.connect(a, options)
      else -> throw IllegalArgumentException("address must be an Int port or a ws:// String URL")
    }
  }

  /**
   * Runs before every connection attempt (protocol.md section 2). Comes back with
   * a token over a fresh challenge from a hub that proved its key; until one has,
   * it holds the attempt back, so nothing goes out and calls keep waiting. Null
   * once the service is stopped.
   */
  private fun authHeaderMap(): Map<String, String>? {
    var delay = HANDSHAKE_RETRY_MS
    while (!stopped) {
      try {
        return handshake()
      } catch (e: Exception) {
        if (stopped) return null
        report("$addressKey: ${e.message}, sending it nothing")
      }
      Thread.sleep(delay)
      delay = minOf(delay * 2, HANDSHAKE_MAX_RETRY_MS)
    }
    return null
  }

  /** Asks the hub for its proof, checks it against the pin and signs for that answer alone. */
  private fun handshake(): Map<String, String> {
    val info = HubProof.ask(addressValue, HUB_INFO_TIMEOUT_MS)
    val key = info.key!!
    val pinned = hubKey ?: pins.get(addressKey)
    check(pinned == null || pinned == key) {
      "it answers with key $key, not the pinned $pinned. Call forgetHubKey() if its key really changed"
    }
    hubKey = key
    if (pinned == null) {
      try {
        pins.put(addressKey, key)
      } catch (e: Exception) {
        // An unwritable pin file keeps the key for this process only.
        System.err.println("hub-service: pin hub key: ${e.message}")
      }
    }
    return signedHeaders(key, info.challenge!!)
  }

  /** The headers for one connect: a token over [challenge] and the device certificate. */
  internal fun signedHeaders(key: String, challenge: String): Map<String, String> {
    val headers = linkedMapOf<String, String>()
    headers["auth"] = sign?.invoke(key, challenge) ?: Keychain.signHub(key, challenge)
    headers["v"] = Keychain.AUTH_VERSION
    deviceHeader()?.let { headers["device"] = it }
    return headers
  }

  /** [signedHeaders] as the raw header lines the channel-kotlin client appends to the upgrade request. */
  internal fun rawHeaders(headers: Map<String, String>): String =
    headers.entries.joinToString("\r\n") { (name, value) -> "$name: $value" }

  private fun reportConnectError(error: Exception) {
    val message = generateSequence<Throwable>(error) { it.cause }.joinToString(": ") { it.message.toString() }
    // hub-server refuses a bad token with HTTP 401 ("invalid websocket status 401" here); its reason
    // (invalid signature, key revoked) stays on the hub, so any failed dial is reported once.
    report(
      "connect to $addressKey (hub key $hubKey) failed: $message. " +
        "If the hub key changed or a different server answers, call forgetHubKey().",
    )
  }

  /** Records a failure, and reports it once per distinct message. */
  private fun report(message: String) {
    if (message != lastError) System.err.println("hub-service: $message")
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
    if (network.hasShares && sign == null) {
      try {
        network.republishAll(Keychain.key()) { body ->
          when (val result = c.send(Any::class.java, "network/shares/replace", body, Unit)) {
            is CallResult.Ok -> result.value
            is CallResult.RemoteErr -> throw ChannelException(result.error)
          }
        }
      } catch (e: Exception) {
        System.err.println("hub-service: failed to republish network shares: ${e.message}")
      }
    }
  }

  companion object {
    private val PRIVATE = mapOf<String, Any?>("mode" to "private")

    /** Bounds the `hub/info` question before a connect, and the connect. */
    private const val HUB_INFO_TIMEOUT_MS = 5_000L
    private const val CONNECT_TIMEOUT_MS = 5_000L

    /** Retry delays for a hub that doesn't prove its key, or can't be reached. */
    private const val HANDSHAKE_RETRY_MS = 1_000L
    private const val HANDSHAKE_MAX_RETRY_MS = 30_000L

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
