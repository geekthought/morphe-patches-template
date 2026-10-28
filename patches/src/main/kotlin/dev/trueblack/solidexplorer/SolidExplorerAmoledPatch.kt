package dev.trueblack.solidexplorer

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Solid Explorer 3.6.2 (APKPure XAPK, base package pl.solidexplorer2).
 *
 * Resource names were checked against the supplied original APK's
 * resources.arsc. This is an opt-in experiment; it is NOT a claim that
 * all Compose/runtime-generated surfaces will be black.
 *
 * Preserve names containing on_*, *text*, *icon*, *foreground*, *accent*,
 * *tint* etc. These can be essential for contrast and accessibility.
 */
private val solidExplorerCompat = Compatibility(
    name = "Solid Explorer",
    packageName = "pl.solidexplorer2",
    apkFileType = ApkFileType.XAPK,
    appIconColor = 0x111111,
    targets = listOf(AppTarget(version = "3.6.2", isExperimental = true)),
)

private val appDarkSurfaces = setOf(
    "almost_black",
    "almost_black_popups",
    "background_dark",
    "background_material_dark",
    "background_material_dark_darker",
    "background_material_dark_lighter",
    "background_floating_material_dark",
    "cardview_dark_background",
    "design_dark_default_color_background",
    "design_dark_default_color_surface",
    "design_snackbar_background_color",
    "divider_color_dark",
    "dialog_color_primary_dark",
    "material_grey_850",
    "material_grey_900",
)

// Semantic Material3 dark tokens only. Do not change Material3's shared
// neutral palettes: those are sometimes used for dark text in light mode.
private val materialDarkRoles = setOf(
    "background",
    "surface",
    "surface_dim",
    "surface_bright",
    "surface_variant",
    "inverse_surface",
    "surface_container",
    "surface_container_low",
    "surface_container_lowest",
    "surface_container_high",
    "surface_container_highest",
    "primary_container",
    "secondary_container",
    "tertiary_container",
    "error_container",
    "outline",
    "outline_variant",
)

private fun isDarkBackgroundName(name: String): Boolean {
    if (name in appDarkSurfaces) return true
    for (prefix in listOf("m3_sys_color_dark_", "m3_sys_color_dynamic_dark_")) {
        if (name.startsWith(prefix) && name.removePrefix(prefix) in materialDarkRoles) {
            return true
        }
    }
    return false
}

private val specificDarkDrawableNames = setOf(
    "bg_dialog_black",
    "bg_dialog_dark",
    "bg_popup_black",
    "bg_popup_dark",
    "bg_popup_material",
    "bg_dialog_material",
    "bg_bottom_sheet_material",
    "bg_drawer_material",
    "bg_drawer_header_material",
    "bg_drawer_item_material",
    "bg_breadcrumb_material",
    "bg_dataheader_dark",
    "color_background_dark",
    "color_background_dark_lighter",
    "divider_horizontal",
    "divider_vertical",
    "panel_divider",
)

// Solid Explorer's dark/black custom styles and Material3 dark variants.
private fun isDarkStyle(name: String): Boolean =
    name == "themeColorDark" ||
    name.startsWith("actionBarBlack") ||
    name.startsWith("toolbarBlack") ||
    name.startsWith("popupThemeBlack") ||
    name.startsWith("popupMenu.Black") ||
    name.startsWith("drawer.Black") ||
    name.startsWith("drawer.Dark") ||
    name.startsWith("bottomSheet.Black") ||
    name.startsWith("buttonBarDialog.BottomSheetBlack") ||
    name == "Theme.SE.Dark.ImageViewer" ||
    name == "Theme.SE.Material3.Dialog" ||
    name == "Theme.Material3.DynamicColors.Dark" ||
    name == "Theme.Material3Expressive.DynamicColors.Dark"

private val backgroundStyleAttrs = setOf(
    "android:windowBackground",
    "android:colorBackground",
    "android:colorBackgroundFloating",
    "android:statusBarColor",
    "android:navigationBarColor",
    "android:navigationBarDividerColor",
    "android:background",
    "android:popupBackground",
    "background",
    "backgroundSplit",
    "backgroundStacked",
    "colorBackgroundFloating",
    "colorSurface",
    "colorSurfaceDim",
    "colorSurfaceBright",
    "colorSurfaceVariant",
    "colorSurfaceContainer",
    "colorSurfaceContainerLow",
    "colorSurfaceContainerLowest",
    "colorSurfaceContainerHigh",
    "colorSurfaceContainerHighest",
    "colorOutline",
    "colorOutlineVariant",
    "colorDivider",
    "cardBackgroundColor",
    "dialogBackground",
)

private fun Node.elements(): Sequence<Element> = sequence {
    for (i in 0 until childNodes.length) {
        val item = childNodes.item(i)
        if (item is Element) yield(item)
    }
}

private fun Element.deepElements(): Sequence<Element> = sequence {
    yield(this@deepElements)
    for (element in elements()) yieldAll(element.deepElements())
}

