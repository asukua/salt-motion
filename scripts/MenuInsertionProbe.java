import dev.saltmotion.*;
import dev.saltmotion.editor.*;
import com.xuncorp.spw.workshop.api.hook.*;
import kotlin.Unit;
import kotlin.jvm.functions.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import static net.bytebuddy.matcher.ElementMatchers.*;

/** Instruments only its own JVM, executes the real menu builder until immediately after the album item. */
public final class MenuInsertionProbe {
    public static MethodHook callback;
    public static Method scopeMethod;
    public static final class StopAfterAlbum extends RuntimeException{}
    public static class ScopeAdvice {
        @Advice.OnMethodExit public static void after(@Advice.This Object receiver,@Advice.AllArguments Object[] args)throws Exception{
            if(HostUiBridge.afterAlbumCall()&&HostUiBridge.selectedTrack(args[0])!=null){
                MenuInsertionProbe.onAlbum(receiver,args);throw new StopAfterAlbum();
            }
        }
    }
    public static void onAlbum(Object receiver,Object[] args){
        callback.after(new MethodHookParam(){
            public Method getMethod(){return scopeMethod;}
            public Object getThisObject(){return receiver;}
            public Object[] getArgs(){return args;}
            public Object getResult(){return Unit.INSTANCE;}
            public Throwable getThrowable(){return null;}
            public boolean isOriginalSkipped(){return false;}
            public void setResult(Object v){throw new AssertionError("Menu must not replace the result");}
            public void setThrowable(Throwable v){throw new AssertionError();}
            public Object invokeOriginal(){throw new AssertionError();}
        });
    }
    static Object defaultValue(Class<?> type)throws Exception{
        if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;if(type==float.class)return 0f;if(type==double.class)return 0d;
        if(type==String.class)return "";
        if(type.isSealed())return defaultValue(type.getPermittedSubclasses()[0]);
        if(type.isInterface())return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a)->{
            if(m.getName().equals("toString"))return "Private menu probe";
            if(m.getReturnType().isPrimitive())return defaultValue(m.getReturnType());return null;
        });
        var field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);return ((sun.misc.Unsafe)field.get(null)).allocateInstance(type);
    }
    public static void main(String[] args)throws Exception{
        try{
            HostUiBridge.verify();Class<?> scope=Class.forName(HostUiBridge.SCOPE);Class<?> impl=Class.forName(HostUiBridge.SCOPE_IMPL);
            scopeMethod=impl.getMethod("\u037f",Function0.class,boolean.class,Function2.class);
            try(var runtime=new MotionRuntime(new PluginLog(null),new MotionSettings(),Path.of(args[0]).resolve("motion-cache"))){
                var hookClass=Class.forName("dev.saltmotion.editor.EditorHooks$install$1");
                var ctor=hookClass.getDeclaredConstructors()[0];ctor.setAccessible(true);callback=(MethodHook)ctor.newInstance(runtime.getStudio());
                byte[] transformed=new ByteBuddy().redefine(impl).visit(Advice.to(ScopeAdvice.class).on(is(scopeMethod))).make().getBytes();
                Files.createDirectories(Path.of(args[0]));Files.write(Path.of(args[0]).resolve("transformedScope.class"),transformed);
                ProbeAgent.instrumentation.redefineClasses(new java.lang.instrument.ClassDefinition(impl,transformed));
                Class<?> trackType=Class.forName("com.xuncorp.voxzen.data.entity.Track");var tc=Arrays.stream(trackType.getConstructors()).filter(c->c.getParameterCount()==23).findFirst().orElseThrow();
                Object[] values=new Object[23];Class<?>[] types=tc.getParameterTypes();for(int i=0;i<types.length;i++)values[i]=defaultValue(types[i]);
                values[0]="right-click-id";values[1]="Right-clicked Thunderstruck";values[2]="AC/DC";values[3]="Iron Man 2";values[4]="AC/DC";values[11]="file:///D:/right-click-target.flac";Object track=tc.newInstance(values);
                var items=new ArrayList<Object[]>();Object receiver=Proxy.newProxyInstance(scope.getClassLoader(),new Class<?>[]{scope},(p,m,a)->{
                    if(m.getParameterCount()==3&&m.getParameterTypes()[0]==Function0.class){items.add(a);return Unit.INSTANCE;}
                    if(m.getName().equals("toString"))return "Private native menu scope";return null;
                });
                Class<?> owner=Class.forName(HostUiBridge.MENU);
                Method build=Arrays.stream(owner.getDeclaredMethods()).filter(m->Modifier.isPrivate(m.getModifiers())&&m.getParameterCount()==14&&m.getParameterTypes()[5]==trackType&&m.getParameterTypes()[13]==scope).findFirst().orElseThrow();
                Object[] parameters=new Object[14];for(int i=0;i<14;i++)parameters[i]=defaultValue(build.getParameterTypes()[i]);
                parameters[4]=Set.of(track);parameters[5]=track;parameters[7]=List.of();parameters[8]=List.of();parameters[9]=List.of();parameters[13]=receiver;
                build.setAccessible(true);
                try{build.invoke(null,parameters);throw new AssertionError("Exact native album callsite was not reached");}
                catch(InvocationTargetException error){if(!(error.getCause() instanceof StopAfterAlbum))throw error;}
                if(items.size()!=2)throw new AssertionError("Expected native album followed by custom cover, got "+items.size());
                ((Function0<?>)items.get(1)[0]).invoke();
                var chosen=runtime.getStudio().getTrack();
                if(chosen==null||!chosen.getId().equals("right-click-id")||!chosen.getPath().equals("file:///D:/right-click-target.flac"))throw new AssertionError("Wrong right-clicked song selected");
                System.out.println("PASS real host menu builder BCI 76; production callback appended exactly one item after album; click opens correct right-clicked track");
            }
            System.exit(0);
        }catch(Throwable error){error.printStackTrace();System.exit(2);}
    }
}
