package dev.saltmotion.hook

import androidx.compose.ui.Modifier

/** Work around Steam 1.19.2 dropping MethodHookParam.setResult in its method advice. */
internal object CoverModifier {
    private val combined = Modifier::class.java.classLoader.loadClass("androidx.compose.ui.CombinedModifier")
    private val outer = combined.getDeclaredField("outer").apply { isAccessible = true }
    private val inner = combined.getDeclaredField("inner").apply { isAccessible = true }

    fun attach(result: Modifier, receiver: Modifier, addition: Modifier.Element, square: Boolean): Boolean {
        // These two verified host calls return receiver.then(new Element). Never edit the receiver
        // or a modifier which has already been exposed to Compose / retained by its caller.
        if (result.javaClass != combined || outer.get(result) !== receiver) return false
        val last = inner.get(result) as? Modifier ?: return false
        val expected = if (square) "androidx.compose.ui.draw.DrawWithCacheElement"
            else "androidx.compose.ui.graphics.BlockGraphicsLayerElement"
        if (last.javaClass.name != expected) return false
        inner.set(result, last.then(addition))
        return true
    }
}
