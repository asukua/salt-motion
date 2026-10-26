import dev.saltmotion.*;
import dev.saltmotion.am.*;
import dev.saltmotion.editor.*;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;

/** Read-only catalog tests and exact host hook resolution, never attaches to the running player. */
public final class CoverEditorProbe {
    public static void main(String[] args)throws Exception{
        Path output=Path.of(args[0]);Files.createDirectories(output);
        HostUiBridge.verify();
        Class<?> resolver=Class.forName("com.xuncorp.voxzen.workshop.hooks.\u052a");
        Object singleton=Arrays.stream(resolver.getDeclaredFields()).filter(f->Modifier.isStatic(f.getModifiers())&&f.getType()==resolver).findFirst().orElseThrow().get(null);
        Method ordinary=Arrays.stream(resolver.getDeclaredMethods()).filter(m->Arrays.equals(m.getParameterTypes(),new Class<?>[]{String.class,String.class,List.class})).findFirst().orElseThrow();
        Method composable=Arrays.stream(resolver.getDeclaredMethods()).filter(m->Arrays.equals(m.getParameterTypes(),new Class<?>[]{String.class,String.class,String.class,List.class})).findFirst().orElseThrow();
        ordinary.invoke(singleton,HostUiBridge.SCOPE_IMPL,"\u037f",List.of("kotlin.jvm.functions.Function0","boolean","kotlin.jvm.functions.Function2"));
        composable.invoke(singleton,HostUiBridge.ROOT_CONTENT,"\u037f",HostUiBridge.ROOT,List.of("boolean"));
        composable.invoke(singleton,HostUiBridge.COIL,"\u037f",HostUiBridge.AUDIO,EditorHooks.INSTANCE.getCoilParameters());
        System.out.println("PASS host fingerprints, native menu method and two unique UI callsites accepted by real host resolver");
        if(args.length>1&&args[1].equals("online")){
            var log=new PluginLog(null);
            try(var apple=new AppleHttp();var http=new CoverHttp();var catalog=new AlbumCatalog(apple,log,System::currentTimeMillis)){
                var search=new CoverSearch(http,catalog);
                var store=new CoverStore(output.resolve("images"),log);
                var track=new SelectedTrack("fixture","Incorrect title","Incorrect artist","Incorrect album","", "D:\\fixture.flac");
                for(boolean songs:new boolean[]{false,true}){
                    String query=songs?"Nirvana Come As You Are":"AC/DC Iron Man 2";
                    for(var source:List.of(CoverSource.APPLE,CoverSource.NETEASE,CoverSource.QQ)){
                        long start=System.nanoTime();
                        var candidates=search.search(source,query,track,List.of("us","jp","cn"),songs);
                        var stills=candidates.stream().filter(c->!c.getAnimated()).toList();
                        if(stills.isEmpty())throw new AssertionError("Empty cover results: "+source+" songSearch="+songs);
                        var selected=stills.getFirst();Path image=store.importImage(http.bytes(selected.getImageUrl(),24_000_000));
                        store.apply(List.of("D:\\fixture-one.flac","D:\\fixture-two.flac"),image,source.getLabel(),null);
                        var fresh=new CoverStore(output.resolve("images"),log);
                        if(!fresh.find("D:\\fixture-one.flac").equals(fresh.find("file:///D:/fixture-two.flac")))throw new AssertionError("Shared cover mapping did not persist");
                        fresh.reset(List.of("D:\\fixture-one.flac"));
                        if(fresh.find("D:\\fixture-one.flac")!=null||fresh.find("D:\\fixture-two.flac")==null)throw new AssertionError("Reset affected unrelated cover");
                        String label=source.name()+"-"+(songs?"song":"album");
                        Files.copy(image,output.resolve(label+"."+image.getFileName().toString().substring(65)),StandardCopyOption.REPLACE_EXISTING);
                        var metadata=new Properties();metadata.setProperty("album",selected.getAlbum());metadata.setProperty("artist",selected.getArtist());
                        try(var out=Files.newOutputStream(output.resolve(label+".properties"))){metadata.store(out,"Actual public catalog result used by the UI fixture");}
                        System.out.println("PASS edited-query source="+source+" mode="+(songs?"song":"album")+" static="+stills.size()+" motion="+candidates.stream().filter(CoverCandidate::getAnimated).count()+" imageBytes="+Files.size(image)+" album="+selected.getAlbum()+" seconds="+String.format(Locale.ROOT,"%.2f",(System.nanoTime()-start)/1e9));
                    }
                }
            }
        }
    }
}
