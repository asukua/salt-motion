package dev.saltmotion.editor;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Writes a sibling copy, verifies tags and encoded audio, then atomically replaces the original. */
public final class CoverTagWriter {
    private CoverTagWriter() {}
    public static boolean supported(Path file) {
        return Set.of("flac","mp3","m4a","mp4","wav","aif","aiff","ogg","opus","dsf").contains(extension(file));
    }
    private static String extension(Path file) {var name=file.getFileName().toString();return name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);}
    public static synchronized Path write(Path target,Path image,Path backupRoot) throws Exception {
        return write(target,image,backupRoot,()->Thread.currentThread().isInterrupted());
    }
    public static synchronized Path write(Path target,Path image,Path backupRoot,java.util.function.BooleanSupplier cancelled) throws Exception {
        if(cancelled.getAsBoolean())throw new IOException("写入已取消");
        target=target.toAbsolutePath().normalize();image=image.toAbsolutePath().normalize();
        if(!supported(target))throw new IOException("此格式暂不支持写入封面");
        if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(target)||!Files.isWritable(target))throw new IOException("歌曲不可写或不是本地普通文件");
        if(!Files.isRegularFile(image)||Files.size(image)>24_000_000)throw new IOException("封面图片不可用或过大");
        byte[] encoded=Files.readAllBytes(image);
        if(!((encoded.length>3&&encoded[0]==(byte)255&&encoded[1]==(byte)216)||(encoded.length>8&&encoded[0]==(byte)137&&encoded[1]==80)))throw new IOException("写入歌曲的封面须为 JPEG 或 PNG 图片");
        try(var decoded=org.jetbrains.skia.Image.Companion.makeFromEncoded(encoded);var bitmap=new org.jetbrains.skia.Bitmap()){
            if(decoded.getWidth()>8192||decoded.getHeight()>8192||(long)decoded.getWidth()*decoded.getHeight()>33_554_432)throw new IOException("封面分辨率过大");
            bitmap.allocPixels(new org.jetbrains.skia.ImageInfo(decoded.getWidth(),decoded.getHeight(),org.jetbrains.skia.ColorType.BGRA_8888,org.jetbrains.skia.ColorAlphaType.PREMUL));
            if(!decoded.readPixels(bitmap))throw new IOException("封面图片不完整");
        }
        String before=hashFile(target);String audioBefore=audioHash(target);
        Path temporary=target.resolveSibling(".salt-cover-"+UUID.randomUUID()+"."+extension(target));
        Path backup=null;
        try {
            Files.copy(target,temporary,StandardCopyOption.COPY_ATTRIBUTES);
            ClassLoader loader=Class.forName("com.xuncorp.spw.workshop.api.WorkshopApi").getClassLoader();
            Class<?> audioIo=loader.loadClass("org.jaudiotagger.audio.AudioFileIO");
            Class<?> audioType=loader.loadClass("org.jaudiotagger.audio.AudioFile");
            Class<?> tagType=loader.loadClass("org.jaudiotagger.tag.Tag");
            Class<?> artType=loader.loadClass("org.jaudiotagger.tag.images.Artwork");
            Object audio=audioIo.getMethod("read",File.class).invoke(null,temporary.toFile());
            Object tag=audioType.getMethod("getTagOrCreateAndSetDefault").invoke(audio);
            List<String> tagsBefore=textFields(tag,tagType,loader);
            @SuppressWarnings("unchecked") var oldImages=(List<Object>)tagType.getMethod("getArtworkList").invoke(tag);
            var keep=new ArrayList<Object>();
            boolean explicitFront=false;
            for(var old:oldImages)if((Integer)artType.getMethod("getPictureType").invoke(old)==3)explicitFront=true;
            for(int i=0;i<oldImages.size();i++){
                var old=oldImages.get(i);int type=(Integer)artType.getMethod("getPictureType").invoke(old);
                if(explicitFront?type!=3:i!=0)keep.add(old);
            }
            Class<?> standard=loader.loadClass("org.jaudiotagger.tag.images.StandardArtwork");
            Object artwork=standard.getMethod("createArtworkFromFile",File.class).invoke(null,image.toFile());
            artType.getMethod("setPictureType",int.class).invoke(artwork,3);
            tagType.getMethod("deleteArtworkField").invoke(tag);
            tagType.getMethod("setField",artType).invoke(tag,artwork);
            for(var other:keep)tagType.getMethod("addField",artType).invoke(tag,other);
            audioType.getMethod("commit").invoke(audio);
            Object check=audioIo.getMethod("read",File.class).invoke(null,temporary.toFile());
            Object checkTag=audioType.getMethod("getTag").invoke(check);
            @SuppressWarnings("unchecked") var actual=(List<Object>)tagType.getMethod("getArtworkList").invoke(checkTag);
            if(actual.isEmpty()||!Arrays.equals(Files.readAllBytes(image),(byte[])artType.getMethod("getBinaryData").invoke(actual.getFirst())))throw new IOException("封面写入后校验失败，原文件未替换");
            if(actual.size()!=keep.size()+1)throw new IOException("原有附加封面数量校验失败，原文件未替换");
            for(int i=0;i<keep.size();i++){
                var expected=keep.get(i);var saved=actual.get(i+1);
                if(!artType.getMethod("getPictureType").invoke(expected).equals(artType.getMethod("getPictureType").invoke(saved))||
                    !Arrays.equals((byte[])artType.getMethod("getBinaryData").invoke(expected),(byte[])artType.getMethod("getBinaryData").invoke(saved)))throw new IOException("原有附加封面校验失败，原文件未替换");
            }
            if(!tagsBefore.equals(textFields(checkTag,tagType,loader)))throw new IOException("原有标签校验失败，原文件未替换");
            if(!audioBefore.equals(audioHash(temporary)))throw new IOException("音频数据校验失败，原文件未替换");
            if(cancelled.getAsBoolean())throw new IOException("写入已取消，原文件未替换");
            if(!before.equals(hashFile(target)))throw new IOException("歌曲已被其他程序修改，请重新打开后再试");
            String key=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(target.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0,20);
            Path directory=backupRoot.resolve(key);Files.createDirectories(directory);
            backup=directory.resolve(System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8)+"-"+target.getFileName());
            Files.copy(target,backup,StandardCopyOption.COPY_ATTRIBUTES);
            if(!before.equals(hashFile(backup))||!before.equals(hashFile(target)))throw new IOException("备份期间歌曲发生变化，原文件未替换");
            if(cancelled.getAsBoolean())throw new IOException("写入已取消，原文件未替换");
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            return backup;
        } catch(InvocationTargetException error){
            var cause=error.getCause();throw new IOException("歌曲标签写入失败："+(cause.getMessage()==null?cause.getClass().getSimpleName():cause.getMessage()),cause);
        } finally {Files.deleteIfExists(temporary);}
    }
    private static List<String> textFields(Object tag,Class<?> tagType,ClassLoader loader) throws Exception {
        Class<?> field=loader.loadClass("org.jaudiotagger.tag.TagField");
        var values=new ArrayList<String>();var fields=(Iterator<?>)tagType.getMethod("getFields").invoke(tag);
        while(fields.hasNext()){
            var value=fields.next();String id=(String)field.getMethod("getId").invoke(value);
            if(Set.of("APIC","PIC","covr","COVERART","COVERARTMIME","METADATA_BLOCK_PICTURE").contains(id))continue;
            // Some legacy ID3 text ends with a redundant NUL. JAudioTagger removes this
            // terminator on serialization; internal separators and all other content remain compared.
            String content=value.toString().replaceAll("\\x00+(?=\";)","").replaceAll("\\x00+$","");
            values.add(id+"\u0000"+content);
        }
        Collections.sort(values);return values;
    }
    private static String hashFile(Path path) throws Exception {try(var in=Files.newInputStream(path)){var md=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];int n;while((n=in.read(b))>=0)md.update(b,0,n);return HexFormat.of().formatHex(md.digest());}}
    private static void digestRange(RandomAccessFile file,MessageDigest md,long start,long end) throws IOException {
        if(start<0||end<start||end>file.length())throw new IOException("无效的音频数据范围");
        file.seek(start);byte[] b=new byte[65536];long remaining=end-start;
        while(remaining>0){int n=file.read(b,0,(int)Math.min(b.length,remaining));if(n<=0)throw new EOFException();md.update(b,0,n);remaining-=n;}
    }
    private static long u32le(RandomAccessFile f)throws IOException{return Integer.toUnsignedLong(Integer.reverseBytes(f.readInt()));}
    private static long u64le(RandomAccessFile f)throws IOException{long value=Long.reverseBytes(f.readLong());if(value<0)throw new IOException("音频块过大");return value;}
    private static String four(RandomAccessFile f)throws IOException{byte[] b=new byte[4];f.readFully(b);return new String(b,java.nio.charset.StandardCharsets.US_ASCII);}
    /** Digest the encoded audio payload, excluding cover/tag blocks. Never decodes or transcodes. */
    public static String audioHash(Path path)throws Exception {
        var md=MessageDigest.getInstance("SHA-256");String extension=extension(path);int chunks=0;
        try(var f=new RandomAccessFile(path.toFile(),"r")){
            long length=f.length();
            switch(extension){
                case "flac"->{
                    long start=0;if(four(f).startsWith("ID3")){f.seek(6);int size=0;for(int i=0;i<4;i++){int v=f.readUnsignedByte();if(v>127)throw new IOException("无效 ID3 长度");size=(size<<7)|v;}start=10L+size;}
                    f.seek(start);if(!four(f).equals("fLaC"))throw new IOException("不是有效的 FLAC 文件");
                    boolean last=false;while(!last){int flags=f.readUnsignedByte();last=(flags&128)!=0;int n=(f.readUnsignedByte()<<16)|(f.readUnsignedByte()<<8)|f.readUnsignedByte();long next=f.getFilePointer()+n;if(next>length)throw new EOFException();f.seek(next);}
                    digestRange(f,md,f.getFilePointer(),length);chunks++;
                }
                case "mp3"->{
                    Class<?> io=Class.forName("org.jaudiotagger.audio.AudioFileIO");Object a=io.getMethod("read",File.class).invoke(null,path.toFile());Object header=a.getClass().getMethod("getAudioHeader").invoke(a);
                    long start=((Number)header.getClass().getMethod("getMp3StartByte").invoke(header)).longValue();long end=length;
                    if(end>=128){f.seek(end-128);if(four(f).startsWith("TAG"))end-=128;}
                    if(end>=32){f.seek(end-32);byte[] marker=new byte[8];f.readFully(marker);if(Arrays.equals(marker,"APETAGEX".getBytes(java.nio.charset.StandardCharsets.US_ASCII))){f.skipBytes(4);end-=u32le(f);}}
                    digestRange(f,md,start,end);chunks++;
                }
                case "m4a","mp4"->{
                    for(long p=0;p+8<=length;){f.seek(p);long size=Integer.toUnsignedLong(f.readInt());String type=four(f);int header=8;if(size==1){size=f.readLong();header=16;}if(size==0)size=length-p;if(size<header||size>length-p)throw new IOException("无效 MP4 原子");if(type.equals("mdat")){digestRange(f,md,p+header,p+size);chunks++;}p+=size;}
                }
                case "wav","aif","aiff"->{
                    boolean wave=extension.equals("wav");String magic=four(f);f.skipBytes(4);String format=four(f);
                    if(!(wave?magic.equals("RIFF")&&format.equals("WAVE"):magic.equals("FORM")&&(format.equals("AIFF")||format.equals("AIFC"))))throw new IOException("不支持的 RIFF/AIFF 格式");
                    for(long p=12;p+8<=length;){f.seek(p);String type=four(f);long size=wave?u32le(f):Integer.toUnsignedLong(f.readInt());if(size>length-p-8)throw new EOFException();if(type.equals(wave?"data":"SSND")){digestRange(f,md,p+8,p+8+size);chunks++;}p+=8+size+(size&1);}
                }
                case "dsf"->{
                    if(!four(f).equals("DSD "))throw new IOException("不是 DSF 文件");long header=u64le(f);u64le(f);long metadata=u64le(f);if(header<28||header>length)throw new IOException("无效 DSF 头");long end=metadata==0?length:metadata;
                    for(long p=header;p+12<=end;){f.seek(p);String type=four(f);long size=u64le(f);if(size<12||size>end-p)throw new EOFException();if(type.equals("data")){digestRange(f,md,p+12,p+size);chunks++;}p+=size;}
                }
                case "ogg","opus"->{
                    var packet=new ByteArrayOutputStream();int packetIndex=0;boolean opus=false;
                    while(f.getFilePointer()<length){if(!four(f).equals("OggS"))throw new IOException("无效 Ogg 页");f.skipBytes(22);int segments=f.readUnsignedByte();byte[] sizes=new byte[segments];f.readFully(sizes);
                        for(byte value:sizes){int size=value&255;byte[] data=new byte[size];f.readFully(data);packet.write(data);if(size<255){byte[] p=packet.toByteArray();packet.reset();if(packetIndex==0){opus=p.length>=8&&new String(p,0,8,java.nio.charset.StandardCharsets.US_ASCII).equals("OpusHead");if(!opus&&!(p.length>=7&&p[0]==1&&new String(p,1,6,java.nio.charset.StandardCharsets.US_ASCII).equals("vorbis")))throw new IOException("不支持的 Ogg 编码");}if(packetIndex++>=(opus?2:3)){md.update(java.nio.ByteBuffer.allocate(4).putInt(p.length).array());md.update(p);chunks++;}}}
                    }
                    if(packet.size()!=0)throw new IOException("Ogg 数据包不完整");
                }
                default->throw new IOException("此格式暂不支持音频数据校验");
            }
        }
        if(chunks==0)throw new IOException("未找到音频数据，原文件未替换");return HexFormat.of().formatHex(md.digest());
    }
}
