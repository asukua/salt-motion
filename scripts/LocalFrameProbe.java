import dev.saltmotion.artwork.*;
import org.jetbrains.skia.*;
import java.util.*;
import java.util.zip.CRC32;

/** Isolated local playback measurement; production frame sizes and scheduling. */
public final class LocalFrameProbe {
    public static void main(String[] args) throws Exception {
        try(var decoder=new NativeFrames(args[0],2160,2160);var target=Surface.Companion.makeRasterN32Premul(486,486)) {
            long deadline=System.nanoTime()+20_000_000_000L;boolean started=false;
            while(!started){started=decoder.read()!=null;if(System.nanoTime()>deadline)throw new AssertionError("No video");}
            long warm=System.nanoTime()+4_000_000_000L;
            while(System.nanoTime()<warm){var f=decoder.read();if(f!=null)try(var i=Image.Companion.makeRaster(new ImageInfo(f.getWidth(),f.getHeight(),ColorType.BGRA_8888,ColorAlphaType.OPAQUE),f.getBgra(),f.getWidth()*4)){target.getCanvas().drawImageRect(i,Rect.makeWH(486,486));}}
            int count=0,changed=0;long prev=-1,start=System.nanoTime(),end=start+8_000_000_000L;var schedule=new FrameSchedule(30,start);
            while(System.nanoTime()<end){var f=decoder.read();if(f==null)continue;long wait=schedule.delayNanos(System.nanoTime());if(wait>0)Thread.sleep((wait+999999)/1000000);var crc=new CRC32();crc.update(f.getBgra());if(crc.getValue()!=prev)changed++;prev=crc.getValue();try(var i=Image.Companion.makeRaster(new ImageInfo(f.getWidth(),f.getHeight(),ColorType.BGRA_8888,ColorAlphaType.OPAQUE),f.getBgra(),f.getWidth()*4)){target.getCanvas().drawImageRect(i,Rect.makeWH(486,486));}count++;}
            double elapsed=(System.nanoTime()-start)/1e9;
            System.out.printf(Locale.ROOT,"RESULT frames=%d changed=%d fps=%.2f seconds=%.3f%n",count,changed,changed/elapsed,elapsed);
        }
    }
}
