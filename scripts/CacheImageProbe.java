import dev.saltmotion.*;
import dev.saltmotion.am.*;
import dev.saltmotion.artwork.*;
import org.jetbrains.skia.*;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import java.nio.file.*;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.CRC32;

public final class CacheImageProbe {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root);
        var album=new AlbumKey("Bruno Mars","24K Magic");
        var settings=new MotionSettings(true,List.of("us"),"",30);
        PreparedMotion prepared;
        try(var runtime=new MotionRuntime(new PluginLog(null),settings,root.resolve("cache"))) {
            prepared=BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,(scope,c)->runtime.prepare(album,settings,c));
            if(prepared==null||!prepared.getCached()||prepared.getStream().getWidth()!=2160)throw new AssertionError("Expected cached maximum resolution");
        }
        try(var offline=new MotionRuntime(new PluginLog(null),settings,root.resolve("cache"))) {
            offline.getHttp().close();
            var hit=BuildersKt.<PreparedMotion>runBlocking(EmptyCoroutineContext.INSTANCE,(scope,c)->offline.prepare(album,settings,c));
            if(hit==null||!hit.getCached())throw new AssertionError("Offline restart lost cached album");
        }
        System.out.println("PASS one cached 2160 album; fresh runtime with closed network client resolves offline");
        var hashes=new HashSet<Long>(); Image retained=null;
        try(var frames=new NativeFrames(prepared.getStream().getUrl(),2160,2160);var surface=Surface.Companion.makeRasterN32Premul(486,486)) {
            long deadline=System.nanoTime()+20_000_000_000L;int n=0;
            while(n<15 && System.nanoTime()<deadline){var image=frames.readImage();if(image==null)continue;
                if(image.getWidth()!=2160||image.getHeight()!=2160)throw new AssertionError("Image dimensions reduced");
                surface.getCanvas().drawImageRect(image,Rect.makeWH(486,486));
                try(var snapshot=surface.makeImageSnapshot();var data=snapshot.encodeToData(EncodedImageFormat.PNG,100,6)){var crc=new CRC32();crc.update(data.getBytes());hashes.add(crc.getValue());}
                if(retained==null)retained=image;else image.close();n++;
            }
            if(n<15||hashes.size()<2)throw new AssertionError("No changing owned images");
        }
        try(var image=retained;var data=image.encodeToData(EncodedImageFormat.PNG,100,6)) {
            Files.write(root.resolve("retained-after-decoder-close.png"),data.getBytes());
        }
        System.out.println("PASS 15 owned 2160 images; pixels change; retained image survives later renders and decoder destruction");
    }
}
