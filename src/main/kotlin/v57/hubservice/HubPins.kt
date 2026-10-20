package v57.hubservice

import channel.defaultJsonCodec
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pinned hub keys (Docs/Devices/protocol.md section 2): the file `hubs.json`
 * beside the service key file, a JSON object `{"<address>": "<hub SPKI>"}`
 * keyed by the normalized address ([Service.normalizeAddress]). A service
 * learns a hub's key from `hub/info` on its first (v0) connection and signs
 * v1 tokens bound to it from then on.
 */
class HubPins(val path: Path) {
  /** The pinned key for [address], or null. */
  @Synchronized
  fun get(address: String): String? = read()[address] as? String

  /** Pins [hubKey] for [address]. */
  @Synchronized
  fun put(address: String, hubKey: String) {
    val pins = read()
    pins[address] = hubKey
    write(pins)
  }

  /** Forgets the pinned key for [address]. */
  @Synchronized
  fun remove(address: String) {
    val pins = read()
    if (pins.remove(address) != null) write(pins)
  }

  private fun read(): LinkedHashMap<String, Any?> {
    // A corrupt pin file counts as empty: the next hub/info pins again.
    val value = runCatching {
      if (Files.exists(path)) defaultJsonCodec().decode(Files.readString(path)) else null
    }.getOrNull()
    val pins = LinkedHashMap<String, Any?>()
    (value as? Map<*, *>)?.forEach { (k, v) -> if (k is String) pins[k] = v }
    return pins
  }

  private fun write(pins: Map<String, Any?>) {
    KeyFile.writeAtomic(path, defaultJsonCodec().encode(pins), secure = false)
  }

  companion object {
    /** Pins stored in `hubs.json` beside [keyPath]. */
    fun beside(keyPath: String): HubPins =
      HubPins(Path.of(keyPath).toAbsolutePath().parent.resolve("hubs.json"))
  }
}
