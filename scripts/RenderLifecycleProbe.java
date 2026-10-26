import dev.saltmotion.artwork.*;
import java.util.concurrent.*;

/** Repeated software contexts, owned pixel arrays, and concurrent read/retirement. */
public final class RenderLifecycleProbe {
    public static void verify(HlsVariants.Stream stream) throws Exception {
        var executor=Executors.newSingleThreadExecutor();
        try {
            for(int cycle=0;cycle<8;cycle++) {
                var decoder=new NativeFrames(stream.getUrl(),stream.getWidth(),stream.getHeight());
                try {
                    long deadline=System.nanoTime()+15_000_000_000L;
                    PixelFrame first=null;
                    while(first==null && System.nanoTime()<deadline) first=decoder.read();
                    if(first==null) throw new AssertionError("No initial software frame");
                    byte[] retained=first.getBgra(); byte[] snapshot=retained.clone();
                    PixelFrame next=null;
                    while(next==null && System.nanoTime()<deadline) next=decoder.read();
                    if(next==null || next.getBgra()==retained) throw new AssertionError("Frames must own separate pixel arrays");
                    if(!java.util.Arrays.equals(retained,snapshot)) throw new AssertionError("Published pixels were reused");
                    var entered=new CountDownLatch(1);
                    var reading=executor.submit(() -> { entered.countDown(); return decoder.read(); });
                    if(!entered.await(2,TimeUnit.SECONDS)) throw new AssertionError("Reader did not start");
                    decoder.close(); decoder.close();
                    reading.get(5,TimeUnit.SECONDS);
                    if(decoder.read()!=null) throw new AssertionError("Closed context still returned a frame");
                } finally { decoder.close(); }
            }
        } finally { executor.shutdownNow(); }
        System.out.println("PASS eight software contexts; immutable published bytes; concurrent read/close; idempotent retirement");
    }
}
