import dev.saltmotion.artwork.*;
import org.jetbrains.skia.*;
import java.util.*;

public final class ImageRateProbe {
    static java.lang.reflect.Method optimized;
    static {try{optimized=NativeFrames.class.getMethod("readImage");}catch(NoSuchMethodException ignored){}}
    static Image read(NativeFrames decoder) throws Exception {
        if(optimized!=null)return (Image)optimized.invoke(decoder);
        var f=decoder.read();return f==null?null:Image.Companion.makeRaster(new ImageInfo(f.getWidth(),f.getHeight(),ColorType.BGRA_8888,ColorAlphaType.OPAQUE),f.getBgra(),f.getWidth()*4);
    }
    public static void main(String[] args) throws Exception {
        try(var decoder=new NativeFrames(args[0],2160,2160);var surface=Surface.Companion.makeRasterN32Premul(486,486)){
            long warm=System.nanoTime()+7_000_000_000L;
            while(System.nanoTime()<warm){var image=read(decoder);if(image!=null)try(image){surface.getCanvas().drawImageRect(image,Rect.makeWH(486,486));}}
            int frames=0;long start=System.nanoTime(),end=start+8_000_000_000L;var schedule=new FrameSchedule(30,start);
            while(System.nanoTime()<end){var image=read(decoder);if(image==null)continue;try(image){long wait=schedule.delayNanos(System.nanoTime());if(wait>0)Thread.sleep((wait+999999)/1000000);surface.getCanvas().drawImageRect(image,Rect.makeWH(486,486));frames++;}}
            double elapsed=(System.nanoTime()-start)/1e9;
            System.out.printf(Locale.ROOT,"RESULT optimized=%s frames=%d fps=%.2f seconds=%.3f%n",optimized!=null,frames,frames/elapsed,elapsed);
            if(frames<2)throw new AssertionError("No frames");
        }
    }
}