@Suppress("unused")
val solidExplorerAbsoluteAmoledPatch = resourcePatch(
    name = "Solid Explorer - Absolute AMOLED Black",
    description = "Solid Explorer 3.6.2: pure-black dark-mode containers, toolbars, menus, drawers, cards and divider resources. Preserves text and icon colors; experimental for Compose runtime surfaces.",
    default = false,
) {
    compatibleWith(solidExplorerCompat)

    execute {
        val found = mutableSetOf<String>()
        var colorsChanged = 0
        for (path in decode("res/values*/colors.xml")) {
            document(path).use { doc ->
                val colorTags = doc.getElementsByTagName("color")
                for (i in 0 until colorTags.length) {
                    val el = colorTags.item(i) as? Element ?: continue
                    val name = el.getAttribute("name")
                    if (!isDarkBackgroundName(name)) continue
                    found.add(name)
                    if (el.textContent.trim() != "#ff000000") {
                        el.textContent = "#ff000000"
                        colorsChanged++
                    }
                }
                // Some color resources decompile as <item type="color">.
                val itemTags = doc.getElementsByTagName("item")
                for (i in 0 until itemTags.length) {
                    val el = itemTags.item(i) as? Element ?: continue
                    val name = el.getAttribute("name")
                    if (el.getAttribute("type") != "color" || !isDarkBackgroundName(name)) continue
                    found.add(name)
                    if (el.textContent.trim() != "#ff000000") {
                        el.textContent = "#ff000000"
                        colorsChanged++
                    }
                }
            }
        }

        val required = setOf(
            "almost_black",
            "almost_black_popups",
            "background_material_dark",
            "design_dark_default_color_surface",
            "m3_sys_color_dark_surface_container",
            "m3_sys_color_dynamic_dark_surface_container",
        )
        if (!found.containsAll(required)) {
            throw PatchException(
                "Solid AMOLED: resource layout changed; missing: " +
                    (required - found).joinToString()
            )
        }

        var stylesChanged = 0
        val seenStyles = mutableSetOf<String>()
        for (path in decode("res/values*/styles.xml")) {
            document(path).use { doc ->
                val styles = doc.getElementsByTagName("style")
                for (i in 0 until styles.length) {
                    val style = styles.item(i) as? Element ?: continue
                    val name = style.getAttribute("name")
                    if (!isDarkStyle(name)) continue
                    seenStyles.add(name)
                    for (child in style.elements()) {
                        if (child.tagName != "item") continue
                        if (child.getAttribute("name") !in backgroundStyleAttrs) continue
                        if (child.textContent.trim() == "@color/black") continue
                        child.textContent = "@color/black"
                        stylesChanged++
                    }
                }
            }
        }
        if ("actionBarBlack" !in seenStyles || "themeColorDark" !in seenStyles) {
            throw PatchException("Solid AMOLED: black toolbar / dark theme styles missing")
        }

        // Only shapes used for known dark surfaces. Never overwrite vector
        // icons, ripple tints, text selectors or arbitrary image resources.
        var drawablesChanged = 0
        for (path in decode(
            "res/drawable*/bg_*dark.xml",
            "res/drawable*/bg_*black.xml",
            "res/drawable*/bg_*material.xml",
            "res/drawable*/color_background_dark*.xml",
            "res/drawable*/divider_horizontal.xml",
            "res/drawable*/divider_vertical.xml",
            "res/drawable*/panel_divider.xml",
        )) {
            val name = path.substringAfterLast('/').removeSuffix(".xml")
            if (name !in specificDarkDrawableNames) continue
            document(path).use { doc ->
                val root = doc.documentElement ?: return@use
                if (root.tagName == "vector" || root.tagName == "animated-vector") return@use
                for (el in root.deepElements()) {
                    if (el.tagName !in setOf("solid", "stroke", "gradient")) continue
                    for (attr in listOf(
                        "android:color", "android:startColor",
                        "android:centerColor", "android:endColor"
                    )) {
                        val old = el.getAttribute(attr)
                        if (old.isNotEmpty() && old != "#ff000000") {
                            el.setAttribute(attr, "#ff000000")
                            drawablesChanged++
                        }
                    }
                }
            }
        }

        // Explicit color-selector XML files for dark backgrounds.
        for (path in decode("res/color/color_background_dark*.xml")) {
            document(path).use { doc ->
                val root = doc.documentElement ?: return@use
                for (el in root.deepElements()) {
                    val old = el.getAttribute("android:color")
                    if (old.isNotEmpty() && old != "#ff000000") {
                        el.setAttribute("android:color", "#ff000000")
                        drawablesChanged++
                    }
                }
            }
        }

        println(
            "Solid Explorer AMOLED: changed " + colorsChanged + " colors; " +
                stylesChanged + " dark style attributes; " +
                drawablesChanged + " dark drawable attributes"
        )
    }
}
