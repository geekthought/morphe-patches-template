package dev.trueblack.firefox

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod

/** Acorn background/outline parameters verified against Firefox 158.0. */
private const val ACORN = "Lmozilla/components/compose/base/theme/AcornColorsKt;"
private const val ACORN_COLORS = "Lmozilla/components/compose/base/theme/AcornColors;"
private const val COLOR_SCHEME = "Landroidx/compose/material3/ColorSchemeKt;"
private const val BLACK = "Lmozilla/components/ui/colors/NovaColors;->Black:J"

private val darkSurfaceOffsets = intArrayOf(4, 14, 22, 26, 34, 38, 40, 48, 52, 54, 56, 62, 66, 68)
private val privateSurfaceOffsets = intArrayOf(4, 22, 34, 38, 40, 48, 52, 54, 56, 62, 66, 70)
private val extendedDarkPaletteOffsets = intArrayOf(7, 15, 23, 27, 29, 37)

private data class Callsite(val index: Int, val start: Int, val count: Int)

@Suppress("unused")
val firefoxAmoledBytecodePatch = bytecodePatch(
    name = "Firefox - Absolute AMOLED Black",
    description = "Force Firefox dark/private Acorn surfaces to #000000 without changing text or icon roles. Experimental across versions.",
    default = false,
) {
    compatibleWith(FIREFOX_COMPATIBILITY)
    dependsOn(firefoxAmoledResourcesPatch)

    execute {
        val acornClass = mutableClassDefBy(ACORN)

        fun findCallsite(
            methodName: String,
            targetClass: String,
            targetMethod: String,
            expectedWords: Int,
        ): Pair<MutableMethod, Callsite> {
            val methods = acornClass.methods.filter { it.name == methodName }
            if (methods.size != 1) {
                throw PatchException("AMOLED: expected one $methodName; found ${methods.size}")
            }
            val method = methods.single()
            val instructions = method.implementation?.instructions?.toList()
                ?: throw PatchException("AMOLED: $methodName implementation missing")
            val calls = instructions.mapIndexedNotNull { index, instruction ->
                val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    ?: return@mapIndexedNotNull null
                if (ref.definingClass != targetClass || !ref.name.startsWith(targetMethod)) {
                    return@mapIndexedNotNull null
                }
                if (instruction.opcode != Opcode.INVOKE_STATIC_RANGE &&
                    instruction.opcode != Opcode.INVOKE_DIRECT_RANGE) {
                    return@mapIndexedNotNull null
                }
                val range = instruction as? RegisterRangeInstruction
                    ?: return@mapIndexedNotNull null
                Callsite(index, range.startRegister, range.registerCount)
            }
            if (calls.size != 1) {
                throw PatchException("AMOLED: expected one $targetMethod in $methodName, found ${calls.size}")
            }
            val call = calls.single()
            if (call.count != expectedWords) {
                throw PatchException("AMOLED: $methodName ABI changed (${call.count} != $expectedWords)")
            }
            return method to call
        }

        fun overwrite(
            name: String, owner: String, callee: String,
            words: Int, offsets: IntArray,
        ) {
            val (method, call) = findCallsite(name, owner, callee, words)
            if (offsets.any { it < 0 || it + 1 >= call.count } ||
                offsets.toSet().size != offsets.size) {
                throw PatchException("AMOLED: invalid surface-register mapping")
            }
            val injection = offsets.joinToString("\n") { offset ->
                "sget-wide v${call.start + offset}, $BLACK"
            }
            method.addInstructions(call.index, injection)
            println("AMOLED $name: set ${offsets.size} backgrounds to black")
        }

        overwrite("acornDarkColorScheme", COLOR_SCHEME, "darkColorScheme", 74, darkSurfaceOffsets)
        overwrite("acornPrivateColorScheme", COLOR_SCHEME, "darkColorScheme", 74, privateSurfaceOffsets)
        overwrite("<clinit>", ACORN_COLORS, "<init>", 39, extendedDarkPaletteOffsets)
    }
}
