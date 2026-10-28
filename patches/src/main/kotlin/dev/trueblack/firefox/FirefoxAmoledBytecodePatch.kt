package dev.trueblack.firefox

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Port of the 32 *instruction-level* changes in the working Firefox 158.0 V3.
 *
 * Do NOT replace entire semantic palettes or shared NovaColors:
 * those contain foreground/text/icon roles. Only named surface assignments
 * before the corresponding constructor call are changed.
 *
 * Firefox 158.0 reference; 158.0.2 experimental; future ABI changes fail safely.
 */
private const val ACORN = "Lmozilla/components/compose/base/theme/AcornColorsKt;"
private const val PALETTE_CLASS = "Lmozilla/components/compose/base/theme/AcornColors;"
private const val MATERIAL3 = "Landroidx/compose/material3/ColorSchemeKt;"
private const val BLACK_FIELD = "Lmozilla/components/ui/colors/NovaColors;->Black:J"

// Registers are offsets from invoke-*/range start. Deliberately exclude the
// shared foreground roles and values that were MOVE_WIDE rather than SGET_WIDE.
private val darkFieldOffsets = intArrayOf(4, 14, 22, 26, 34, 38, 40, 48, 52, 54, 56, 62, 68)
private val privateFieldOffsets = intArrayOf(4, 22, 34, 38, 40, 48, 52, 56)
private val paletteDarkFieldOffsets = intArrayOf(7, 15, 23, 29, 37)

private val privateBackgroundLiterals = longArrayOf(
    0xff332752L, // outlineVariant
    0xff0d0321L, // surfaceDim
    0xff20163aL, // surfaceContainerHigh
    0xff11042bL, // surfaceContainerLow
)

private data class Call(val index: Int, val start: Int, val words: Int)

@Suppress("unused")
val firefoxAmoledBytecodePatch = bytecodePatch(
    name = "Firefox - Absolute AMOLED Black",
    description = "V3-equivalent Acorn background changes, retaining text and icon colors. Experimental for newer Firefox versions.",
    default = false,
) {
    compatibleWith(FIREFOX_COMPATIBILITY)
    dependsOn(firefoxAmoledResourcesPatch)

    execute {
        val clazz = mutableClassDefBy(ACORN)

        fun locate(
            methodName: String,
            owner: String,
            callee: String,
            words: Int,
            requiredStart: Int? = null,
        ): Pair<MutableMethod, Call> {
            val methods = clazz.methods.filter { it.name == methodName }
            if (methods.size != 1) {
                throw PatchException("AMOLED: method " + methodName + " not uniquely found")
            }
            val method = methods.single()
            val instructions = method.implementation?.instructions?.toList()
                ?: throw PatchException("AMOLED: no implementation for " + methodName)
            val calls = instructions.mapIndexedNotNull { index, insn ->
                val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
                    ?: return@mapIndexedNotNull null
                if (ref.definingClass != owner || !ref.name.startsWith(callee)) {
                    return@mapIndexedNotNull null
                }
                if (insn.opcode != Opcode.INVOKE_STATIC_RANGE &&
                    insn.opcode != Opcode.INVOKE_DIRECT_RANGE) return@mapIndexedNotNull null
                val range = insn as? RegisterRangeInstruction
                    ?: return@mapIndexedNotNull null
                Call(index, range.startRegister, range.registerCount)
            }.filter { it.words == words && (requiredStart == null || it.start == requiredStart) }
            if (calls.size != 1) {
                throw PatchException(
                    "AMOLED: cannot identify safe " + methodName + " call: " +
                    calls.size + " matching constructor calls"
                )
            }
            return method to calls.single()
        }

        fun patchFields(
            method: MutableMethod,
            call: Call,
            offsets: IntArray,
            label: String,
        ): Int {
            val instructions = method.implementation!!.instructions.toList()
            var changed = 0
            for (offset in offsets) {
                val reg = call.start + offset
                if (offset < 0 || offset + 1 >= call.words) {
                    throw PatchException("AMOLED: unexpected argument layout for " + label)
                }
                val matches = instructions.indices.filter { index ->
                    index < call.index &&
                    instructions[index].opcode == Opcode.SGET_WIDE &&
                    (instructions[index] as? OneRegisterInstruction)?.registerA == reg
                }
                if (matches.size != 1) {
                    throw PatchException(
                        "AMOLED: " + label + " register v" + reg +
                        " expected one sget-wide, found " + matches.size
                    )
                }
                method.replaceInstruction(matches.single(), "sget-wide v" + reg + ", " + BLACK_FIELD)
                changed++
            }
            return changed
        }

        fun patchMove(
            method: MutableMethod, call: Call,
            offset: Int, source: Int, label: String,
        ): Int {
            val destination = call.start + offset
            val instructions = method.implementation!!.instructions.toList()
            val matches = instructions.indices.filter { index ->
                val insn = instructions[index]
                index < call.index &&
                insn.opcode == Opcode.MOVE_WIDE_FROM16 &&
                (insn as? TwoRegisterInstruction)?.let {
                    it.registerA == destination && it.registerB == source
                } == true
            }
            if (matches.size != 1) {
                throw PatchException("AMOLED: missing " + label + " move into v" + destination)
            }
            method.replaceInstruction(matches.single(), "sget-wide v" + destination + ", " + BLACK_FIELD)
            return 1
        }

        val (dark, darkCall) = locate(
            "acornDarkColorScheme", MATERIAL3, "darkColorScheme", 74
        )
        val darkFields = patchFields(dark, darkCall, darkFieldOffsets, "dark scheme")
        val darkMove = patchMove(dark, darkCall, 66, 3, "surfaceContainerLow")

        val (priv, privCall) = locate(
            "acornPrivateColorScheme", MATERIAL3, "darkColorScheme", 74
        )
        val privFields = patchFields(priv, privCall, privateFieldOffsets, "private scheme")
        var privateLiterals = 0
        // V3 replaces original const-wide instructions, not just final call arguments.
        val privateInstructions = priv.implementation!!.instructions.toList()
        for (value in privateBackgroundLiterals) {
            val matches = privateInstructions.indices.filter { index ->
                val insn = privateInstructions[index]
                index < privCall.index &&
                insn.opcode == Opcode.CONST_WIDE &&
                (insn as? WideLiteralInstruction)?.wideLiteral == value
            }
            if (matches.size != 1) {
                throw PatchException(
                    "AMOLED: private surface literal " + value.toString(16) +
                    " not found exactly once"
                )
            }
            val index = matches.single()
            val register = (privateInstructions[index] as OneRegisterInstruction).registerA
            priv.replaceInstruction(index, "const-wide v" + register + ", 0xff000000L")
            privateLiterals++
        }

        // One dark palette and one light palette are constructed in <clinit>.
        // The first dark constructor is invoke-direct/range {v0 .. } with 39 words.
        val (staticInit, paletteCall) = locate(
            "<clinit>", PALETTE_CLASS, "<init>", 39, requiredStart = 0
        )
        val paletteFields = patchFields(
            staticInit, paletteCall, paletteDarkFieldOffsets, "dark extended palette"
        )
        val paletteMove = patchMove(
            staticInit, paletteCall, 27, 5, "surfaceDimVariant"
        )
        val count = darkFields + darkMove + privFields + privateLiterals +
                    paletteFields + paletteMove
        if (count != 32) {
            throw PatchException("AMOLED: expected 32 V3 instruction changes, found " + count)
        }
        println("Firefox AMOLED: applied 32 V3-equivalent Acorn instruction changes")
    }
}
