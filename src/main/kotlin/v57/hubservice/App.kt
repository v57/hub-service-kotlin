package v57.hubservice

import java.util.LinkedHashMap

/**
 * A hub app: a header plus a UI body of elements (TS app.ts parity).
 *
 * [body] is a list of plain strings (shorthand text) and/or element maps
 * built with the helpers in this file. The app path (header.path) serves the
 * AppInterface as a stream: first yielded value is the interface map with all
 * five keys always present — `header`, `body`, `top`, `bottom`, `data`.
 */
class App(
  val header: AppHeader,
  val body: List<Any?> = emptyList(),
  val top: Any? = null,
  val bottom: Any? = null,
  val data: Map<String, Any?> = linkedMapOf(),
) {
  /** The AppInterface map yielded by the app path stream (TS parity). */
  fun interfaceMap(): Map<String, Any?> = linkedMapOf(
    "header" to header.toMap(),
    "body" to body,
    "top" to top,
    "bottom" to bottom,
    "data" to data,
  )
}

// --- Element helpers (TS app.ts wire shapes) ---------------------------------

/** Plain shorthand text: just return the string. */
fun text(value: String): String = value

fun progress(value: String, min: Any? = null, max: Any? = null): Map<String, Any?> {
  val e = LinkedHashMap<String, Any?>()
  e["type"] = "progress"
  e["value"] = value
  if (min != null) e["min"] = min
  if (max != null) e["max"] = max
  return e
}

fun textField(value: String, placeholder: String? = null, action: Action? = null): Map<String, Any?> {
  val e = LinkedHashMap<String, Any?>()
  e["type"] = "textField"
  e["value"] = value
  if (placeholder != null) e["placeholder"] = placeholder
  if (action != null) e["action"] = action.toMap()
  return e
}

fun button(title: String, action: Action): Map<String, Any?> =
  linkedMapOf("type" to "button", "title" to title, "action" to action.toMap())

fun slider(
  value: String,
  defaultValue: Any? = null,
  min: Any? = null,
  max: Any? = null,
  step: Any? = null,
  action: Action? = null,
): Map<String, Any?> {
  val e = LinkedHashMap<String, Any?>()
  e["type"] = "slider"
  e["value"] = value
  if (defaultValue != null) e["defaultValue"] = defaultValue
  if (min != null) e["min"] = min
  if (max != null) e["max"] = max
  if (step != null) e["step"] = step
  if (action != null) e["action"] = action.toMap()
  return e
}

fun list(data: String, content: Any?): Map<String, Any?> =
  linkedMapOf("type" to "list", "data" to data, "content" to content)

fun picker(options: List<String>, selected: String): Map<String, Any?> =
  linkedMapOf("type" to "picker", "options" to options, "selected" to selected)

fun cell(title: Any? = null, subtitle: Any? = null): Map<String, Any?> {
  val e = LinkedHashMap<String, Any?>()
  e["type"] = "cell"
  if (title != null) e["title"] = title
  if (subtitle != null) e["subtitle"] = subtitle
  return e
}

fun files(value: String, title: Any? = null, action: Action): Map<String, Any?> {
  val e = LinkedHashMap<String, Any?>()
  e["type"] = "files"
  e["value"] = value
  e["action"] = action.toMap()
  if (title != null) e["title"] = title
  return e
}

fun fileOperation(format: String? = null, title: Any? = null, action: Action): Map<String, Any?> {
  val e = LinkedHashMap<String, Any?>()
  e["type"] = "fileOperation"
  e["action"] = action.toMap()
  if (format != null) e["format"] = format
  if (title != null) e["title"] = title
  return e
}

fun hstack(content: List<Any?>, spacing: Any? = null): Map<String, Any?> {
  val e = LinkedHashMap<String, Any?>()
  e["type"] = "hstack"
  e["content"] = content
  if (spacing != null) e["spacing"] = spacing
  return e
}

fun vstack(content: List<Any?>, spacing: Any? = null): Map<String, Any?> {
  val e = LinkedHashMap<String, Any?>()
  e["type"] = "vstack"
  e["content"] = content
  if (spacing != null) e["spacing"] = spacing
  return e
}

fun zstack(content: List<Any?>): Map<String, Any?> =
  linkedMapOf("type" to "zstack", "content" to content)

fun spacer(): Map<String, Any?> = linkedMapOf("type" to "spacer")
