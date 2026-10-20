package v57.hubservice

/**
 * Who is calling, as the hub reports it in `context.hub.caller`
 * (Docs/Devices/protocol.md section 4). The hub replaces any `context.hub`
 * a client sent, and services accept requests only from the hub connection,
 * so handlers can trust it.
 *
 * @property key        the caller's service SPKI; null for anonymous callers
 * @property deviceId   the caller's device id; null unless its connection is device-attached
 * @property deviceName the caller's device name; null without a device
 * @property groups     every group the key or its device belongs to
 * @property owner      whether the caller is in the owners group
 * @property via        through a merged hub: the merged hub's SPKI, else null
 * @property claimed    through a merged hub: the merged hub's own (unverified) caller, else null
 * @property raw        the `caller` object as received
 */
data class Caller(
  val key: String?,
  val deviceId: String?,
  val deviceName: String?,
  val groups: List<String>,
  val owner: Boolean,
  val via: String? = null,
  val claimed: Caller? = null,
  val raw: Map<*, *> = emptyMap<String, Any?>(),
) {
  /** Whether the caller's connection is attached to a device. */
  val hasDevice: Boolean get() = deviceId != null

  /** Whether the request came through a merged hub (the caller is then the merging hub's connection). */
  val viaMergedHub: Boolean get() = via != null

  companion object {
    /** The caller in a request `context`, or null when the hub sent none (hub-lite, older hub-pro). */
    fun fromContext(context: Any?): Caller? = from(((context as? Map<*, *>)?.get("hub") as? Map<*, *>)?.get("caller"))

    /** Parses a `caller` object, or null if it isn't one. */
    fun from(value: Any?): Caller? {
      val map = value as? Map<*, *> ?: return null
      val device = map["device"] as? Map<*, *>
      return Caller(
        key = map["key"] as? String,
        deviceId = device?.get("id") as? String,
        deviceName = device?.get("name") as? String,
        groups = (map["groups"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
        owner = map["owner"] == true,
        via = map["via"] as? String,
        claimed = from(map["claimed"]),
        raw = map,
      )
    }

    /** The hub's SPKI from a request `context` (`context.hub.hubKey`), or null. */
    fun hubKey(context: Any?): String? = ((context as? Map<*, *>)?.get("hub") as? Map<*, *>)?.get("hubKey") as? String
  }
}

/**
 * A request handed to post and stream handlers registered with a
 * `{ body, request -> }` handler.
 *
 * @property path    the API path called
 * @property body    the decoded request body
 * @property caller  who is calling (`context.hub.caller`); null when the hub sent none or when its
 *                   `context.hub.hubKey` isn't the hub key this service pinned
 * @property hubKey  the forwarding hub's SPKI (`context.hub.hubKey`); null when the hub sent none
 * @property context the whole request context (selection keys such as `service` and `device` included)
 */
data class Request(
  val path: String,
  val body: Any?,
  val caller: Caller?,
  val hubKey: String?,
  val context: Map<*, *>,
) {
  companion object {
    /**
     * The request for [context]. The caller is trusted only when the context
     * names [pinnedHubKey] as its hub (TS parity), so a service without a
     * pinned key sees no caller.
     */
    internal fun of(path: String, body: Any?, context: Any?, pinnedHubKey: String?): Request {
      val map = context as? Map<*, *> ?: emptyMap<String, Any?>()
      val hubKey = Caller.hubKey(map)
      val caller = if (pinnedHubKey != null && pinnedHubKey == hubKey) Caller.fromContext(map) else null
      return Request(path, body, caller, hubKey, map)
    }
  }
}
