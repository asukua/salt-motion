package dev.saltmotion.hook

import java.security.MessageDigest

/** Resource checks do not preload the class that the host must transform. */
internal object CoverTarget {
    const val CALLER = "androidx.compose.ui.\u0207"
    const val RESOURCE = "androidx/compose/ui/\u0207.class"
    const val SHA256 = "aabc550f0084b8f2d11d7088ceafa922983faedfedba6d7cca81cc0fcdebfc2d"
    val parameters = listOf("androidx.compose.ui.Modifier", "kotlin.jvm.functions.Function1")
    const val SQUARE_DESCRIPTOR = "(Landroidx/compose/ui/graphics/painter/Painter;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;Landroidx/compose/runtime/Composer;I)V"
    const val CIRCLE_DESCRIPTOR = "(Lcom/xuncorp/voxzen/ui/component/PlayerSheetState;Lkotlin/jvm/functions/Function0;Landroidx/compose/ui/graphics/painter/Painter;Landroidx/compose/animation/AnimatedContentScope;ZLandroidx/compose/runtime/Composer;I)Lkotlin/Unit;"
    const val SQUARE_BCI = 385
    const val CIRCLE_BCI = 560
    fun callsite(className: String, methodName: String, descriptor: String, offset: Int, square: Boolean): Boolean =
        className == CALLER && methodName == "\u037f" &&
            descriptor == (if (square) SQUARE_DESCRIPTOR else CIRCLE_DESCRIPTOR) &&
            offset == (if (square) SQUARE_BCI else CIRCLE_BCI)

    // StackFrame.descriptor needs RETAIN_CLASS_REFERENCE even when the class itself is not read.
    private val walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
    fun onCoverStack(square: Boolean): Boolean = walker.walk { frames ->
        frames.limit(40).anyMatch { callsite(it.className, it.methodName, it.descriptor, it.byteCodeIndex, square) }
    }
    fun verified(version: String, bytes: ByteArray): Boolean =
        version.substringBefore('-') == "1.19.2" &&
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == SHA256
}
