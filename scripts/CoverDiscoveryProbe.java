import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import java.lang.reflect.*;

/** Uses real host class bytes in a private JVM; malformed/ambiguous variants must fail closed. */
public final class CoverDiscoveryProbe {
    static int find(byte[] hay, byte[] needle){outer:for(int i=0;i<=hay.length-needle.length;i++){for(int j=0;j<needle.length;j++)if(hay[i+j]!=needle[j])continue outer;return i;}return -1;}
    static void jar(Path path, Map<String,byte[]> entries)throws Exception {
        try(var out=new ZipOutputStream(Files.newOutputStream(path))){for(var e:entries.entrySet()){out.putNextEntry(new ZipEntry(e.getKey()));out.write(e.getValue());out.closeEntry();}}
    }
    public static void verify(ClassLoader loader, Path output)throws Exception{
        Class<?> target=loader.loadClass("dev.saltmotion.hook.CoverTarget");Object instance=target.getField("INSTANCE").get(null);
        Method structural=Arrays.stream(target.getDeclaredMethods()).filter(m->m.getName().startsWith("structuralMatch")&&!Modifier.isStatic(m.getModifiers())&&Arrays.equals(m.getParameterTypes(),new Class<?>[]{String.class,byte[].class})).findFirst().orElseThrow();
        Method scan=Arrays.stream(target.getDeclaredMethods()).filter(m->m.getName().startsWith("discoverInArchive")&&!Modifier.isStatic(m.getModifiers())&&Arrays.equals(m.getParameterTypes(),new Class<?>[]{Path.class})).findFirst().orElseThrow();
        Method discover=Arrays.stream(target.getDeclaredMethods()).filter(m->m.getName().startsWith("discover")&&!m.getName().startsWith("discoverInArchive")&&!Modifier.isStatic(m.getModifiers())&&Arrays.equals(m.getParameterTypes(),new Class<?>[]{ClassLoader.class})).findFirst().orElseThrow();
        Method verified=Arrays.stream(target.getDeclaredMethods()).filter(m->m.getName().startsWith("verified")&&!Modifier.isStatic(m.getModifiers())&&Arrays.equals(m.getParameterTypes(),new Class<?>[]{String.class,byte[].class})).findFirst().orElseThrow();
        Object known=discover.invoke(instance,loader);if(known==null)throw new AssertionError("Missing verified cover class");
        String name=(String)known.getClass().getMethod("getCaller").invoke(known), path=name.replace('.','/')+".class";
        byte[] bytes;try(var stream=loader.getResourceAsStream(path)){if(stream==null)throw new AssertionError("Missing verified cover class bytes");bytes=stream.readAllBytes();}
        if(!(boolean)structural.invoke(instance,name,bytes))throw new AssertionError("Verified cover class failed structural discovery");
        if(!(boolean)verified.invoke(instance,"99.0.0",bytes))throw new AssertionError("Compatible class incorrectly rejected due to future version string");
        if((boolean)structural.invoke(instance,"androidx.compose.ui.invalid",bytes)||(boolean)structural.invoke(instance,name,new byte[]{1,2,3}))throw new AssertionError("Invalid class was accepted");
        byte[] modified=bytes.clone();byte[] debug="FullScreenCover.kt:".getBytes(java.nio.charset.StandardCharsets.UTF_8);int i=find(modified,debug);
        if(i<0)throw new AssertionError("Missing debug source marker");i+=debug.length;if(modified[i]<'0'||modified[i]>'9')throw new AssertionError("Missing debug line");modified[i]=modified[i]=='9'?(byte)'8':(byte)(modified[i]+1);
        if(Arrays.equals(bytes,modified)||!(boolean)structural.invoke(instance,name,modified))throw new AssertionError("Harmless bytecode metadata change should preserve structural match");
        byte[] broken=null;
        for(int at=0;at<modified.length-5;at++){
            if((modified[at]&255)!=0xb8||modified[at+3]!=1||modified[at+4]!=1||modified[at+5]!=11)continue;
            byte[] candidate=modified.clone();candidate[at]=0;
            if(!(boolean)structural.invoke(instance,name,candidate)){broken=candidate;break;}
        }
        if(broken==null)throw new AssertionError("Could not locate and reject a modified cover call");
        Files.createDirectories(output);Path one=output.resolve("future-single.jar");jar(one,Map.of(path,modified,"com/xuncorp/spw/workshop/api/WorkshopApi.class",new byte[]{0}));
        Object result=scan.invoke(instance,one);if(result==null||!result.toString().contains(name))throw new AssertionError("Unique structurally compatible future host was not discovered");
        try(var futureLoader=new java.net.URLClassLoader(new java.net.URL[]{one.toUri().toURL()},null)) {
            Object fallback=discover.invoke(instance,futureLoader);
            if(fallback==null||!fallback.toString().contains(name))throw new AssertionError("Full future-host fallback did not discover a unique compatible class");
        }
        String other=name.substring(0,name.length()-1)+(char)(name.charAt(name.length()-1)+1), otherPath=other.replace('.','/')+".class";
        byte[] renamed=modified.clone();byte[] from=name.replace('.','/').getBytes(java.nio.charset.StandardCharsets.UTF_8),to=other.replace('.','/').getBytes(java.nio.charset.StandardCharsets.UTF_8);int at=find(renamed,from);
        if(at<0||from.length!=to.length)throw new AssertionError("Could not create ambiguity fixture");System.arraycopy(to,0,renamed,at,to.length);
        if(!(boolean)structural.invoke(instance,other,renamed))throw new AssertionError("Second future candidate fixture invalid");
        Path two=output.resolve("future-ambiguous.jar");jar(two,Map.of(path,modified,otherPath,renamed));
        if(scan.invoke(instance,two)!=null)throw new AssertionError("Ambiguous host must fail closed");
        System.out.println("PASS structural discovery: future version accepted, harmless changes accepted, changed invocation rejected, ambiguous candidates rejected");
    }
}
