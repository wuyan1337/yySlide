package sh.haven.feature.editor

import android.content.Context
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import org.eclipse.tm4e.core.registry.IThemeSource
import java.io.ByteArrayInputStream

object TextMateSupport {

    @Volatile
    private var initialized = false

    // Standard 16 ANSI colors matching termlib's ColorCache.standardAnsiColor()
    private val ANSI = intArrayOf(
        0xFF000000.toInt(), // 0  Black
        0xFFCD0000.toInt(), // 1  Red
        0xFF00CD00.toInt(), // 2  Green
        0xFFCDCD00.toInt(), // 3  Yellow
        0xFF0000EE.toInt(), // 4  Blue
        0xFFCD00CD.toInt(), // 5  Magenta
        0xFF00CDCD.toInt(), // 6  Cyan
        0xFFE5E5E5.toInt(), // 7  White
        0xFF7F7F7F.toInt(), // 8  Bright Black (comment gray)
        0xFFFF0000.toInt(), // 9  Bright Red
        0xFF00FF00.toInt(), // 10 Bright Green
        0xFFFFFF00.toInt(), // 11 Bright Yellow
        0xFF5C5CFF.toInt(), // 12 Bright Blue
        0xFFFF00FF.toInt(), // 13 Bright Magenta
        0xFF00FFFF.toInt(), // 14 Bright Cyan
        0xFFFFFFFF.toInt(), // 15 Bright White
    )

