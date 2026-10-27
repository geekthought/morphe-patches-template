package dev.trueblack.firefox

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import org.w3c.dom.Node

private const val BLACK_HEX = "#000000"

internal val FIREFOX_COMPATIBILITY = Compatibility(
    name = "Firefox Android",
    packageName = "org.mozilla.firefox",
    apkFileType = ApkFileType.XAPK,
    appIconColor = 0xFF7139,
    targets = listOf(
        AppTarget(version = "158.0", isExperimental = true),
        AppTarget(version = null, isExperimental = true),
    ),
)

/**
 * Only surface-role colors. Never patch generic foreground/onSurface/text/icon/tint
 * resources: otherwise labels and controls might become invisible.
 * Original role names are based on Firefox 158.0. Fail closed if none is found.
 */
private val surfaceRoles = setOf(
    "background", "surface", "surface_bright", "surface_dim", "surface_inverse",
    "surface_variant", "inverse_surface", "surface_container", "surface_container_high",
    "surface_container_highest", "surface_container_low", "surface_container_lowest",
    "surface_container_selected", "primary_container", "secondary_container",
    "tertiary_container", "error_container", "information_container",
    "success_container", "warning_container", "outline", "outline_variant",
    "sheet_outline", "scrim", "layer_color_2", "layer_color_3",
    "layer_color_critical", "layer_color_warning", "splashscreen_background",
)
private val semanticPrefixes = listOf(
    "fx_mobile_oled_", "fx_mobile_private_", "m3_sys_color_dark_",
    "m3_sys_color_dynamic_dark_",
)
private val nightPrefixes = listOf("fx_mobile_")
private val sharedDarkOnly = setOf(
    "background_floating_material_dark", "background_material_dark",
    "cardview_dark_background", "design_dark_default_color_background",
    "design_dark_default_color_surface", "mozac_feature_readerview_dark_background",
)
private val nightAdditional = setOf(
    "design_snackbar_background_color",
    "mozac_additional_note_background",
    "mozac_feature_addons_messagebar_error_background_color",
    "mozac_feature_addons_messagebar_warning_background_color",
    "mozac_feature_search_widget_background_color",
)
private val v3BaseColors = setOf(
    "m3_ref_palette_grey10", "m3_ref_palette_grey20",
    "m3_ref_palette_grey_variant10", "m3_ref_palette_grey_variant20",
    "m3_ref_palette_neutral4", "m3_ref_palette_neutral6",
    "m3_ref_palette_neutral10", "m3_ref_palette_neutral12",
    "m3_ref_palette_neutral17", "m3_ref_palette_neutral20",
    "m3_ref_palette_neutral22", "m3_ref_palette_neutral24",
    "m3_ref_palette_neutral_variant10", "m3_ref_palette_neutral_variant20",
    "novaGray65", "novaGray70", "novaGray75", "novaGray85",
)
private val themedStyle = Regex("night|dark|private|oled|amoled", RegexOption.IGNORE_CASE)
private val surfaceThemeAttributes = setOf(
    "windowBackground", "colorBackground", "colorBackgroundFloating",
    "colorSurface", "colorSurfaceVariant", "colorSurfaceBright", "colorSurfaceDim",
    "colorSurfaceContainer", "colorSurfaceContainerHigh", "colorSurfaceContainerHighest",
    "colorSurfaceContainerLow", "colorSurfaceContainerLowest", "colorOutline",
    "colorOutlineVariant", "statusBarColor", "navigationBarColor",
    "navigationBarDividerColor", "background", "cardBackgroundColor", "divider",
    "listDivider", "popupBackground", "colorControlHighlight",
)
private fun isNight(path: String): Boolean =
    path.substringAfter("res/").substringBefore('/').split('-').contains("night")

private fun isSurfaceColor(name: String, night: Boolean): Boolean {
    if (name in sharedDarkOnly || name in v3BaseColors) return true
    if (night && name in nightAdditional) return true
    val prefix = semanticPrefixes.firstOrNull { name.startsWith(it) }
        ?: (if (night) nightPrefixes.firstOrNull { name.startsWith(it) } else null)
        ?: return false
    val role = name.removePrefix(prefix)
    return role in surfaceRoles
}

