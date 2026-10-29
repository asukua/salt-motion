package dev.saltmotion.hook

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.net.JarURLConnection
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile

/** Host class bytes are inspected as resources; target classes are never preloaded before hooking. */
internal object CoverTarget {
    const val CALLER = "androidx.compose.ui.\u0207"
    const val RESOURCE = "androidx/compose/ui/\u0207.class"
    const val SHA256 = "aabc550f0084b8f2d11d7088ceafa922983faedfedba6d7cca81cc0fcdebfc2d"
    private const val NEXT_CALLER = "androidx.compose.ui.\u0b26"
    private const val NEXT_SHA256 = "c830630f48e4eb587db1b0f92c197d2a89d59c8f06abe73a570b2512704d3597"
    val parameters = listOf("androidx.compose.ui.Modifier", "kotlin.jvm.functions.Function1")
    const val SQUARE_DESCRIPTOR = "(Landroidx/compose/ui/graphics/painter/Painter;Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;Landroidx/compose/runtime/Composer;I)V"
    const val CIRCLE_DESCRIPTOR = "(Lcom/xuncorp/voxzen/ui/component/PlayerSheetState;Lkotlin/jvm/functions/Function0;Landroidx/compose/ui/graphics/painter/Painter;Landroidx/compose/animation/AnimatedContentScope;ZLandroidx/compose/runtime/Composer;I)Lkotlin/Unit;"
    const val SQUARE_BCI = 385
    const val CIRCLE_BCI = 560
    private const val MODIFIER_DESCRIPTOR = "(Landroidx/compose/ui/Modifier;Lkotlin/jvm/functions/Function1;)Landroidx/compose/ui/Modifier;"
    data class Match(val caller: String, val method: String = "\u037f")
    private val walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)

    fun callsite(className: String, methodName: String, descriptor: String, offset: Int, square: Boolean, target: Match = Match(CALLER)): Boolean =
        className == target.caller && methodName == target.method &&
            descriptor == (if (square) SQUARE_DESCRIPTOR else CIRCLE_DESCRIPTOR) &&
            offset == (if (square) SQUARE_BCI else CIRCLE_BCI)

    fun onCoverStack(square: Boolean, target: Match = Match(CALLER)): Boolean = walker.walk { frames ->
        frames.limit(40).anyMatch { callsite(it.className, it.methodName, it.descriptor, it.byteCodeIndex, square, target) }
    }

    // A changed version string alone does not invalidate unchanged bytecode.
    @Suppress("UNUSED_PARAMETER")
    fun verified(version: String, bytes: ByteArray): Boolean = hash(bytes) in setOf(SHA256, NEXT_SHA256)
    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun discover(loader: ClassLoader): Match? {
        for ((name, expected) in listOf(CALLER to SHA256, NEXT_CALLER to NEXT_SHA256)) {
            val data = loader.getResourceAsStream(name.replace('.', '/') + ".class")?.use { it.readNBytes(4_000_001) } ?: continue
            if (data.size <= 4_000_000 && hash(data) == expected) return Match(name)
        }
        // R8 can rename the same code in a later host. Probe the archive only if the
        // exact known fingerprints fail, and accept exactly one structurally verified match.
        val resource = loader.getResource("com/xuncorp/spw/workshop/api/WorkshopApi.class") ?: return null
        val archive = runCatching {
            val connection = resource.openConnection() as JarURLConnection
            Path.of(connection.jarFileURL.toURI())
        }.getOrNull() ?: return null
        return runCatching { discoverInArchive(archive) }.getOrNull()
    }

    fun discoverInArchive(archive: Path): Match? = JarFile(archive.toFile()).use { jar ->
        val found = mutableListOf<Match>()
        val entries = jar.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (!entry.name.startsWith("androidx/compose/ui/") || !entry.name.endsWith(".class") ||
                '$' in entry.name || entry.size !in 1..4_000_000) continue
            val bytes = jar.getInputStream(entry).use { it.readNBytes(4_000_001) }
            if (bytes.size > 4_000_000 || !contains(bytes, "FullScreenCover.kt")) continue
            val name = entry.name.removeSuffix(".class").replace('/', '.')
            if (structuralMatch(name, bytes)) {
                found += Match(name)
                if (found.size > 1) return@use null
            }
        }
        found.singleOrNull()
    }
    private fun contains(data: ByteArray, value: String): Boolean {
        val needle = value.toByteArray(Charsets.UTF_8)
        if (data.size < needle.size) return false
        return (0..data.size - needle.size).any { start -> needle.indices.all { data[start + it] == needle[it] } }
    }

    /** Require both exact business signatures and both expected modifier calls at verified offsets. */
    fun structuralMatch(name: String, bytes: ByteArray): Boolean = runCatching {
        if (!name.startsWith("androidx.compose.ui.") || '$' in name || !contains(bytes, "FullScreenCover.kt")) return@runCatching false
        val file = DataInputStream(ByteArrayInputStream(bytes))
        if (file.readInt() != 0xCAFEBABE.toInt()) return@runCatching false
        file.readUnsignedShort(); file.readUnsignedShort()
        val cp = arrayOfNulls<Any>(file.readUnsignedShort())
        var i = 1
        while (i < cp.size) {
            when (val tag = file.readUnsignedByte()) {
                1 -> cp[i] = file.readUTF()
                7, 8, 16, 19, 20 -> cp[i] = intArrayOf(tag, file.readUnsignedShort())
                9, 10, 11, 12, 17, 18 -> cp[i] = intArrayOf(tag, file.readUnsignedShort(), file.readUnsignedShort())
                3, 4 -> file.skipNBytes(4)
                5, 6 -> { file.skipNBytes(8); i++ }
                15 -> file.skipNBytes(3)
                else -> error("Unknown constant pool tag $tag")
            }
            i++
        }
        fun utf(index: Int) = cp[index] as String
        fun className(index: Int) = utf((cp[index] as IntArray)[1])
        fun ref(index: Int): Triple<String, String, String> {
            val method = cp[index] as IntArray
            require(method[0] == 10)
            val named = cp[method[2]] as IntArray
            require(named[0] == 12)
            return Triple(className(method[1]), utf(named[1]), utf(named[2]))
        }
        file.readUnsignedShort()
        if (className(file.readUnsignedShort()).replace('/', '.') != name) return@runCatching false
        file.readUnsignedShort()
        repeat(file.readUnsignedShort()) { file.readUnsignedShort() }
        fun skipMembers() {
            repeat(file.readUnsignedShort()) {
                file.skipNBytes(6)
                repeat(file.readUnsignedShort()) { file.readUnsignedShort(); file.skipNBytes(file.readInt().toLong()) }
            }
        }
        skipMembers()
        var square = false; var circle = false
        repeat(file.readUnsignedShort()) {
            file.readUnsignedShort(); val method = utf(file.readUnsignedShort()); val desc = utf(file.readUnsignedShort())
            repeat(file.readUnsignedShort()) {
                val attr = utf(file.readUnsignedShort()); val size = file.readInt(); require(size in 0..4_000_000)
                val data = file.readNBytes(size); require(data.size == size)
                if (method == "\u037f" && attr == "Code" && (desc == SQUARE_DESCRIPTOR || desc == CIRCLE_DESCRIPTOR)) {
                    val input = DataInputStream(ByteArrayInputStream(data)); input.skipNBytes(4)
                    val codeSize = input.readInt(); require(codeSize in 0..size)
                    val code = input.readNBytes(codeSize); require(code.size == codeSize)
                    val offset = if (desc == SQUARE_DESCRIPTOR) SQUARE_BCI else CIRCLE_BCI
                    require(offset + 2 < code.size && code[offset].toInt() and 255 == 0xb8)
                    val index = ((code[offset + 1].toInt() and 255) shl 8) or (code[offset + 2].toInt() and 255)
                    val owner = if (desc == SQUARE_DESCRIPTOR) "androidx/compose/ui/draw/DrawModifierKt" else "androidx/compose/ui/graphics/GraphicsLayerModifierKt"
                    val nameExpected = if (desc == SQUARE_DESCRIPTOR) "drawWithCache" else "graphicsLayer"
                    require(ref(index) == Triple(owner, nameExpected, MODIFIER_DESCRIPTOR))
                    if (desc == SQUARE_DESCRIPTOR) { require(!square); square = true } else { require(!circle); circle = true }
                }
            }
        }
        square && circle
    }.getOrDefault(false)
}
