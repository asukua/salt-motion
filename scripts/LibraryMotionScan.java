import dev.saltmotion.*;
import dev.saltmotion.am.*;
import dev.saltmotion.artwork.*;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;

/** Read-only library audit through production matching and HLS selection; no player attachment. */
public final class LibraryMotionScan {
    static Map<?,?> map(Object value){return value instanceof Map<?,?> m?m:Map.of();}
    static List<?> list(Object value){return value instanceof List<?> l?l:List.of();}
    static String str(Object value){return value==null?"":value.toString();}
    static String albumKey(String artist,String album){return AlbumModelsKt.identity(artist)+"|"+AlbumModelsKt.identity(album);}

    static String json(Object value) {
        if(value==null)return "null";
        if(value instanceof Boolean || value instanceof Number)return value.toString();
        if(value instanceof Map<?,?> m) {
            var items=new ArrayList<String>(); for(var e:m.entrySet()) items.add(json(str(e.getKey()))+":"+json(e.getValue()));
            return "{"+String.join(",",items)+"}";
        }
        if(value instanceof Collection<?> c) return "["+String.join(",",c.stream().map(LibraryMotionScan::json).toList())+"]";
        var out=new StringBuilder("\"");
        for(char c:value.toString().toCharArray()) {
            switch(c) {case '"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(c<32)out.append(String.format("\\u%04x",(int)c));else out.append(c);}}
        }
        return out.append('"').toString();
    }

    static class Trace implements AppleTransport,AutoCloseable {
        final AppleHttp http=new AppleHttp();
        final List<Map<String,Object>> events=new ArrayList<>();
        final List<Map<String,Object>> candidates=new ArrayList<>();
        final List<Map<String,Object>> details=new ArrayList<>();
        long nextRequest;
        void reset(){events.clear();candidates.clear();details.clear();}
        public WebResponse get(String url,String bearer,int limit) {
            try {
                long remaining=nextRequest-System.nanoTime();
                if(remaining>0)Thread.sleep((remaining+999999)/1000000);
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Interrupted",e);}
            nextRequest=System.nanoTime()+250_000_000L;
            var uri=java.net.URI.create(url);
            var event=new LinkedHashMap<String,Object>();
            event.put("host",uri.getHost());event.put("path",uri.getPath());
            events.add(event);
            try {
                var response=http.get(url,bearer,limit);event.put("status",response.getStatus());
                if(uri.getHost().equals("amp-api.music.apple.com") && response.getStatus()==200) {
                    var root=map(AppleMusicJson.INSTANCE.read(response.getBody()));
                    String region=uri.getPath().split("/")[3];
                    if(uri.getPath().endsWith("/search")) {
                        for(var item:list(map(map(root.get("results")).get("albums")).get("data"))) {
                            var candidate=summary(map(item),region); if(!candidate.isEmpty())candidates.add(candidate);
                        }
                    } else if(uri.getPath().contains("/albums/")) {
                        for(var item:list(root.get("data"))) {var detail=summary(map(item),region);if(!detail.isEmpty())details.add(detail);}
                    }
                }
                return response;
            }catch(RuntimeException error){event.put("error",error.getClass().getSimpleName());throw error;}
        }
        static Map<String,Object> summary(Map<?,?> item,String region) {
            if(!"albums".equals(item.get("type")))return Map.of();
            var a=map(item.get("attributes"));var result=new LinkedHashMap<String,Object>();
            result.put("id",item.get("id"));result.put("region",region);
            for(String field:List.of("name","artistName","url","releaseDate","trackCount","upc","contentRating"))
                if(a.containsKey(field))result.put(field,a.get(field));
            var videos=map(a.get("editorialVideo"));
            result.put("editorialVideoKeys",new ArrayList<>(videos.keySet()));
            result.put("hasSquareVideo",videos.containsKey("motionDetailSquare")||videos.containsKey("motionSquareVideo1x1"));
            return result;
        }
        public void close(){http.close();}
    }

    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("inventory.json results.ndjson");
        var inventory=map(AppleMusicJson.INSTANCE.read(Files.readString(Path.of(args[0]))));
        var output=Path.of(args[1]);Files.createDirectories(output.toAbsolutePath().getParent());
        var completed=new HashSet<String>();
        if(Files.exists(output)) for(String line:Files.readAllLines(output)) {
            var record=map(AppleMusicJson.INSTANCE.read(line));
            completed.add(albumKey(str(record.get("artist")),str(record.get("album"))));
        }
        int done=completed.size(),found=0;
        try(var trace=new Trace();var catalog=new AlbumCatalog(trace,new PluginLog(null),System::currentTimeMillis)) {
            var settings=new MotionSettings(true,List.of("us","jp","cn"),"",30);
            for(var entry:list(inventory.get("groups"))) {
                var group=map(entry);String artist=str(group.get("artist")),album=str(group.get("album"));
                if(completed.contains(albumKey(artist,album)))continue;
                trace.reset();var record=new LinkedHashMap<String,Object>();
                record.put("artist",artist);record.put("album",album);record.put("checkedAt",OffsetDateTime.now().toString());
                record.put("trackCount",list(group.get("tracks")).size());record.put("pluginVersion","0.1.7");
                try {
                    var motion=catalog.resolve(new AlbumKey(artist,album),settings);
                    if(motion!=null) {
                        record.put("status","motion_found");record.put("albumId",motion.getAlbumId());record.put("sourceUrl",motion.getSourceUrl());
                        try {
                            var stream=HlsVariants.INSTANCE.resolveStream(motion.getVideoUrl(),trace);
                            record.put("width",stream.getWidth());record.put("height",stream.getHeight());record.put("codec",stream.getCodec());record.put("sourceFps",stream.getFramesPerSecond());
                            record.put("status","confirmed");found++;
                        }catch(Exception error){record.put("streamError",error.getClass().getSimpleName());}
                    } else {
                        boolean verifiedDetail=trace.details.stream().anyMatch(d->AlbumModelsKt.identity(str(d.get("name"))).equals(AlbumModelsKt.identity(album)) && AlbumModelsKt.identity(str(d.get("artistName"))).equals(AlbumModelsKt.identity(artist)));
                        boolean searched=trace.events.stream().anyMatch(e->str(e.get("path")).endsWith("/search") && Integer.valueOf(200).equals(e.get("status")));
                        record.put("status",verifiedDetail?"no_verified_square_motion":searched?"unmatched":"query_failed");
                    }
                }catch(Exception error){record.put("status","query_failed");record.put("error",error.getClass().getSimpleName());}
                record.put("events",new ArrayList<>(trace.events));record.put("candidates",new ArrayList<>(trace.candidates));record.put("details",new ArrayList<>(trace.details));
                Files.writeString(output,json(record)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                System.out.println("SCAN "+(++done)+"/"+list(inventory.get("groups")).size()+" status="+record.get("status")+" foundThisRun="+found+" artist="+artist+" album="+album);
            }
        }
        System.out.println("SCAN COMPLETE albums="+done);
    }
}
