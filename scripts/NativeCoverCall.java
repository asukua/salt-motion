import com.xuncorp.spw.workshop.api.hook.UiHookCall;
import androidx.compose.runtime.Composer;
import androidx.compose.ui.Modifier;
import androidx.compose.ui.Alignment;
import androidx.compose.ui.layout.ContentScale;
import kotlin.jvm.functions.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real host image models and isolated Coil loader, with disk caching disabled. */
public final class NativeCoverCall {
    public static final AtomicInteger nativeLoaderCalls=new AtomicInteger();
    public static UiHookCall create(Modifier modifier)throws Exception{
        ClassLoader loader=Class.forName("com.xuncorp.spw.workshop.api.WorkshopApi").getClassLoader();
        Class<?> vriType=loader.loadClass("com.xuncorp.spc.v.Vri");
        var vriConstructor=loader.loadClass("com.xuncorp.spc.v.Vri$\u0529").getConstructor(String.class);vriConstructor.setAccessible(true);
        Object vri=vriConstructor.newInstance("file:///D:/cover-scene-fixture.flac");
        Object audio=loader.loadClass("com.xuncorp.voxzen.image.AudioCover").getConstructor(vriType,long.class,long.class,int.class).newInstance(vri,0L,0L,0);
        Class<?> contextType=loader.loadClass("coil3.PlatformContext");Object context=contextType.getField("\u037f").get(null);
        Class<?> builderType=loader.loadClass("coil3.request.ImageRequest$\u037f");Object builder=builderType.getConstructor(contextType).newInstance(context);
        builderType.getMethod("\u037f",Object.class).invoke(builder,audio);
        Object request=builderType.getMethod("\u037f").invoke(builder);
        Class<?> optionsType=loader.loadClass(dev.saltmotion.editor.HostUiBridge.IMAGE_OPTIONS);
        Object options=optionsType.getConstructor(Alignment.class,ContentScale.class,float.class,long.class,String.class,int.class).newInstance(null,ContentScale.Companion.getCrop(),0f,0L,null,249);
        Class<?> imageBuilderType=loader.loadClass("coil3.ImageLoader$\u037f");Object imageBuilder=imageBuilderType.getConstructor(contextType).newInstance(context);
        imageBuilderType.getMethod("\u0528",Function0.class).invoke(imageBuilder,(Function0<Object>)()->null);
        Object imageLoader=imageBuilderType.getMethod("\u0528").invoke(imageBuilder);
        Object[] raw={(Function0<Object>)()->request,modifier,(Function2<Composer,Integer,Object>)(c,f)->{nativeLoaderCalls.incrementAndGet();return imageLoader;},null,options,null,null,null,null,null};
        return new UiHookCall(){
            public Object getThisObject(){return null;}
            public List<Object> getArguments(){return Arrays.asList(raw);}
            public Object getArgument(String name){throw new IllegalArgumentException(name);}
        };
    }
}
