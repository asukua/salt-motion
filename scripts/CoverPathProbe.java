import dev.saltmotion.editor.*;
import dev.saltmotion.*;
import dev.saltmotion.am.AppleMusicJson;
import java.nio.file.*;
import java.util.*;

/** Matches literal host Vri conversion; verifies persistent image mappings without changing user audio. */
public final class CoverPathProbe {
    static Object nativeVri(String value)throws Exception{
        var ctor=Class.forName("com.xuncorp.spc.v.Vri$\u0529").getConstructor(String.class);ctor.setAccessible(true);return ctor.newInstance(value);
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]).toAbsolutePath();Files.createDirectories(root);
        var helper=Class.forName("androidx.compose.ui.\u0756");
        var toFile=helper.getMethod("\u0528",Class.forName("com.xuncorp.spc.v.Vri"));
        var values=new ArrayList<String>();
        for(String name:List.of("Michael Jackson - Thriller.flac","幼稚园杀手 - 再爱我一次 #1 100% +.mp3","literal%20space.flac","literal space.flac")){
            var file=root.resolve(name);Files.writeString(file,name);values.add("file:///"+file.toString().replace('\\','/'));
        }
        var store=new CoverStore(root.resolve("covers"),new PluginLog(null));var image=store.importImage(Files.readAllBytes(Path.of(args[1])));
        for(String vri:values){
            Path actual=ArtworkModelsKt.pathOf(vri);Path expected=((java.io.File)toFile.invoke(null,nativeVri(vri))).toPath();
            if(!actual.equals(expected)||!Files.isRegularFile(actual))throw new AssertionError("Vri differs from host: "+vri);
            store.apply(List.of(vri),image,"Fixture",null);
            if(store.find(actual.toString())==null||store.find(vri)==null)throw new AssertionError("Raw/native lookup failed");
        }
        var fresh=new CoverStore(root.resolve("covers"),new PluginLog(null));
        for(String vri:values)if(fresh.find(vri)==null)throw new AssertionError("Native mapping not retained after reload");
        fresh.reset(List.of(values.get(2)));
        if(fresh.find(values.get(2))!=null||fresh.find(values.get(3))==null)throw new AssertionError("Percent-encoded spelling was confused with literal filename");
        System.out.println("PASS literal native Vri matches actual host; spaces/Unicode/#/%/+; distinct %20 filename; apply/reload/reset cover mappings");
        if(args.length>2){
            var list=(List<?>)AppleMusicJson.INSTANCE.read(Files.readString(Path.of(args[2])));int count=0;
            for(Object value:list){
                String vri=(String)value;Path actual=ArtworkModelsKt.pathOf(vri);Path expected=((java.io.File)toFile.invoke(null,nativeVri(vri))).toPath();
                if(!actual.equals(expected)||!Files.isRegularFile(actual))throw new AssertionError("Music-library Vri mismatch or missing file: "+vri);count++;
            }
            System.out.println("PASS read-only path validation for "+count+" library songs; no audio writes");
        }
    }
}
