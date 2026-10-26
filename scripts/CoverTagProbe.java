import dev.saltmotion.editor.CoverTagWriter;
import java.nio.file.*;
import java.util.*;
import javax.sound.sampled.*;
import java.io.*;

/** Copies user audio into the workspace before any writes; the source file is hashed before and after. */
public final class CoverTagProbe{
    static String hash(Path p)throws Exception{try(var in=Files.newInputStream(p)){var md=java.security.MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;while((n=in.read(b))>=0)md.update(b,0,n);return HexFormat.of().formatHex(md.digest());}}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]),image=Path.of(args[1]);Files.createDirectories(root);
        var sources=new ArrayList<Path>();for(int i=2;i<args.length;i++)sources.add(Path.of(args[i]));
        Path wave=root.resolve("fixture-source.wav");
        var format=new AudioFormat(44100,16,1,true,false);byte[] pcm=new byte[44100*2];
        for(int i=0;i<pcm.length/2;i++){short sample=(short)(Math.sin(2*Math.PI*440*i/44100)*3000);pcm[i*2]=(byte)sample;pcm[i*2+1]=(byte)(sample>>>8);}
        try(var stream=new AudioInputStream(new ByteArrayInputStream(pcm),format,44100)){AudioSystem.write(stream,AudioFileFormat.Type.WAVE,wave.toFile());}sources.add(wave);
        for(Path source:sources){
            String before=hash(source);String ext=source.getFileName().toString().substring(source.getFileName().toString().lastIndexOf('.'));
            Path copy=root.resolve("中文 #1 100% + " + source.getFileName());Files.copy(source,copy,StandardCopyOption.REPLACE_EXISTING);
            String nativeVri="file:///"+copy.toAbsolutePath().toString().replace('\\','/');
            Path resolved=dev.saltmotion.editor.ArtworkModelsKt.pathOf(nativeVri);
            if(!resolved.equals(copy.toAbsolutePath()))throw new AssertionError("Native Vri resolved to another file");
            String audio=CoverTagWriter.audioHash(copy);
            Path backup=CoverTagWriter.write(resolved,image,root.resolve("backups"));
            if(!before.equals(hash(backup))||!before.equals(hash(source)))throw new AssertionError("Source or backup changed");
            if(!audio.equals(CoverTagWriter.audioHash(copy)))throw new AssertionError("Encoded audio changed");
            if(before.equals(hash(copy)))throw new AssertionError("Cover was not written");
            System.out.println("PASS embedded "+ext+" via raw native Vri with spaces/Unicode/#/%/+; original unchanged; exact backup; tags verified; encoded audio SHA-256 unchanged");
            Path corrupt=root.resolve("invalid-cover.jpg");Files.writeString(corrupt,"not an image");String committed=hash(copy);
            try{CoverTagWriter.write(copy,corrupt,root.resolve("backups"));throw new AssertionError("Invalid image accepted");}catch(Exception expected){}
            if(!committed.equals(hash(copy)))throw new AssertionError("Failed write damaged audio");
            System.out.println("PASS failed image write preserves "+ext);
            var checks=new java.util.concurrent.atomic.AtomicInteger();
            try{CoverTagWriter.write(copy,image,root.resolve("backups"),()->checks.incrementAndGet()>1);throw new AssertionError("Cancelled write committed");}catch(IOException expected){}
            if(!committed.equals(hash(copy)))throw new AssertionError("Cancelled write damaged audio");
            System.out.println("PASS cancellation before replace preserves "+ext);
        }
    }
}
