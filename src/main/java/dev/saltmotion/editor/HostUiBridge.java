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
    public static final String COIL="com.skydoves.landscapist.coil3.\u037f", AUDIO="com.xuncorp.voxzen.image.AudioCoverImageKt";
    // Filled only after every class in a known editor contract has matched.
    public static String MENU, SCOPE, ROOT, ROOT_CONTENT, SCOPE_IMPL, IMAGE_COMPONENT, IMAGE_OPTIONS;
    private static ClassLoader loader(){try{return Class.forName("com.xuncorp.spw.workshop.api.WorkshopApi").getClassLoader();}catch(Exception e){throw new IllegalStateException(e);}}
    private record Contract(String menu,String scope,String root,String rootContent,String components,String options,String[] hashes) {}
    private static final Contract[] CONTRACTS={
        new Contract("androidx.compose.ui.\u0996","androidx.compose.ui.\u0538","androidx.compose.ui.\u09b2","androidx.compose.ui.\u067f","androidx.compose.ui.\u0552","androidx.compose.ui.\u1050",new String[]{
            "b27eac07dee1c8690efca65f190bf7f4afa9528afe61a379a37c035870e3d13d","2148fc6d61e938cf9da78b43f682fe9aa67fe032be1c98b769e804dbabb14c93","f4788e37ce72d40be6ea350895d3c4c57248badb5556eed0b21922c3f1495533","2d3d59080697b8dc8ad804c880a7389b03051ea5647fe26bee3e936811b6ab61","fa3201603076e0034a92ec9a6a12ff860be29008bd0a99c6a15914476a3736a2","b6546d4c32a26033f5631ae5b6f211e0534e5e12e8d9b9ad3a5f901718d7f8fd","f95fa3c5321a60dd8a7ba8f2049078f35267c03ef20195d78191b225e8665f24","657adc650688a15d9fc09278bc4f69689970f5e3d955f528862e77aff32a1966","dd711c261a18c43f16008239aafa7435cdfb9e2597322d31dc9e3d6e938b4e7a","9fc16e8a34740341e11238c2123b7f4163ca3bba1d0f922b204571f475214ed9","21061a02c1e51c33e9b6fe9cbc717f94c5a8460179380e361d502911e1e53dc4"}),
        new Contract("androidx.compose.ui.\u0e09","androidx.compose.ui.\u0924","androidx.compose.ui.\u0510","androidx.compose.ui.\u041a","androidx.compose.ui.\u0f4a","androidx.compose.ui.\u0e88",new String[]{
            "2a142ea4085a821fd0a6380c4c2a1efc40becfe8cf075a89f42a1849d9998dac","50ee2f6c3c2ed2f6cc16fb5784124b366dc9605d40ba9fb31ab93b817c9da294","5c4020dcfa166843f9156fcdfe38d6aa451ca5be91e06217d729806a6d5816ad","112b4fdfc6f546e28a0f32169c3f0e8f5db6215b878c28095b4dd71007f91a66","114986345a4276b75c4ded15bda9ec90b30f26107454a4e4905d7a1607315bd8","e3a3e6a6f69a5001eff38b7d1bd919c7202e8a91cc2c6b3fd3d93f0b4bc145ac","b02d75c6aa43fbc3dec4f7f8ce32e72d5282aff4bda52bf0e0f6a67372980f38","064fe2674db8ce0078d69fd04e0e7034c2fabfddb7d36680544f1e8dbfe75a49","809dd0511313f76bb3ee445958397e489bb5e7c857e933c17bf2d3180eabbd34","9fc16e8a34740341e11238c2123b7f4163ca3bba1d0f922b204571f475214ed9","21061a02c1e51c33e9b6fe9cbc717f94c5a8460179380e361d502911e1e53dc4"})
    };
    public static synchronized void verify() throws Exception {
        for(Contract c:CONTRACTS) {
            String[] names={c.menu,c.scope,c.menu+"$\u037f","com.xuncorp.voxzen.ui.component.\u052c",c.root,c.rootContent,AUDIO,COIL,"androidx.compose.ui.\u02b0","androidx.compose.ui.\u02b2","androidx.compose.ui.\u0374"};
            boolean matches=true;
            for(int i=0;i<names.length;i++)try(var input=loader().getResourceAsStream(names[i].replace('.','/')+".class")){
                if(input==null||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())).equals(c.hashes[i])){matches=false;break;}
            }
            if(matches){MENU=c.menu;SCOPE=c.scope;ROOT=c.root;ROOT_CONTENT=c.rootContent;SCOPE_IMPL=c.menu+"$\u037f";IMAGE_COMPONENT=c.components;IMAGE_OPTIONS=c.options;return;}
        }
        throw new IllegalStateException("Unsupported cover editor contract");
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