    private val extensionToScope = mapOf(
        "java" to "source.java",
        "kt" to "source.kotlin",
        "kts" to "source.kotlin",
        "py" to "source.python",
        "pyw" to "source.python",
        "js" to "source.js",
        "mjs" to "source.js",
        "cjs" to "source.js",
        "jsx" to "source.js",
        "ts" to "source.ts",
        "tsx" to "source.ts",
        "mts" to "source.ts",
        "json" to "source.json",
        "jsonc" to "source.json",
        "xml" to "text.xml",
        "svg" to "text.xml",
        "plist" to "text.xml",
        "html" to "text.html.basic",
        "htm" to "text.html.basic",
        "xhtml" to "text.html.basic",
        "css" to "source.css",
        "scss" to "source.css",
        "sh" to "source.shell",
        "bash" to "source.shell",
        "zsh" to "source.shell",
        "fish" to "source.shell",
        "c" to "source.c",
        "h" to "source.c",
        "cpp" to "source.cpp",
        "cxx" to "source.cpp",
        "cc" to "source.cpp",
        "hpp" to "source.cpp",
        "hh" to "source.cpp",
        "md" to "text.html.markdown",
        "markdown" to "text.html.markdown",
        "yaml" to "source.yaml",
        "yml" to "source.yaml",
        "go" to "source.go",
        "rs" to "source.rust",
        "sql" to "source.sql",
        "dockerfile" to "source.dockerfile",
        "rb" to "source.ruby",
        "rake" to "source.ruby",
        "gemspec" to "source.ruby",
        "php" to "source.php",
        "gradle" to "source.groovy",
        "toml" to "source.yaml",
        "ini" to "source.yaml",
        "conf" to "source.yaml",
        "cfg" to "source.yaml",
        "properties" to "source.yaml",
    )

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            FileProviderRegistry.getInstance().addFileProvider(
                AssetsFileResolver(context.applicationContext.assets)
            )
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")
            initialized = true
        }
    }

    fun scopeForFileName(fileName: String): String? {
        if (fileName.lowercase() == "dockerfile") return "source.dockerfile"
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return extensionToScope[ext]
    }

    fun applyLanguage(editor: CodeEditor, fileName: String) {
        val scope = scopeForFileName(fileName) ?: return
        try {
            val language = TextMateLanguage.create(scope, true)
            editor.setEditorLanguage(language)
        } catch (_: Exception) {
        }
    }

    fun applyTheme(editor: CodeEditor, background: Int, foreground: Int) {
        try {
            val json = buildTerminalTheme(background, foreground)
            val stream = ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
            val themeSource = IThemeSource.fromInputStream(stream, "terminal.json", null)
            val themeModel = ThemeModel(themeSource)
            val scheme = TextMateColorScheme.create(themeModel)
            editor.colorScheme = scheme
        } catch (_: Exception) {
        }
    }

    private fun hex(argb: Int): String {
        return "#%06X".format(argb and 0xFFFFFF)
    }

    private fun buildTerminalTheme(bg: Int, fg: Int): String {
        val isDark = brightness(bg) < 128
        val commentColor = hex(ANSI[8])        // Bright Black (gray)
        val red = hex(ANSI[1])                 // Red → keywords
        val green = hex(ANSI[2])               // Green → strings
        val yellow = hex(ANSI[3])              // Yellow → types, annotations
        val blue = hex(ANSI[4])                // Blue → functions
        val magenta = hex(ANSI[5])             // Magenta → constants, numbers
        val cyan = hex(ANSI[6])                // Cyan → variables, tags
        val brightRed = hex(ANSI[9])           // Bright Red → errors, invalid
        val brightGreen = hex(ANSI[10])        // Bright Green → inserted
        val brightYellow = hex(ANSI[11])       // Bright Yellow → modified
        val brightBlue = hex(ANSI[12])         // Bright Blue → links
        val brightCyan = hex(ANSI[14])         // Bright Cyan → regex, escape

        val lineHighlight = if (isDark) {
            "#%06X".format((fg and 0xFFFFFF) or 0x0A000000) // fg at ~4% opacity
                .let { "#${Integer.toHexString(((fg and 0xFFFFFF) ushr 16 and 0xFF) / 16)}${Integer.toHexString(((fg and 0xFFFFFF) ushr 8 and 0xFF) / 16)}${Integer.toHexString((fg and 0xFF) / 16)}" }
                .let { blendColor(bg, fg, 0.06f) }
        } else {
            blendColor(bg, fg, 0.06f)
        }

        val selectionBg = blendColor(bg, fg, 0.20f)
        val lineNumColor = blendColor(bg, fg, 0.35f)

        return """
{
  "name": "Terminal",
  "type": "${if (isDark) "dark" else "light"}",
  "colors": {
    "editor.background": "${hex(bg)}",
    "editor.foreground": "${hex(fg)}",
    "editorLineNumber.foreground": "$lineNumColor",
    "editor.selectionBackground": "$selectionBg",
    "editor.lineHighlightBackground": "$lineHighlight"
  },
  "tokenColors": [
    { "settings": { "foreground": "${hex(fg)}" } },
    {
      "scope": ["comment", "punctuation.definition.comment"],
      "settings": { "foreground": "$commentColor", "fontStyle": "italic" }
    },
    {
      "scope": ["keyword", "storage.type", "storage.modifier", "keyword.operator.new"],
      "settings": { "foreground": "$red" }
    },
    {
      "scope": ["keyword.control", "keyword.operator.expression"],
      "settings": { "foreground": "$red" }
    },
    {
      "scope": ["string", "string.quoted", "punctuation.definition.string"],
      "settings": { "foreground": "$green" }
    },
    {
      "scope": ["constant.numeric", "constant.language", "constant.character"],
      "settings": { "foreground": "$magenta" }
    },
    {
      "scope": ["entity.name.function", "support.function", "meta.function-call"],
      "settings": { "foreground": "$blue" }
    },
    {
      "scope": ["entity.name.type", "support.type", "support.class", "entity.name.class"],
      "settings": { "foreground": "$yellow" }
    },
    {
      "scope": ["entity.name.tag", "punctuation.definition.tag"],
      "settings": { "foreground": "$cyan" }
    },
    {
      "scope": ["entity.other.attribute-name"],
      "settings": { "foreground": "$yellow" }
    },
    {
      "scope": ["variable", "variable.other", "variable.parameter"],
      "settings": { "foreground": "$cyan" }
    },
    {
      "scope": ["variable.language"],
      "settings": { "foreground": "$red" }
    },
    {
      "scope": ["constant.character.escape", "string.regexp"],
      "settings": { "foreground": "$brightCyan" }
    },
    {
      "scope": ["meta.annotation", "storage.type.annotation"],
      "settings": { "foreground": "$yellow" }
    },
    {
      "scope": ["markup.heading"],
      "settings": { "foreground": "$blue", "fontStyle": "bold" }
    },
    {
      "scope": ["markup.bold"],
      "settings": { "fontStyle": "bold" }
    },
    {
      "scope": ["markup.italic"],
      "settings": { "fontStyle": "italic" }
    },
    {
      "scope": ["markup.inline.raw", "markup.fenced_code"],
      "settings": { "foreground": "$green" }
    },
    {
      "scope": ["markup.underline.link"],
      "settings": { "foreground": "$brightBlue" }
    },
    {
      "scope": ["invalid", "invalid.illegal"],
      "settings": { "foreground": "$brightRed" }
    },
    {
      "scope": ["markup.inserted"],
      "settings": { "foreground": "$brightGreen" }
    },
    {
      "scope": ["markup.deleted"],
      "settings": { "foreground": "$brightRed" }
    },
    {
      "scope": ["markup.changed"],
      "settings": { "foreground": "$brightYellow" }
    }
  ]
}
"""
    }

    private fun brightness(argb: Int): Int {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private fun blendColor(bg: Int, fg: Int, ratio: Float): String {
        val inv = 1f - ratio
        val r = (((bg ushr 16) and 0xFF) * inv + ((fg ushr 16) and 0xFF) * ratio).toInt()
        val g = (((bg ushr 8) and 0xFF) * inv + ((fg ushr 8) and 0xFF) * ratio).toInt()
        val b = ((bg and 0xFF) * inv + (fg and 0xFF) * ratio).toInt()
        return "#%02X%02X%02X".format(r, g, b)
    }
}
