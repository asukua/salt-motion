package dev.saltmotion.artwork;

import com.sun.jna.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.jetbrains.skia.*;

/** Dedicated libmpv software render context; all output stays in private CPU memory.
 * Parameter ABI follows mpv's include/mpv/render.h: int + pointer is 16 bytes on x64.
 */
public final class SoftwareRenderFrames implements AutoCloseable {
    private final FrameLayout layout;
    private static final int API_TYPE=1, BLOCK_FOR_TARGET_TIME=12,
        SW_SIZE=17, SW_FORMAT=18, SW_STRIDE=19, SW_POINTER=20;
    private final NativeLibrary library;
    private final Function update, render, swap, unsetCallback, freeContext, destroy;
    private final Semaphore updates=new Semaphore(0);
    public interface UpdateCallback extends Callback { void invoke(Pointer ignored); }
    private final UpdateCallback callback=ignored -> { if(updates.availablePermits()==0) updates.release(); };
    private final Memory allocation;
    private final Pointer pixels;
    private byte[] rowBuffer;
    private final Memory dimensions=new Memory(8), format=new Memory(5), stride=new Memory(8), timing=new Memory(4);
    private final Memory renderParams=new Memory(6*16L);
    private final Pointer handle;
    private Pointer context;
    private boolean closed;

    public SoftwareRenderFrames(String url,int width,int height) {
        if(Native.POINTER_SIZE!=8) throw new IllegalStateException("Unsupported platform");
        layout=FrameLayout.of(width,height);
        library=NativeLibrary.getInstance("libmpv-2");
        update=library.getFunction("mpv_render_context_update");
        render=library.getFunction("mpv_render_context_render");
        swap=library.getFunction("mpv_render_context_report_swap");
        unsetCallback=library.getFunction("mpv_render_context_set_update_callback");
        freeContext=library.getFunction("mpv_render_context_free");
        destroy=library.getFunction("mpv_terminate_destroy");
        allocation=new Memory((long)layout.bufferBytes()+63);
        pixels=allocation.align(64);
        handle=library.getFunction("mpv_create").invokePointer(new Object[0]);
        if(handle==null) { releaseBuffers(); throw new IllegalStateException("mpv_create failed"); }
        try {
            String[][] options={{"config","no"},{"msg-level","all=error"},{"vo","libmpv"},{"hwdec","no"},{"aid","no"},
                {"mute","yes"},{"volume","0"},{"loop-file","inf"},{"force-window","no"},{"audio-file-auto","no"},
                {"sub-auto","no"},{"osd-level","0"},{"vd-lavc-threads","2"},{"network-timeout","8"},
                {"demuxer-max-bytes","16777216"},{"demuxer-max-back-bytes","16777216"},{"hls-bitrate","max"}};
            var set=library.getFunction("mpv_set_option_string");
            for(var option:options) if(set.invokeInt(new Object[]{handle,option[0],option[1]})<0)
                throw new IllegalStateException("mpv rejected "+option[0]);
            if(library.getFunction("mpv_initialize").invokeInt(new Object[]{handle})<0)
                throw new IllegalStateException("mpv_initialize failed");
            try(var api=new Memory(3);var params=new Memory(32);var out=new Memory(8)) {
                api.setString(0,"sw","UTF-8"); params.clear(); out.clear();
                param(params,0,API_TYPE,api);
                int status=library.getFunction("mpv_render_context_create").invokeInt(new Object[]{out,handle,params});
                context=out.getPointer(0);
                if(status<0 || context==null) throw new IllegalStateException("Software render context unavailable: "+status);
            }
            dimensions.setInt(0,layout.width()); dimensions.setInt(4,layout.height());
            format.setString(0,"bgr0","UTF-8"); stride.setLong(0,layout.stride()); timing.setInt(0,1);
            renderParams.clear();
            param(renderParams,0,SW_SIZE,dimensions); param(renderParams,1,SW_FORMAT,format);
            param(renderParams,2,SW_STRIDE,stride); param(renderParams,3,SW_POINTER,pixels);
            param(renderParams,4,BLOCK_FOR_TARGET_TIME,timing);
            unsetCallback.invokeVoid(new Object[]{context,callback,Pointer.NULL});
            try(var args=new StringArray(new String[]{"loadfile",url,"replace"},"UTF-8")) {
                if(library.getFunction("mpv_command").invokeInt(new Object[]{handle,args})<0)
                    throw new IllegalStateException("loadfile failed");
            }
        } catch(Throwable error) { close(); throw error; }
    }

    private static void param(Memory params,int index,int type,Pointer data) {
        params.setInt(index*16L,type); params.setPointer(index*16L+8,data);
    }

    public record Frame(int width,int height,byte[] bgra) {}

    public synchronized Frame read() {
        if(closed) return null;
        param(renderParams,3,SW_POINTER,pixels);
        if(!renderNext()) return null;
        byte[] copy=new byte[layout.packedBytes()];
        if(layout.stride()==layout.rowBytes()) {
            pixels.read(0,copy,0,copy.length);
        } else {
            if(rowBuffer==null) rowBuffer=new byte[layout.bufferBytes()];
            pixels.read(0,rowBuffer,0,rowBuffer.length);
            for(int row=0;row<layout.height();row++)
                System.arraycopy(rowBuffer,row*layout.stride(),copy,row*layout.rowBytes(),layout.rowBytes());
        }
        for(int i=3;i<copy.length;i+=4) copy[i]=(byte)255;
        return new Frame(layout.width(),layout.height(),copy);
    }

    /** Render directly into independently owned Skia memory: no full-frame Java array or JNI copy. */
    public synchronized Image readImage() {
        if(closed) return null;
        try(var memory=Data.Companion.makeUninitialized(Math.addExact(layout.bufferBytes(),63))) {
            long address=memory.writableData(),aligned=(address+63)&~63L;
            param(renderParams,3,SW_POINTER,new Pointer(aligned));
            if(!renderNext()) return null;
            try(var pixels=memory.makeSubset((int)(aligned-address),layout.bufferBytes())) {
                // bgr0's fourth byte is ignored by OPAQUE; no redundant alpha fill is needed.
                return Image.Companion.makeRaster(new ImageInfo(layout.width(),layout.height(),ColorType.BGRA_8888,ColorAlphaType.OPAQUE),pixels,layout.stride());
            }
        }
    }

    private boolean renderNext() {
        if(closed) return false;
        try { if(!updates.tryAcquire(100,TimeUnit.MILLISECONDS)) return false; }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
        updates.drainPermits();
        if((update.invokeLong(new Object[]{context})&1)==0) return false;
        if(render.invokeInt(new Object[]{context,renderParams})<0) throw new IllegalStateException("Software frame rendering failed");
        swap.invokeVoid(new Object[]{context});
        return true;
    }

    public synchronized void close() {
        if(closed) return;
        closed=true;
        try {
            if(context!=null) {
                unsetCallback.invokeVoid(new Object[]{context,Pointer.NULL,Pointer.NULL});
                freeContext.invokeVoid(new Object[]{context}); context=null;
            }
        } finally {
            try { destroy.invokeVoid(new Object[]{handle}); }
            finally {
                releaseBuffers();
            }
        }
    }

    private void releaseBuffers() {
        renderParams.close(); dimensions.close(); format.close(); stride.close(); timing.close(); allocation.close();
        rowBuffer=null;
    }
}
