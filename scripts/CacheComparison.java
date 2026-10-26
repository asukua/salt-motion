import dev.saltmotion.*;
import dev.saltmotion.am.*;
import dev.saltmotion.artwork.*;
import org.jetbrains.skia.*;
import okhttp3.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/** Compare an unchanged public motion video online and cached locally, using packaged production code. */
public final class CacheComparison {
    static final long MAX_ASSET=200L*1024*1024;
    static Path cache(String remote,AppleHttp http,Path directory) throws Exception {
        var body=http.get(remote,null,262144).getBody();
        var base=URI.create(remote);
        var assets=new LinkedHashMap<String,String>();
        var lines=new ArrayList<String>();
        var attr=Pattern.compile("URI=\"([^\"]+)\"");
        for(String raw:body.lines().toList()) {
            String line=raw.trim();
            if(line.startsWith("#EXT-X-KEY") && !line.contains("METHOD=NONE"))throw new IllegalStateException("Encrypted asset not supported by this probe");
            var match=attr.matcher(line);var rewritten=new StringBuilder();
            while(match.find()) {
                String url=base.resolve(match.group(1)).toString();
                String name=assets.computeIfAbsent(url,u->"asset-"+assets.size()+".mp4");
                match.appendReplacement(rewritten,"URI=\""+name+"\"");
            }
            match.appendTail(rewritten);line=rewritten.toString();
            if(!line.isEmpty()&&!line.startsWith("#")) {
                String url=base.resolve(line).toString();
                line=assets.computeIfAbsent(url,u->"asset-"+assets.size()+".mp4");
            }
            lines.add(line);
        }
        var client=new OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS).followRedirects(false).build();
        try {
            for(var asset:assets.entrySet()) {
                if(!AppleHttp.Companion.allowed(URI.create(asset.getKey())))throw new IllegalStateException("Unexpected CDN");
                Path target=directory.resolve(asset.getValue());
                var request=new Request.Builder().url(asset.getKey()).header("Origin","https://music.apple.com").header("Referer","https://music.apple.com/").build();
                try(var response=client.newCall(request).execute()) {
                    if(response.code()!=200 || response.body()==null)throw new IllegalStateException("Cache HTTP "+response.code());
                    long total=0;
                    try(var in=response.body().byteStream();var out=Files.newOutputStream(target)) {
                        byte[] buffer=new byte[65536];int n;
                        while((n=in.read(buffer))!=-1) {total+=n;if(total>MAX_ASSET)throw new IllegalStateException("Cache size bound exceeded");out.write(buffer,0,n);}
                    }
                    System.out.println("CACHED asset="+target.getFileName()+" bytes="+total);
                }
            }
        }finally{client.connectionPool().evictAll();client.dispatcher().executorService().shutdown();}
        Path playlist=directory.resolve("cached.m3u8");Files.writeString(playlist,String.join("\n",lines)+"\n");return playlist;
    }

    static String measure(String label,String url,HlsVariants.Stream stream) throws Exception {
        try(var decoder=new NativeFrames(url,stream.getWidth(),stream.getHeight());var surface=Surface.Companion.makeRasterN32Premul(486,486)) {
            long firstDeadline=System.nanoTime()+20_000_000_000L;
            while(decoder.read()==null)if(System.nanoTime()>firstDeadline)throw new IllegalStateException("No first frame");
            long warmEnd=System.nanoTime()+4_000_000_000L;
            while(System.nanoTime()<warmEnd) {
                var f=decoder.read();if(f!=null)try(var image=Image.Companion.makeRaster(new ImageInfo(f.getWidth(),f.getHeight(),ColorType.BGRA_8888,ColorAlphaType.OPAQUE),f.getBgra(),f.getWidth()*4)){surface.getCanvas().drawImageRect(image,Rect.makeWH(486,486));}
            }
            long started=System.nanoTime(),end=started+8_000_000_000L,readNs=0,imageNs=0,drawNs=0,hashNs=0,waitNs=0;
            int frames=0,changed=0,empty=0;long previous=-1;
            var schedule=new FrameSchedule(30,started);
            while(System.nanoTime()<end) {
                long t=System.nanoTime();var frame=decoder.read();readNs+=System.nanoTime()-t;
                if(frame==null){empty++;continue;}
                long remaining=schedule.delayNanos(System.nanoTime());
                if(remaining>0){t=System.nanoTime();Thread.sleep((remaining+999999)/1000000);waitNs+=System.nanoTime()-t;}
                if(frame.getWidth()!=stream.getWidth()||frame.getHeight()!=stream.getHeight())throw new AssertionError("Unexpected dimensions");
                t=System.nanoTime();var crc=new CRC32();crc.update(frame.getBgra());long hash=crc.getValue();if(hash!=previous)changed++;previous=hash;hashNs+=System.nanoTime()-t;
                t=System.nanoTime();var image=Image.Companion.makeRaster(new ImageInfo(frame.getWidth(),frame.getHeight(),ColorType.BGRA_8888,ColorAlphaType.OPAQUE),frame.getBgra(),frame.getWidth()*4);imageNs+=System.nanoTime()-t;
                t=System.nanoTime();try(image){surface.getCanvas().drawImageRect(image,Rect.makeWH(486,486));}drawNs+=System.nanoTime()-t;frames++;
            }
            double elapsed=(System.nanoTime()-started)/1e9;
            if(frames<2||changed<2)throw new AssertionError("No changing frames");
            String result=String.format(Locale.ROOT,"RESULT mode=%s frames=%d changed=%d seconds=%.3f fps=%.2f nativeReadMs=%.2f imageCreateMs=%.2f rasterDrawCloseMs=%.2f crcMs=%.2f pacingWaitMs=%.2f emptyReads=%d",label,frames,changed,elapsed,changed/elapsed,readNs/1e6/frames,imageNs/1e6/frames,drawNs/1e6/frames,hashNs/1e6/frames,waitNs/1e6/frames,empty);
            System.out.println(result);return result;
        }
    }

    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args[0]).toAbsolutePath();Files.createDirectories(directory);
        try(var http=new AppleHttp();var catalog=new AlbumCatalog(http,new PluginLog(null),System::currentTimeMillis)) {
            var motion=catalog.resolve(new AlbumKey("Bruno Mars","24K Magic"),new MotionSettings(true,List.of("us"),"",30));
            if(motion==null)throw new AssertionError("No album");
            var stream=HlsVariants.INSTANCE.resolveStream(motion.getVideoUrl(),http);
            System.out.println("SOURCE width="+stream.getWidth()+" height="+stream.getHeight()+" fps="+stream.getFramesPerSecond()+" codec="+stream.getCodec());
            Path local=cache(stream.getUrl(),http,directory);
            var results=new ArrayList<String>();
            results.add(measure("online-A",stream.getUrl(),stream));
            results.add(measure("local",local.toString(),stream));
            results.add(measure("online-B",stream.getUrl(),stream));
            Files.writeString(directory.resolve("summary.txt"),String.join("\n",results)+"\n");
        }
    }
}
