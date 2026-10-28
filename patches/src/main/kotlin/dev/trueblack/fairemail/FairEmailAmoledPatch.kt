package dev.trueblack.fairemail

import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

/**
 * Pure black UI surfaces for FairEmail.
 *
 * Grounded in FairEmail upstream tag 1.2337:
 * https://github.com/M66B/FairEmail/tree/1.2337/app/src/main/res
 *
 * Apply a FairEmail "Black" theme in the app after patching.
 * Does not recolor text, icons, message HTML or app accent/semantic colors.
 */
private val fairEmailCompatibility = Compatibility(
    name = "FairEmail",
    packageName = "eu.faircode.email",
    appIconColor = 0x212121,
    targets = listOf(
        AppTarget(version = "1.2337a", isExperimental = true),
    )
)

// Explicit resource names from FairEmail 1.2337. Do not change shared
// @color/black, @color/white, *Foreground, *Accent or *Text names.
private val backgroundColors = setOf(
    "splashBackground",
    "darkColorItemSelected",
    "darkColorSeparator",
    "darkColorDrawerBackground",
    "darkColorCardBackground",
    "darkActionBackground",
    "blackPrimary",
    "blackPrimaryDark",
    "blackAndWhitePrimary",
    "blackAndWhitePrimaryDark",
    "blackAndWhitePrimaryLight",
    "design_snackbar_background_color",
    "colorWidgetBackground",
)

// The custom FairEmail theme attributes. Foreground, text, accent,
// warning, selection-ripple and accessibility state colors stay untouched.
private val blackThemeBackgroundAttrs = setOf(
    "android:windowBackground",
    "android:colorBackground",
    "android:statusBarColor",
    "android:navigationBarColor",
    "android:navigationBarDividerColor",
    "android:colorBackgroundFloating",
    "colorBackgroundFloating",
    "colorPrimary",
    "colorPrimaryDark",
    "colorDrawerBackground",
    "colorCardBackground",
    "colorActionBackground",
    "colorInfoBackground",
    "colorFabBackground",
    "colorSeparator",
)

// The dark base theme is also inherited by every black theme.
private val darkBaseBackgroundAttrs = setOf(
    "colorBackgroundFloating",
    "android:colorBackgroundFloating",
    "colorDrawerBackground",
    "colorCardBackground",
    "colorActionBackground",
    "colorSeparator",
)

private fun Element.setThemeItem(
    document: org.w3c.dom.Document,
    attr: String,
): Boolean {
    val childNodes = childNodes
    for (index in 0 until childNodes.length) {
        val element = childNodes.item(index) as? Element ?: continue
        if (element.tagName != "item" || element.getAttribute("name") != attr) continue
        if (element.textContent.trim() == "@color/black") return false
        element.textContent = "@color/black"
        return true
    }
    val item = document.createElement("item")
    item.setAttribute("name", attr)
    item.textContent = "@color/black"
    appendChild(item)
    return true
}

@Suppress("unused")
val fairEmailAbsoluteAmoledPatch = resourcePatch(
    name = "FairEmail - Absolute AMOLED Black",
    description = "Pure #000000 black for black-theme toolbars, cards, dialogs, menus, drawers, bars and separators; leave text/icons/accents unchanged. Requires selecting a Black theme in FairEmail.",
    default = false,
) {
    compatibleWith(fairEmailCompatibility)

    execute {
        var colorUpdates = 0
        val foundColors = mutableSetOf<String>()

        // Only exact named background surface roles, not broad grey/neutral
        // palettes that may be used for text, icons and state indicators.
        for (path in decode("res/values*/colors.xml")) {
            document(path).use { doc ->
                val entries = doc.getElementsByTagName("color")
                for (i in 0 until entries.length) {
                    val entry = entries.item(i) as? Element ?: continue
                    val name = entry.getAttribute("name")
                    if (name !in backgroundColors) continue
                    foundColors += name
                    if (entry.textContent.trim() != "#ff000000") {
                        entry.textContent = "#ff000000"
                        colorUpdates++
                    }
                }
            }
        }

        val requiredColors = setOf(
            "darkColorCardBackground", "darkColorDrawerBackground",
            "blackPrimary", "blackPrimaryDark",
        )
        if (!foundColors.containsAll(requiredColors)) {
            throw PatchException(
                "FairEmail AMOLED: expected colors missing: " +
                    (requiredColors - foundColors).joinToString()
            )
        }

        // FairEmail has several black themes, including Material You black
        // variants in values-v31/ and values-v34/. Each gets independent
        // overrides so colored parents cannot leak grey into the toolbar.
        var blackThemes = 0
        var styleUpdates = 0
        var darkBaseFound = false

        for (path in decode("res/values*/styles.xml")) {
            document(path).use { doc ->
                val styles = doc.getElementsByTagName("style")
                for (i in 0 until styles.length) {
                    val style = styles.item(i) as? Element ?: continue
                    val name = style.getAttribute("name")
                    val isBlackTheme =
                        name.startsWith("AppTheme") && name.contains("Black")
                    if (name == "AppThemeBaseDark") darkBaseFound = true
                    if (!isBlackTheme && name != "AppThemeBaseDark") continue

                    if (isBlackTheme) blackThemes++
                    val attrs = if (isBlackTheme)
                        blackThemeBackgroundAttrs else darkBaseBackgroundAttrs
                    for (attr in attrs) {
                        if (style.setThemeItem(doc, attr)) styleUpdates++
                    }
                }
            }
        }

        if (blackThemes < 2 || !darkBaseFound) {
            throw PatchException(
                "FairEmail AMOLED: black/dark theme styles not found " +
                    "(black=" + blackThemes + ", darkBase=" + darkBaseFound + ")"
            )
        }
        println(
            "FairEmail AMOLED: changed " + colorUpdates + " color resources and " +
                styleUpdates + " style items in " + blackThemes + " black themes"
        )
    }
}
