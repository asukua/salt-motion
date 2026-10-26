import dev.saltmotion.*;
import dev.saltmotion.am.*;
import dev.saltmotion.artwork.*;
import org.jetbrains.skia.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;

/** Production scheduling/native reads and real raster draws; never attaches to the live player. */
public final class FrameRateProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("albumId artist album outputDirectory");
        Path output = Path.of(args[3]); Files.createDirectories(output);
        try (var http = new AppleHttp(); var catalog = new AlbumCatalog(http, new PluginLog(null), System::currentTimeMillis)) {
            var settings = new MotionSettings(true, List.of("us"), args[1]+" | "+args[2]+" = "+args[0], 30);
            var motion = catalog.resolve(new AlbumKey(args[1], args[2]), settings);
            if (motion == null) throw new AssertionError("No motion asset");
            var manifest = http.get(motion.getVideoUrl(), null, 262144).getBody();
            var rates = new TreeSet<String>();
            var match = java.util.regex.Pattern.compile("FRAME-RATE=([0-9.]+)").matcher(manifest);
            while (match.find()) rates.add(match.group(1));
            System.out.println("source-frame-rates="+rates);
            var stream = HlsVariants.INSTANCE.resolveStream(motion.getVideoUrl(), http);
            System.out.println("selected="+stream.getWidth()+"x"+stream.getHeight()+" codec="+stream.getCodec()+" bitrate="+stream.getBandwidth());
            var results = new ArrayList<String>();
            for (int target : new int[]{24,30}) results.add(measure(stream,target,8,"single"));
            var executor = Executors.newFixedThreadPool(2);
            try {
                var first = executor.submit(() -> measure(stream,30,8,"dual-A"));
                var second = executor.submit(() -> measure(stream,30,8,"dual-B"));
                results.add(first.get(45,TimeUnit.SECONDS)); results.add(second.get(45,TimeUnit.SECONDS));
            } finally { executor.shutdownNow(); }
            Files.writeString(output.resolve("summary.txt"),"album="+args[0]+" source-fps="+rates+"\n"+String.join("\n",results)+"\n");
            RenderLifecycleProbe.verify(stream);
        }
    }

    private static String measure(HlsVariants.Stream stream,int target,int seconds,String label) throws Exception {
        try (var decoder = new NativeFrames(stream.getUrl(),stream.getWidth(),stream.getHeight()); var surface = Surface.Companion.makeRasterN32Premul(486,486)) {
            long warmup=System.nanoTime()+15_000_000_000L;
            while(decoder.read()==null) {
                if(System.nanoTime()>warmup) throw new AssertionError("No first frame");
                Thread.sleep(10);
            }
            int frames=0,changed=0; long previous=-1,lastDraw=0,work=0;
            var intervals=new ArrayList<Double>();
            long start=System.nanoTime(),end=start+seconds*1_000_000_000L;
            FrameSchedule schedule=null;
            while(System.nanoTime()<end) {
                long began=System.nanoTime();
                long waiting=0;
                var frame=decoder.read();
                if(frame!=null) {
                    if(schedule!=null) {
                        long remaining=schedule.delayNanos(System.nanoTime());
                        if(remaining>0) {
                            long waitStart=System.nanoTime();
                            Thread.sleep((remaining+999_999L)/1_000_000L);
                            waiting=System.nanoTime()-waitStart;
                        }
                    }
                    if(frame.getWidth()!=stream.getWidth() || frame.getHeight()!=stream.getHeight()) throw new AssertionError("Source dimensions were reduced");
                    var crc=new CRC32(); crc.update(frame.getBgra());
                    long checksum=crc.getValue(); if(checksum!=previous) changed++; previous=checksum;
                    try(var image=Image.Companion.makeRaster(new ImageInfo(frame.getWidth(),frame.getHeight(),ColorType.BGRA_8888,ColorAlphaType.OPAQUE),frame.getBgra(),frame.getWidth()*4)) {
                        surface.getCanvas().drawImageRect(image,Rect.makeWH(486,486));
                    }
                    long drawn=System.nanoTime();
                    if(lastDraw!=0) intervals.add((drawn-lastDraw)/1e6);
                    lastDraw=drawn; frames++;
                    if(schedule==null) schedule=new FrameSchedule(target,System.nanoTime());
                }
                else Thread.sleep(10);
                work+=System.nanoTime()-began-waiting;
            }
            double elapsed=(System.nanoTime()-start)/1e9;
            if(frames==0 || changed<2) throw new AssertionError("No changing frames");
            Collections.sort(intervals);
            double p95=intervals.get((int)Math.floor((intervals.size()-1)*0.95));
            String report=String.format(Locale.ROOT,"PACED mode=%s target=%d frames=%d seconds=%.3f submittedFps=%.2f changedFps=%.2f p95IntervalMs=%.2f workMs=%.2f",label,target,frames,elapsed,frames/elapsed,changed/elapsed,p95,work/1e6/frames);
            System.out.println(report); return report;
        }
    }
}
