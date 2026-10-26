import dev.saltmotion.am.*;
import dev.saltmotion.artwork.*;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;

/** Verify suggested AM candidates, without presenting them as automatic local-album matches. */
public final class CandidateMotionScan {
    public static void main(String[] args) throws Exception {
        var plan=LibraryMotionScan.list(AppleMusicJson.INSTANCE.read(Files.readString(Path.of(args[0]))));
        var output=Path.of(args[1]);var done=new HashSet<String>();
        if(Files.exists(output)) for(var line:Files.readAllLines(output)) {
            var r=LibraryMotionScan.map(AppleMusicJson.INSTANCE.read(line));
            done.add(LibraryMotionScan.str(r.get("localKey"))+"|"+r.get("id"));
        }
        try(var trace=new LibraryMotionScan.Trace()) {
            var tokens=new PublicWebToken(trace,System::currentTimeMillis);int n=done.size();
            for(var entry:plan) {
                var input=LibraryMotionScan.map(entry);
                String key=LibraryMotionScan.str(input.get("localKey")),id=LibraryMotionScan.str(input.get("id")),region=LibraryMotionScan.str(input.get("region"));
                if(done.contains(key+"|"+id))continue;
                trace.reset();var record=new LinkedHashMap<String,Object>();
                input.forEach((k,v)->record.put(k.toString(),v));record.put("checkedAt",OffsetDateTime.now().toString());
                try {
                    var bearer=tokens.get(AlbumRef.Companion.parse(id),region);
                    if(bearer==null)throw new IllegalStateException("Token unavailable");
                    var response=trace.get("https://amp-api.music.apple.com/v1/catalog/"+region+"/albums/"+id+"?extend=editorialVideo&platform=web",bearer,4_000_000);
                    record.put("httpStatus",response.getStatus());
                    var expected=new AlbumKey(LibraryMotionScan.str(input.get("artistName")),LibraryMotionScan.str(input.get("name")));
                    var motion=response.getStatus()==200?AlbumCatalog.Companion.parseAlbum(response.getBody(),id,expected):null;
                    record.put("status",response.getStatus()==200?"no_verified_square_motion":"query_failed");
                    if(motion!=null) {
                        var stream=HlsVariants.INSTANCE.resolveStream(motion.getVideoUrl(),trace);
                        record.put("status","candidate_motion");record.put("width",stream.getWidth());record.put("height",stream.getHeight());record.put("codec",stream.getCodec());
                        record.put("sourceUrl",motion.getSourceUrl());
                    }
                }catch(Exception error){record.put("status","query_failed");record.put("error",error.getClass().getSimpleName());}
                record.put("events",new ArrayList<>(trace.events));
                Files.writeString(output,LibraryMotionScan.json(record)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                System.out.println("CANDIDATE "+(++n)+"/"+plan.size()+" status="+record.get("status")+" local="+key+" am="+record.get("name"));
            }
        }
    }
}
