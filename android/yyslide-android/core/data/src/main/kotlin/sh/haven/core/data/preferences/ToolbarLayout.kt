package sh.haven.core.data.preferences

import org.json.JSONArray
import org.json.JSONObject

/**
 * A single item on the keyboard toolbar.
 *
 * [BuiltIn] references a [ToolbarKey] by ID — it gets special rendering
 * (icons for arrows, toggle state for modifiers, keyboard show/hide, etc.).
 *
 * [Custom] is a user-defined key with a display label and a string to send.
 * The [send] string is converted to UTF-8 bytes. Escape sequences use
 * standard JSON unicode escapes, e.g. `\u001b[A` for arrow-up.
 */
sealed class ToolbarItem {
    data class BuiltIn(val key: ToolbarKey) : ToolbarItem()
    data class Custom(val label: String, val send: String) : ToolbarItem()

    val displayLabel: String get() = when (this) {
        is BuiltIn -> key.label
        is Custom -> label
    }
}

/**
 * Complete toolbar layout: an ordered list of rows, each containing ordered items.
 *
 * JSON format:
 * ```json
 * [
 *   ["keyboard", "esc", "tab", "shift", "ctrl", "alt"],
 *   ["arrow_left", {"label": "|", "send": "|"}, {"label": "PgUp", "send": "\u001b[5~"}]
 * ]
 * ```
 *
 * - String elements reference built-in keys by ID (see [ToolbarKey.id]).
 * - Object elements define custom keys: `{"label": "...", "send": "..."}`.
 */
/** How the navigation keys (arrows, Home/End, PgUp/PgDn) are rendered on the toolbar. */
enum class NavBlockMode(val id: String, val label: String) {
    /** 2x4 aligned grid block (default). */
    ALIGNED("aligned", "Aligned block"),
    /** Inline with other keys — can be freely reordered. */
    INLINE("inline", "Inline keys");

    companion object {
        fun fromId(id: String): NavBlockMode? = entries.find { it.id == id }
    }
}

data class MacroPreset(val label: String, val send: String, val description: String)

val MACRO_PRESETS = listOf(
    MacroPreset("Paste", "PASTE", "Paste clipboard"),
    MacroPreset("^C", "\u0003", "Ctrl+C (interrupt)"),
    MacroPreset("^D", "\u0004", "Ctrl+D (EOF)"),
    MacroPreset("^Z", "\u001a", "Ctrl+Z (suspend)"),
    MacroPreset("^L", "\u000c", "Ctrl+L (clear)"),
    MacroPreset("^A", "\u0001", "Ctrl+A (tmux prefix)"),
    MacroPreset("^B", "\u0002", "Ctrl+B (tmux/screen prefix)"),
    MacroPreset("^R", "\u0012", "Ctrl+R (reverse search)"),
    MacroPreset("^W", "\u0017", "Ctrl+W (delete word)"),
    MacroPreset("^U", "\u0015", "Ctrl+U (delete line)"),
    MacroPreset("\u21e7Tab", "\u001b[Z", "Shift+Tab"),
    MacroPreset("C-A-Del", "\u001b[3;8~", "Ctrl+Alt+Delete"),
    MacroPreset("C-Del", "\u001b[3;6~", "Ctrl+Delete"),
    MacroPreset("C-Ins", "\u001b[2;5~", "Ctrl+Insert (copy)"),
    MacroPreset("S-Ins", "\u001b[2;2~", "Shift+Insert (paste)"),
)

data class ToolbarLayout(val rows: List<List<ToolbarItem>>) {

    val row1: List<ToolbarItem> get() = rows.getOrElse(0) { emptyList() }
    val row2: List<ToolbarItem> get() = rows.getOrElse(1) { emptyList() }

    fun toJson(): String {
        val arr = JSONArray()
        for (row in rows) {
            val rowArr = JSONArray()
            for (item in row) {
                when (item) {
                    is ToolbarItem.BuiltIn -> rowArr.put(item.key.id)
                    is ToolbarItem.Custom -> rowArr.put(JSONObject().apply {
                        put("label", item.label)
                        put("send", item.send)
                    })
                }
            }
            arr.put(rowArr)
        }
        return arr.toString(2)
    }

    companion object {
        val DEFAULT = ToolbarLayout(listOf(
            ToolbarKey.DEFAULT_ROW1.map { ToolbarItem.BuiltIn(it) },
            ToolbarKey.DEFAULT_ROW2.map { ToolbarItem.BuiltIn(it) },
        ))

        fun fromJson(json: String): ToolbarLayout {
            return try {
                val arr = JSONArray(json)
                val rows = (0 until arr.length()).map { i ->
                    val rowArr = arr.getJSONArray(i)
                    (0 until rowArr.length()).mapNotNull { j ->
                        when (val elem = rowArr.get(j)) {
                            is String -> ToolbarKey.fromId(elem)?.let { ToolbarItem.BuiltIn(it) }
                            is JSONObject -> {
                                val label = elem.optString("label", "")
                                val send = elem.optString("send", "")
                                if (label.isNotEmpty() && send.isNotEmpty()) {
                                    ToolbarItem.Custom(label, send)
                                } else null
                            }
                            else -> null
                        }
                    }
                }
                ToolbarLayout(rows)
            } catch (_: Exception) {
                DEFAULT
            }
        }

        /**
         * Migrate from legacy comma-separated row1/row2 format.
         */
        fun fromLegacy(row1: String, row2: String): ToolbarLayout {
            return ToolbarLayout(listOf(
                ToolbarKey.fromIdString(row1).map { ToolbarItem.BuiltIn(it) },
                ToolbarKey.fromIdString(row2).map { ToolbarItem.BuiltIn(it) },
            ))
        }

        /**
         * Validate JSON and return an error message, or null if valid.
         */
        fun validate(json: String): String? {
            return try {
                val arr = JSONArray(json)
                if (arr.length() == 0) return "Layout must have at least one row"
                for (i in 0 until arr.length()) {
                    val row = arr.optJSONArray(i)
                        ?: return "Row ${i + 1} is not an array"
                    for (j in 0 until row.length()) {
                        val elem = row.get(j)
                        when (elem) {
                            is String -> {
                                if (ToolbarKey.fromId(elem) == null) {
                                    return "Unknown key ID: \"$elem\""
                                }
                            }
                            is JSONObject -> {
                                if (!elem.has("label")) return "Custom key at row ${i + 1}, position ${j + 1} missing \"label\""
                                if (!elem.has("send")) return "Custom key at row ${i + 1}, position ${j + 1} missing \"send\""
                            }
                            else -> return "Invalid element at row ${i + 1}, position ${j + 1}"
                        }
                    }
                }
                null
            } catch (e: Exception) {
                "Invalid JSON: ${e.message}"
            }
        }
    }
}
