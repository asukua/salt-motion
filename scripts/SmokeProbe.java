import dev.saltmotion.MotionSettings;
import dev.saltmotion.PluginLog;
import dev.saltmotion.am.*;
import dev.saltmotion.artwork.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.jetbrains.skia.*;

/**
 * Isolated real-network/native probe. Does not attach to or modify the running music player.
 * Arguments: Apple album ID, album artist, album title, output directory.
 */
public class SmokeProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("albumId artist albumTitle outputDirectory");
        Path output = Path.of(args[3]);
        Files.createDirectories(output);
        var http = new AppleHttp();
        AppleTransport trace = (url, bearer, limit) -> {
            var response = http.get(url, bearer, limit);
            var uri = java.net.URI.create(url);
            System.out.println("http host=" + uri.getHost() + " path=" + uri.getPath() +
                " status=" + response.getStatus() + " characters=" + response.getBody().length());
            return response;
        };
        try (http; var catalog = new AlbumCatalog(trace, new PluginLog(null), System::currentTimeMillis)) {
            var key = new AlbumKey(args[1], args[2]);
            var automatic = catalog.resolve(key, new MotionSettings(true, List.of("us"), "", 15));
            System.out.println("automatic-match=" + (automatic == null ? "none" : automatic.getAlbumId()));
            var settings = new MotionSettings(true, List.of("us"), args[1] + " | " + args[2] + " = " + args[0], 15);
            var motion = catalog.resolve(key, settings);
            if (motion == null) throw new AssertionError("Production catalog did not resolve the specified album");
            var stream = HlsVariants.INSTANCE.resolveStream(motion.getVideoUrl(), http);
            System.out.println("resolved-album=" + motion.getAlbumId() + " highest-resolution=" + stream.getWidth()+"x"+stream.getHeight()+" codec="+stream.getCodec()+" bitrate="+stream.getBandwidth()+"");
            int total = 0;
            for (int cycle = 0; cycle < 3; cycle++) {
                var hashes = new HashSet<Integer>();
                int frames = 0;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                try (var decoder = new NativeFrames(stream.getUrl(),stream.getWidth(),stream.getHeight())) {
                    while (frames < 15 && System.nanoTime() < deadline) {
                        var frame = decoder.read();
                        if (frame != null) {
                            if (frame.getWidth() != stream.getWidth() || frame.getHeight() != stream.getHeight())
                                throw new AssertionError("Unexpected frame dimensions");
                            hashes.add(Arrays.hashCode(frame.getBgra()));
                            if (cycle == 0 && frames == 0) {
                                try (Image image = Image.Companion.makeRaster(new ImageInfo(frame.getWidth(), frame.getHeight(),
                                        ColorType.BGRA_8888, ColorAlphaType.OPAQUE), frame.getBgra(), frame.getWidth() * 4);
                                     Data png = image.encodeToData(EncodedImageFormat.PNG, 100, 6)) {
                                    if (png == null) throw new AssertionError("Skia raster encoding failed");
                                    Files.write(output.resolve("first-frame.png"), png.getBytes());
                                }
                            }
                            frames++;
                        }
                        Thread.sleep(100);
                    }
                }
                if (frames < 15 || hashes.size() < 2) throw new AssertionError("Motion did not produce changing frames");
                total += frames;
                System.out.println("cycle=" + cycle + " frames=" + frames + " distinctFrames=" + hashes.size() + " closed=true");
            }
            System.out.println("PASS decodedFrames=" + total + " real-raster-png=true");
            RenderLifecycleProbe.verify(stream);
        }
    }
}