private fun Node.children(): Sequence<Element> = sequence {
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val child = nodes.item(i)
        if (child is Element) yield(child)
    }
}
private fun Element.deep(): Sequence<Element> = sequence {
    yield(this@deep)
    for (child in children()) yieldAll(child.deep())
}
private fun isColor(value: String): Boolean =
    value.startsWith("#") || value.startsWith("@color/") ||
    value.startsWith("@android:color/") || value.startsWith("?attr/") ||
    value.startsWith("?android:attr/")

@Suppress("unused")
val firefoxAmoledResourcesPatch = resourcePatch {
    compatibleWith(FIREFOX_COMPATIBILITY)
    execute {
        var changes = 0
        val matched = mutableSetOf<String>()

        for (path in decode("res/values*/colors.xml")) {
            val night = isNight(path)
            document(path).use { xml ->
                val colors = xml.getElementsByTagName("color")
                for (i in 0 until colors.length) {
                    val color = colors.item(i) as? Element ?: continue
                    val name = color.getAttribute("name")
                    if (!isSurfaceColor(name, night)) continue
                    matched += name
                    if (color.textContent.trim() != BLACK_HEX) {
                        color.textContent = BLACK_HEX
                        changes++
                    }
                }
            }
        }

        for (path in decode("res/values*/styles.xml", "res/values*/themes.xml")) {
            val night = isNight(path)
            document(path).use { xml ->
                val styles = xml.getElementsByTagName("style")
                for (i in 0 until styles.length) {
                    val style = styles.item(i) as? Element ?: continue
                    if (!night && !themedStyle.containsMatchIn(style.getAttribute("name"))) continue
                    for (item in style.children()) {
                        if (item.tagName != "item") continue
                        if (item.getAttribute("name").substringAfterLast(':') !in surfaceThemeAttributes) continue
                        val value = item.textContent.trim()
                        if (isColor(value) && value != BLACK_HEX) {
                            item.textContent = BLACK_HEX
                            changes++
                        }
                    }
                }
            }
        }

        // Deliberately avoid vector assets, text colors, icons and web content.
        for (path in decode(
            "res/drawable-night*/*.xml",
            "res/drawable*/background*.xml",
            "res/drawable*/*_background.xml",
            "res/drawable*/*_divider.xml",
            "res/drawable*/*_surface.xml",
        )) {
            if (!isNight(path) && !Regex("background|divider|surface", RegexOption.IGNORE_CASE)
                    .containsMatchIn(path.substringAfterLast('/'))) continue
            document(path).use { xml ->
                val root = xml.documentElement ?: return@use
                if (root.tagName == "vector" || root.tagName == "animated-vector") return@use
                for (el in root.deep()) {
                    if (el.tagName !in setOf("solid", "stroke", "gradient")) continue
                    for (attribute in listOf(
                        "android:color", "android:startColor", "android:centerColor", "android:endColor",
                    )) {
                        val old = el.getAttribute(attribute)
                        if (old.isNotEmpty() && isColor(old) && old != BLACK_HEX) {
                            el.setAttribute(attribute, BLACK_HEX)
                            changes++
                        }
                    }
                }
            }
        }

        for (path in decode("res/layout-night*/*.xml")) {
            document(path).use { xml ->
                val root = xml.documentElement ?: return@use
                for (el in root.deep()) {
                    for (attribute in listOf(
                        "android:background", "android:backgroundTint",
                        "app:cardBackgroundColor", "android:statusBarColor",
                        "android:navigationBarColor",
                    )) {
                        val old = el.getAttribute(attribute)
                        if (old.isNotEmpty() && isColor(old) && old != BLACK_HEX) {
                            el.setAttribute(attribute, BLACK_HEX)
                            changes++
                        }
                    }
                }
            }
        }

        if (matched.isEmpty()) {
            throw PatchException("Firefox AMOLED: no recognized surface tokens found; unsupported Firefox resource layout")
        }
        println("Firefox AMOLED: recolored $changes resources, matched ${matched.size} distinct surface names")
    }
}
