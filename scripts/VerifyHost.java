import dev.saltmotion.hook.CoverTarget;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.lang.reflect.*;
import java.util.jar.JarFile;

/** Exercise the installed host's real ordinary-method Hook resolver, in an isolated JVM. */
public class VerifyHost {
    public static void main(String[] args) throws Exception {
        try (var jar = new JarFile(Path.of(args[0]).toFile())) {
            byte[] cover;
            try (var in = jar.getInputStream(jar.getJarEntry(CoverTarget.RESOURCE))) { cover = in.readAllBytes(); }
            if (!CoverTarget.INSTANCE.verified("1.19.2", cover)) throw new AssertionError("Unverified native cover");
            int matches = 0;
            for (var entry : Collections.list(jar.entries())) {
                if (!entry.getName().startsWith("com/xuncorp/voxzen/workshop/hooks/") ||
                    !entry.getName().endsWith(".class") || entry.getName().contains("$")) continue;
                byte[] bytes;
                try (var input = jar.getInputStream(entry)) { bytes = input.readAllBytes(); }
                if (!new String(bytes, StandardCharsets.ISO_8859_1).contains("WorkshopHookTargets.kt")) continue;
                Class<?> resolver = Class.forName(entry.getName().replace('/', '.').replace(".class", ""));
                Method resolve = Arrays.stream(resolver.getDeclaredMethods()).filter(m ->
                    Arrays.equals(m.getParameterTypes(), new Class<?>[]{String.class, String.class, List.class}))
                    .findFirst().orElseThrow();
                var singleton = Arrays.stream(resolver.getDeclaredFields()).filter(f ->
                    Modifier.isStatic(f.getModifiers()) && f.getType() == resolver).findFirst().orElseThrow();
                singleton.setAccessible(true); resolve.setAccessible(true);
                for (var binding : List.of(
                    List.of("androidx.compose.ui.draw.DrawModifierKt", "drawWithCache"),
                    List.of("androidx.compose.ui.graphics.GraphicsLayerModifierKt", "graphicsLayer"))) {
                    Object target = resolve.invoke(singleton.get(null), binding.get(0), binding.get(1),
                        CoverTarget.INSTANCE.getParameters());
                    Method selected = Arrays.stream(target.getClass().getDeclaredMethods()).filter(m ->
                        m.getParameterCount() == 0 && m.getReturnType() == Method.class).findFirst().orElseThrow();
                    selected.setAccessible(true);
                    Method actual = (Method) selected.invoke(target);
                    if (!actual.getName().equals(binding.get(1)) || actual.getParameterCount() != 2 ||
                        !actual.getReturnType().getName().equals("androidx.compose.ui.Modifier"))
                        throw new AssertionError("Wrong native Modifier overload");
                    matches++;
                    System.out.println("Host resolver accepted " + binding.get(0) + "." + binding.get(1));
                }
            }
            if (matches != 2) throw new AssertionError("Expected two native method bindings: " + matches);
            System.out.println("PASS cover class SHA-256 and real native method resolution");
        }
    }
}
