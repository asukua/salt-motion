package dev.saltmotion.editor;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.Modifier;
import androidx.compose.ui.layout.ContentScale;
import androidx.compose.ui.text.TextStyle;
import com.xuncorp.spw.workshop.api.hook.UiHookCall;
import kotlin.jvm.functions.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;

/** Steam 1.19.2 contracts only; all UI calls use the host's own class loader and native menu renderer. */
public final class HostUiBridge {
    public static final String MENU="androidx.compose.ui.\u0996",SCOPE="androidx.compose.ui.\u0538",ROOT="androidx.compose.ui.\u09b2",ROOT_CONTENT="androidx.compose.ui.\u067f",COIL="com.skydoves.landscapist.coil3.\u037f",AUDIO="com.xuncorp.voxzen.image.AudioCoverImageKt";
    public static final String SCOPE_IMPL=MENU+"$\u037f";
    private static ClassLoader loader(){try{return Class.forName("com.xuncorp.spw.workshop.api.WorkshopApi").getClassLoader();}catch(Exception e){throw new IllegalStateException(e);}}
    public static void verify() throws Exception {
        String[][] classes={
            {MENU,"b27eac07dee1c8690efca65f190bf7f4afa9528afe61a379a37c035870e3d13d"},
            {SCOPE,"2148fc6d61e938cf9da78b43f682fe9aa67fe032be1c98b769e804dbabb14c93"},
            {SCOPE_IMPL,"f4788e37ce72d40be6ea350895d3c4c57248badb5556eed0b21922c3f1495533"},
            {"com.xuncorp.voxzen.ui.component.\u052c","2d3d59080697b8dc8ad804c880a7389b03051ea5647fe26bee3e936811b6ab61"},
            {ROOT,"fa3201603076e0034a92ec9a6a12ff860be29008bd0a99c6a15914476a3736a2"},
            {ROOT_CONTENT,"b6546d4c32a26033f5631ae5b6f211e0534e5e12e8d9b9ad3a5f901718d7f8fd"},
            {AUDIO,"f95fa3c5321a60dd8a7ba8f2049078f35267c03ef20195d78191b225e8665f24"},
            {COIL,"657adc650688a15d9fc09278bc4f69689970f5e3d955f528862e77aff32a1966"},
            {"androidx.compose.ui.\u02b0","dd711c261a18c43f16008239aafa7435cdfb9e2597322d31dc9e3d6e938b4e7a"},
            {"androidx.compose.ui.\u02b2","9fc16e8a34740341e11238c2123b7f4163ca3bba1d0f922b204571f475214ed9"},
            {"androidx.compose.ui.\u0374","21061a02c1e51c33e9b6fe9cbc717f94c5a8460179380e361d502911e1e53dc4"}
        };
        for(var entry:classes)try(var input=loader().getResourceAsStream(entry[0].replace('.','/')+".class")){
            if(input==null||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())).equals(entry[1]))throw new IllegalStateException("Unsupported cover editor contract: "+entry[0]);
        }
    }
    private static final StackWalker WALKER=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    public static boolean afterAlbumCall(){return WALKER.walk(frames->frames.anyMatch(f->f.getClassName().equals(MENU)&&f.getMethodName().equals("\u037f")&&f.getByteCodeIndex()==76));}
    public static Object selectedTrack(Object callback)throws Exception {
        for(var field:callback.getClass().getDeclaredFields())if(field.getType().getName().equals("com.xuncorp.voxzen.data.entity.Track")){field.setAccessible(true);return field.get(callback);}
        return null;
    }
    public static void addMenuItem(Object scope,Function0<?> click,Function2<?,?,?> content)throws Exception {
        loader().loadClass(SCOPE).getMethod("\u037f",Function0.class,boolean.class,Function2.class).invoke(scope,click,true,content);
    }
    public static void menuLabel(String title,Function2<?,?,?> icon,Composer composer)throws Exception {
        loader().loadClass("com.xuncorp.voxzen.ui.component.\u052c").getMethod("\u037f",String.class,Modifier.class,Function2.class,boolean.class,Composer.class,int.class,int.class)
            .invoke(null,title,null,icon,false,composer,0,10);
    }
    public static long[] colors(Composer composer)throws Exception {
        Object colors=loader().loadClass("androidx.compose.ui.\u02b0").getMethod("\u0528",Composer.class).invoke(null,composer);
        String[] names={"\u037f","\u0528","\u0529","\u052a","\u052b","\u052c","\u052d","\u052e","\u052f","\u0560","\u0588"};
        long[] result=new long[names.length];for(int i=0;i<names.length;i++)result[i]=(Long)colors.getClass().getMethod(names[i]).invoke(colors);return result;
    }
    private static final class Typography {
        static final Method STYLES, BODY;
        static {
            try {
                STYLES=loader().loadClass("androidx.compose.ui.\u02b0").getMethod("\u0529",Composer.class);
                BODY=loader().loadClass("androidx.compose.ui.\u02b2").getMethod("\u037f");
            } catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
        }
    }
    /** Read the active SaltTheme composition local; never cache its current font selection. */
    public static TextStyle textStyle(Composer composer)throws Exception {
        return (TextStyle)Typography.BODY.invoke(Typography.STYLES.invoke(null,composer));
    }
    public static String coverPath(UiHookCall call)throws Exception {
        Object request=((Function0<?>)call.getArgument(0)).invoke();
        Object data=request.getClass().getMethod("\u0528").invoke(request);
        if(data==null||!data.getClass().getName().equals("com.xuncorp.voxzen.image.AudioCover"))return null;
        Object vri=data.getClass().getMethod("getVri").invoke(data);
        return (String)vri.getClass().getMethod("\u037f").invoke(vri);
    }
    public static ContentScale contentScale(UiHookCall call)throws Exception {
        Object options=call.getArgument(4);
        for(var method:options.getClass().getMethods())if(method.getReturnType()==ContentScale.class&&method.getParameterCount()==0)return (ContentScale)method.invoke(options);
        throw new IllegalStateException("ImageOptions ContentScale unavailable");
    }
    public static void originalCoil(UiHookCall call,Composer composer)throws Exception {
        Object[] args=new Object[13];for(int i=0;i<10;i++)args[i]=call.getArgument(i);args[10]=composer;args[11]=0;args[12]=488;
        Method selected=Arrays.stream(loader().loadClass(COIL).getMethods()).filter(m->m.getName().equals("\u037f")&&m.getParameterCount()==13&&m.getParameterTypes()[0]==Function0.class).findFirst().orElseThrow();
        selected.invoke(null,args);
    }
}
