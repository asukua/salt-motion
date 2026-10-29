import java.nio.file.*;
import java.util.*;
import java.util.jar.Manifest;
import org.pf4j.*;

/** Loads the distributed package using PF4J and the player's trimmed runtime, not the build JDK. */
public class NetworkRuntimeProbe {
    public static void main(String[] args) throws Exception {
        if (ModuleLayer.boot().findModule("java.net.http").isPresent())
            throw new AssertionError("This regression probe must use the player's trimmed runtime");
        Path root = Path.of(args[0]).toAbsolutePath();
        Path probes = Path.of(args[1]).toAbsolutePath();
        Manifest manifest;
        try (var input = Files.newInputStream(root.resolve("META-INF/MANIFEST.MF"))) { manifest = new Manifest(input); }
        String version = manifest.getMainAttributes().getValue("Plugin-Version");
        var descriptor = new DefaultPluginDescriptor("dev.saltmotion", "", "dev.saltmotion.SaltMotionPlugin",
            version, "*", "asukua", "");
        try (var loader = new PluginClassLoader(new DefaultPluginManager(root.resolve("empty-plugins")),
                descriptor, NetworkRuntimeProbe.class.getClassLoader())) {
            loader.addURL(root.resolve("classes").toUri().toURL());
            try (var libs = Files.list(root.resolve("lib"))) {
                for (Path lib : libs.filter(path -> path.toString().endsWith(".jar")).toList())
                    loader.addURL(lib.toUri().toURL());
            }
            loader.addURL(probes.toUri().toURL());
            Class<?> client = loader.loadClass("dev.saltmotion.am.AppleHttp");
            if (client.getClassLoader() != loader) throw new AssertionError("HTTP client not loaded by PF4J");
            Class<?> backend = loader.loadClass("okhttp3.OkHttpClient");
            if (backend.getClassLoader() != loader) throw new AssertionError("HTTP backend must come from the package");
            if (loader.loadClass("okio.Buffer").getClassLoader() != loader)
                throw new AssertionError("I/O dependency must come from the package");
            try (var instance = (AutoCloseable) client.getConstructor().newInstance()) {
                System.out.println("PASS packaged HTTP client initialized and stopped under PF4J; java.net.http absent; version=" + version);
            }
            Class<?> logType = loader.loadClass("dev.saltmotion.PluginLog");
            Class<?> settingsType = loader.loadClass("dev.saltmotion.MotionSettings");
            Class<?> runtimeType = loader.loadClass("dev.saltmotion.MotionRuntime");
            Object log = logType.getConstructor(Path.class).newInstance((Object) null);
            Object settings = settingsType.getConstructor().newInstance();
            try (var runtime = (AutoCloseable) runtimeType.getConstructor(logType, settingsType).newInstance(log, settings)) {
                CoverRuntimeProbe.verify(loader, runtime);
                System.out.println("PASS MotionRuntime startup construction and shutdown under packaged PF4J dependencies");
            }
            loader.loadClass("CoverDiscoveryProbe").getMethod("verify",ClassLoader.class,Path.class).invoke(null,loader,probes.resolve("cover-discovery"));
            loader.loadClass("CoverEditorProbe").getMethod("main",String[].class).invoke(null,(Object)new String[]{probes.resolve("editor-resolution").toString()});
            if (args.length > 2 && args[2].equals("online")) {
                Class<?> smoke = loader.loadClass("SmokeProbe");
                if (smoke.getClassLoader() != loader) throw new AssertionError("Smoke probe must share plugin isolation");
                smoke.getMethod("main", String[].class).invoke(null, (Object) new String[]{
                    "1524793738", "Taylor Swift", "folklore", args[3],
                });
            }
        }
    }
}
