import java.lang.reflect.*;
import java.nio.file.*;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import static net.bytebuddy.matcher.ElementMatchers.*;
import androidx.compose.ui.Modifier;
import androidx.compose.ui.draw.DrawModifierKt;
import androidx.compose.ui.graphics.drawscope.ContentDrawScope;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** Isolated JVM only: run the actual host square cover and verify the production stack and attachment. */
public final class CoverCallProbe {
    static Object target, targetInstance, bridge; static Method onStack, attach; static String caller; static int connected;
    public static class DrawAdvice {
        @Advice.OnMethodExit public static void exit(@Advice.AllArguments Object[] args,@Advice.Return Object result)throws Exception { CoverCallProbe.inspect(args,result); }
    }
    public static void inspect(Object[] args,Object result)throws Exception {
        if(!(boolean)onStack.invoke(targetInstance,true,target))return;
        String lambda=args[1].getClass().getName();
        if(!lambda.startsWith(caller+"$"))throw new AssertionError("Host cover lambda does not match caller: "+lambda);
        Modifier sentinel=DrawModifierKt.drawWithContent(Modifier.Companion,(Function1<ContentDrawScope,Unit>)(scope)->{scope.drawContent();return Unit.INSTANCE;});
        if(!(boolean)attach.invoke(bridge,result,args[0],(Modifier.Element)sentinel,true))throw new AssertionError("Real host modifier was rejected");
        connected++;
    }
    public static void main(String[] args)throws Exception {
        try {
            Class<?> targetClass=Class.forName("dev.saltmotion.hook.CoverTarget");targetInstance=targetClass.getField("INSTANCE").get(null);
            Method discover=java.util.Arrays.stream(targetClass.getDeclaredMethods()).filter(m->m.getName().startsWith("discover")&&!m.getName().startsWith("discoverInArchive")&&!java.lang.reflect.Modifier.isStatic(m.getModifiers())&&m.getParameterCount()==1&&m.getParameterTypes()[0]==ClassLoader.class).findFirst().orElseThrow();
            target=discover.invoke(targetInstance,targetClass.getClassLoader());if(target==null)throw new AssertionError("No host cover target");
            caller=(String)target.getClass().getMethod("getCaller").invoke(target);
            onStack=java.util.Arrays.stream(targetClass.getDeclaredMethods()).filter(m->m.getName().startsWith("onCoverStack")&&!java.lang.reflect.Modifier.isStatic(m.getModifiers())&&m.getParameterCount()==2).findFirst().orElseThrow();
            Class<?> bridgeClass=Class.forName("dev.saltmotion.hook.CoverModifier");bridge=bridgeClass.getField("INSTANCE").get(null);attach=bridgeClass.getMethod("attach",Modifier.class,Modifier.class,Modifier.Element.class,boolean.class);
            Method draw=DrawModifierKt.class.getMethod("drawWithCache",Modifier.class,Function1.class);
            byte[] transformed=new ByteBuddy().redefine(DrawModifierKt.class).visit(Advice.to(DrawAdvice.class).on(is(draw))).make().getBytes();
            ProbeAgent.instrumentation.redefineClasses(new java.lang.instrument.ClassDefinition(DrawModifierKt.class,transformed));
            Class.forName("dev.saltmotion.CoverHostSceneProbe").getMethod("render",String.class,String.class).invoke(null,caller,args[0]);
            if(connected<1)throw new AssertionError("Real square cover did not reach the stack/attachment check");
            System.out.println("PASS actual host cover Composable: real lambda class, retained stack BCI 385 and modifier attachment; calls="+connected);
            System.exit(0);
        }catch(Throwable error){error.printStackTrace();System.exit(2);}
    }
}
