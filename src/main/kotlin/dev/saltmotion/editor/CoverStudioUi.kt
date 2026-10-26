package dev.saltmotion.editor

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xuncorp.spw.workshop.api.WorkshopApi
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import java.awt.EventQueue
import java.awt.FileDialog
import java.nio.file.Files
import java.nio.file.Path as FilePath
import kotlin.math.max
import kotlinx.coroutines.*
import org.jetbrains.skia.Image as SkImage
import org.jetbrains.skia.Surface
import org.jetbrains.skia.Rect

internal data class StudioPalette(val highlight:Color=Color(0xff49beff), val text:Color=Color(0xffebeef1),
    val secondary:Color=Color(0xffaeb4ba), val background:Color=Color(0xff222222), val card:Color=Color(0xff2b2b2b),
    val stroke:Color=Color(0xff414141), val onHighlight:Color=Color(0xff102532), val error:Color=Color(0xffff9090))

private val LocalStudioFontFamily = staticCompositionLocalOf<FontFamily?> { null }

@OptIn(InternalComposeApi::class)
@Composable internal fun hostPalette(): StudioPalette {
    val composer = currentComposer
    val values = runCatching { HostUiBridge.colors(composer) }.getOrNull() ?: return StudioPalette()
    fun c(i:Int)=Color(values[i].toULong())
    return StudioPalette(c(0),c(1),c(2),c(3),c(5),c(6),c(7),c(10))
}
internal enum class Glyph { PHOTO,SEARCH,IMPORT,WRITE,CHECK,CLOSE,PLAY,RESET }
@Composable internal fun StudioGlyph(kind:Glyph,color:Color,modifier:Modifier=Modifier.size(20.dp)) {
    Canvas(modifier) {
        val factor=size.width/24f
        scale(factor,factor,pivot=Offset.Zero) {
            val stroke=Stroke(1.65f,cap=StrokeCap.Round)
            fun line(x:Float,y:Float,a:Float,b:Float)=drawLine(color,Offset(x,y),Offset(a,b),1.65f,StrokeCap.Round)
            when(kind){
                Glyph.PHOTO->{drawRoundRect(color,Offset(3f,3f),androidx.compose.ui.geometry.Size(18f,18f),CornerRadius(3f),style=stroke);drawCircle(color,1.6f,Offset(15.8f,8f),style=stroke);val p=Path().apply{moveTo(3f,16f);lineTo(8f,11f);lineTo(14f,17f);lineTo(17f,14f);lineTo(21f,18f)};drawPath(p,color,style=stroke)}
                Glyph.SEARCH->{drawCircle(color,6.4f,Offset(10.5f,10.5f),style=stroke);line(15.3f,15.3f,21f,21f)}
                Glyph.IMPORT->{line(12f,16f,12f,3f);line(7f,8f,12f,3f);line(17f,8f,12f,3f);val p=Path().apply{moveTo(3f,14f);lineTo(3f,20f);lineTo(21f,20f);lineTo(21f,14f)};drawPath(p,color,style=stroke)}
                Glyph.WRITE->{val p=Path().apply{moveTo(4f,3f);lineTo(17f,3f);lineTo(21f,7f);lineTo(21f,21f);lineTo(3f,21f);lineTo(3f,3f);moveTo(7f,3f);lineTo(7f,10f);lineTo(17f,10f);lineTo(17f,3f);moveTo(7f,21f);lineTo(7f,15f);lineTo(17f,15f);lineTo(17f,21f)};drawPath(p,color,style=stroke)}
                Glyph.CHECK->{line(4f,12f,9f,17f);line(9f,17f,20f,6f)}
                Glyph.CLOSE->{line(6f,6f,18f,18f);line(18f,6f,6f,18f)}
                Glyph.PLAY->{val p=Path().apply{moveTo(8f,5f);lineTo(19f,12f);lineTo(8f,19f);close()};drawPath(p,color)}
                Glyph.RESET->{drawArc(color,45f,285f,false,Offset(4f,4f),androidx.compose.ui.geometry.Size(16f,16f),style=stroke);line(3f,4f,3f,10f);line(3f,10f,9f,10f)}
            }
        }
    }
}
@Composable private fun Label(text:String,p:StudioPalette,size:Int=14,secondary:Boolean=false,weight:FontWeight=FontWeight.Normal,maxLines:Int=1,modifier:Modifier=Modifier) {
    BasicText(text,modifier,style=TextStyle(fontFamily=LocalStudioFontFamily.current,color=if(secondary)p.secondary else p.text,fontSize=size.sp,fontWeight=weight,lineHeight=(size+6).sp),maxLines=maxLines,overflow=TextOverflow.Ellipsis)
}
@Composable private fun Action(text:String,p:StudioPalette,onClick:()->Unit,icon:Glyph?=null,primary:Boolean=false,enabled:Boolean=true,compact:Boolean=false) {
    val interactions=remember{MutableInteractionSource()};val hovered by interactions.collectIsHoveredAsState()
    val shape=RoundedCornerShape(if(compact)7.dp else 9.dp)
    val bg=when{primary&&enabled->p.highlight;hovered&&enabled->p.text.copy(alpha=.09f);else->p.card}
    val color=(if(primary&&enabled)p.onHighlight else p.text).copy(alpha=if(enabled)1f else .35f)
    Row(Modifier.clip(shape).background(bg).border(1.dp,if(primary&&enabled)p.highlight else p.stroke,shape)
        .hoverable(interactions).clickable(interactionSource=interactions,indication=null,enabled=enabled,onClick=onClick)
        .padding(horizontal=if(compact)10.dp else 14.dp,vertical=if(compact)7.dp else 10.dp),
        horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically) {
        if(icon!=null)StudioGlyph(icon,color,Modifier.size(if(compact)16.dp else 18.dp))
        BasicText(text,style=TextStyle(fontFamily=LocalStudioFontFamily.current,color=color,fontSize=(if(compact)12 else 13).sp,fontWeight=if(primary)FontWeight.Medium else FontWeight.Normal))
    }
}
@OptIn(InternalComposeApi::class)
@Composable internal fun NativeCoverMenuLabel() {
    val icon:@Composable ()->Unit={StudioGlyph(Glyph.PHOTO,hostPalette().text)}
    @Suppress("UNCHECKED_CAST")
    HostUiBridge.menuLabel("专辑封面自定义",icon as (Composer,Int)->Unit,currentComposer)
}

/** Isolated raster images own their pixels. Remote thumbnails are bounded separately from saved originals. */
internal fun rasterImage(bytes:ByteArray,maxSide:Int):SkImage = SkImage.makeFromEncoded(bytes).use { image ->
    require(image.width in 1..8192&&image.height in 1..8192&&image.width.toLong()*image.height<=33_554_432)
    val ratio=minOf(1.0,maxSide.toDouble()/max(image.width,image.height))
    Surface.makeRasterN32Premul(max(1,(image.width*ratio).toInt()),max(1,(image.height*ratio).toInt())).use { surface ->
        surface.canvas.drawImageRect(image,Rect.makeWH(surface.width.toFloat(),surface.height.toFloat()))
        surface.makeImageSnapshot()
    }
}
private fun thumbnailUrl(c:CoverCandidate):String=when(c.source){
    CoverSource.NETEASE->c.imageUrl.substringBefore('?')+"?param=320y320"
    CoverSource.QQ->c.imageUrl.replace("T002M000","T002R300x300M000")
    CoverSource.APPLE->c.imageUrl.replace(Regex("/[0-9]+x[0-9]+([^/]*)$"),"/320x320$1")
    else->c.imageUrl
}
@Composable internal fun CoverImageView(candidate:CoverCandidate?,studio:CoverStudio?,modifier:Modifier,original:ByteArray?=null,large:Boolean=false,scale:ContentScale=ContentScale.Crop) {
    val key=candidate?.key.orEmpty()+candidate?.localFile.toString()+large+original?.contentHashCode()
    var image by remember(key){mutableStateOf<SkImage?>(null)}
    var failed by remember(key){mutableStateOf(false)}
    LaunchedEffect(key) {
        var opened:SkImage?=null;var handed=false
        try {
            val created=runInterruptible(Dispatchers.IO){
                val bytes=when{
                    candidate?.localFile!=null->Files.readAllBytes(candidate.localFile)
                    candidate!=null&&studio!=null&&large->Files.readAllBytes(studio.image(candidate))
                    candidate!=null&&studio!=null->studio.http.bytes(thumbnailUrl(candidate),4_000_000)
                    else->original
                } ?: return@runInterruptible null
                rasterImage(bytes,if(large)640 else 320).also{opened=it}
            }
            ensureActive();image=created;handed=true
        }catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){failed=true}
        finally{if(!handed)opened?.close()}
    }
    DisposableEffect(key){onDispose{image?.close()}}
    Box(modifier.background(Color(0x12222222)),contentAlignment=Alignment.Center) {
        val frame=image
        if(frame!=null)Image(frame.toComposeImageBitmap(),null,Modifier.fillMaxSize(),contentScale=scale)
        else StudioGlyph(Glyph.PHOTO,Color(0xff8b9299).copy(alpha=if(failed).45f else .25f),Modifier.size(32.dp))
    }
}
@OptIn(InternalComposeApi::class)
@Composable internal fun CustomCoverImage(call:UiHookCall,studio:CoverStudio) {
    val path=runCatching{HostUiBridge.coverPath(call)}.getOrNull()
    val saved=path?.let{studio.store.find(it)}
    if(saved==null){HostUiBridge.originalCoil(call,currentComposer);return}
    val poster=studio.store.poster(saved)
    val candidate=CoverCandidate(CoverSource.LOCAL,saved.poster,"","","","",localFile=poster)
    CoverImageView(candidate,studio,call.getArgument(1) as Modifier,scale=HostUiBridge.contentScale(call))
}

@Composable internal fun CoverStudioHost(studio:CoverStudio) {
    val track=studio.track ?: return
    key(track.id,track.path){
        val p=hostPalette();val scope=rememberCoroutineScope()
        val density=LocalDensity.current
        val windowSize=LocalWindowInfo.current.containerSize
        val panelWidth=with(density){if(windowSize.width>0)minOf(1040.dp,(windowSize.width.toDp()-48.dp).coerceAtLeast(640.dp))else 1040.dp}
        val panelHeight=with(density){if(windowSize.height>0)minOf(738.dp,(windowSize.height.toDp()-64.dp).coerceAtLeast(500.dp))else 738.dp}
        var query by remember{mutableStateOf("${track.albumKey.artist} ${track.album}".trim())}
        var songs by remember{mutableStateOf(false)}
        var submitted by remember{mutableStateOf(query)}
        var submittedSongs by remember{mutableStateOf(false)}
        var request by remember{mutableIntStateOf(0)}
        var candidates by remember{mutableStateOf<List<CoverCandidate>>(emptyList())}
        var statuses by remember{mutableStateOf<Map<CoverSource,String>>(emptyMap())}
        var selected by remember{mutableStateOf<CoverCandidate?>(null)}
        var filter by remember{mutableStateOf<CoverSource?>(null)}
        var wholeAlbum by remember{mutableStateOf(false)}
        var busy by remember{mutableStateOf(false)}
        var message by remember{mutableStateOf("")}
        var original by remember{mutableStateOf<ByteArray?>(null)}
        fun submit(){if(query.isNotBlank()&&!busy){submitted=query.trim();submittedSongs=songs;request++}}
        LaunchedEffect(track.id){original=withContext(Dispatchers.IO){runCatching{WorkshopApi.library.getCoverById(track.id).toCompletableFuture().get(8,java.util.concurrent.TimeUnit.SECONDS)}.getOrNull()}}
        LaunchedEffect(request){
            candidates=candidates.filter{it.source==CoverSource.LOCAL};statuses=CoverSource.entries.filter{it!=CoverSource.LOCAL}.associateWith{"搜索中…"}
            supervisorScope{
                for(source in listOf(CoverSource.APPLE,CoverSource.NETEASE,CoverSource.QQ))launch{
                    try{
                        val result=runInterruptible(Dispatchers.IO){studio.search.search(source,submitted,track,studio.runtime.settings.storefronts,submittedSongs)}
                        ensureActive();candidates=(candidates.filter{it.source!=source}+result).distinctBy{it.key}
                        statuses=statuses+(source to if(result.isEmpty())"未找到"else"${result.size} 个结果")
                    }catch(c:CancellationException){throw c}
                    catch(e:Exception){statuses=statuses+(source to "暂不可用");studio.log.log("Cover search source=$source failed: ${e.javaClass.simpleName}")}
                }
            }
        }
        fun apply(embed:Boolean){
            if(busy)return
            val candidate=selected?:return;val albumScope=wholeAlbum;busy=true;message=if(embed)"正在备份并写入歌曲…"else"正在保存封面…"
            scope.launch{
                try{val n=runInterruptible(Dispatchers.IO){studio.apply(track,albumScope,candidate,embed)};message=if(embed)"已写入 $n 首歌曲，并保留写入前备份"else"已为 $n 首歌曲应用封面"}
                catch(c:CancellationException){throw c}
                catch(e:Exception){
                    studio.log.log("Cover save failed embedded=$embed: ${studio.log.describe(e)}")
                    message=e.message?:"保存失败，请稍后重试"
                }
                finally{busy=false}
            }
        }
        fun importLocal(){
            if(busy)return
            val dialog=FileDialog(null as java.awt.Frame?,"导入专辑封面",FileDialog.LOAD)
            dialog.file="*.jpg;*.jpeg;*.png;*.webp;*.gif"
            dialog.isVisible=true
            val file=dialog.file?.let{FilePath.of(dialog.directory,it)};dialog.dispose()
            if(file!=null)scope.launch{
                try{
                    val poster=runInterruptible(Dispatchers.IO){require(Files.size(file)<=24_000_000){"请选择小于 24 MB 的图片"};studio.store.importImage(Files.readAllBytes(file))}
                    val c=CoverCandidate(CoverSource.LOCAL,poster.fileName.toString(),file.fileName.toString(),"本地图片","","",localFile=poster)
                    candidates=(listOf(c)+candidates).distinctBy{it.key};selected=c;filter=null;message="已导入，点击应用封面后保存"
                }catch(c:CancellationException){throw c}
                catch(e:Exception){message=e.message?:"无法导入这张图片"}
            }
        }
        Dialog(onDismissRequest={if(!busy)studio.dismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)){
            StudioPanel(track,p,query,{query=it},songs,{value->
                val oldDefault="${track.albumKey.artist} ${if(songs)track.title else track.album}".trim()
                if(query==oldDefault)query="${track.albumKey.artist} ${if(value)track.title else track.album}".trim()
                songs=value
            },::submit,candidates,statuses,filter,{filter=it},selected,{if(!busy){selected=it;message=""}},
                wholeAlbum,{if(!busy){if(it&&!track.albumKey.valid)message="本地专辑或艺人标签缺失，请选择当前歌曲"else wholeAlbum=it}},busy,message,original,studio,{studio.dismiss()},::importLocal,{apply(false)},{apply(true)},{
                    val albumScope=wholeAlbum
                    scope.launch{busy=true;try{runInterruptible(Dispatchers.IO){studio.store.reset(studio.paths(track,albumScope))};message="已取消插件自定义，歌曲文件中的封面保持原样";selected=null}
                    catch(c:CancellationException){throw c}catch(e:Exception){message=e.message?:"无法取消自定义"}finally{busy=false}}
                },panelWidth,panelHeight)
        }
    }
}

@OptIn(InternalComposeApi::class)
@Composable internal fun StudioPanel(track:SelectedTrack,p:StudioPalette,query:String,onQuery:(String)->Unit,songs:Boolean,onSongs:(Boolean)->Unit,onSearch:()->Unit,
    candidates:List<CoverCandidate>,statuses:Map<CoverSource,String>,filter:CoverSource?,onFilter:(CoverSource?)->Unit,selected:CoverCandidate?,onSelect:(CoverCandidate)->Unit,
    wholeAlbum:Boolean,onWholeAlbum:(Boolean)->Unit,busy:Boolean,message:String,original:ByteArray?,studio:CoverStudio?,onClose:()->Unit,onImport:()->Unit,onApply:()->Unit,onEmbed:()->Unit,onReset:()->Unit,width:Dp=1040.dp,height:Dp=738.dp) {
    var motionPane by remember { mutableStateOf(false) }
    // The host supplies MiSans, system or custom fonts through SaltTheme. Dialogs inherit it.
    CompositionLocalProvider(LocalStudioFontFamily provides HostUiBridge.textStyle(currentComposer).fontFamily) {
    Column(Modifier.width(width).height(height).clip(RoundedCornerShape(16.dp)).background(p.background).border(1.dp,p.stroke,RoundedCornerShape(16.dp)).padding(24.dp)){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(p.highlight.copy(alpha=.12f)),contentAlignment=Alignment.Center){StudioGlyph(Glyph.PHOTO,p.highlight,Modifier.size(23.dp))}
            Column(Modifier.weight(1f)){Label("专辑封面自定义",p,20,weight=FontWeight.SemiBold);Label("${track.title}  ·  ${track.albumKey.artist}",p,12,true)}
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(7.dp)).clickable(enabled=!busy,onClick=onClose),contentAlignment=Alignment.Center){StudioGlyph(Glyph.CLOSE,p.secondary)}
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            Row(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(9.dp)).background(p.card).border(1.dp,p.stroke,RoundedCornerShape(9.dp)).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                StudioGlyph(Glyph.SEARCH,p.secondary,Modifier.size(18.dp))
                BasicTextField(query,onQuery,Modifier.weight(1f),enabled=!busy,singleLine=true,textStyle=TextStyle(fontFamily=LocalStudioFontFamily.current,color=p.text,fontSize=14.sp),cursorBrush=androidx.compose.ui.graphics.SolidColor(p.highlight),
                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={onSearch()}),decorationBox={inner->if(query.isBlank())Label("输入歌手、歌名或专辑名",p,14,true);inner()})
            }
            Action(if(songs)"按歌曲"else"按专辑",p,{onSongs(!songs)},compact=false,enabled=!busy)
            Action("搜索",p,onSearch,Glyph.SEARCH,primary=true,enabled=query.isNotBlank()&&!busy)
            Action("本地导入",p,onImport,Glyph.IMPORT,enabled=!busy)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
            SourceChip("全部",filter==null,p){onFilter(null)}
            for(source in listOf(CoverSource.APPLE,CoverSource.NETEASE,CoverSource.QQ))SourceChip(source.label,filter==source,p){onFilter(if(filter==source)null else source)}
            Spacer(Modifier.weight(1f));Label("关键词可自由修改",p,11,true)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(22.dp)){
            Column(Modifier.weight(1f).fillMaxHeight()){
                val visible=candidates.filter{filter==null||it.source==filter}
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    SourceChip("静态封面 ${visible.count{!it.animated}}",!motionPane,p){motionPane=false}
                    SourceChip("动态封面 ${visible.count{it.animated}}",motionPane,p){motionPane=true}
                    Spacer(Modifier.weight(1f))
                    if(motionPane)Label("静音循环",p,10,true)
                }
                Spacer(Modifier.height(12.dp))
                key(motionPane,filter){
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())){
                    val list=visible.filter{it.animated==motionPane}
                    if(list.isEmpty()){
                        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(10.dp)).background(p.card.copy(alpha=.7f)),contentAlignment=Alignment.Center){
                            Label(if(statuses.values.any{it=="搜索中…"})"正在搜索封面…"else if(motionPane)"暂未找到动态封面"else"没有搜索结果，试试修改关键词或本地导入",p,12,true,maxLines=2,modifier=Modifier.padding(18.dp))
                        }
                    } else for(row in list.chunked(3)){
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                            for(c in row)CoverCard(c,track,p,studio,selected?.key==c.key,Modifier.weight(1f)){onSelect(c)}
                            repeat(3-row.size){Spacer(Modifier.weight(1f))}
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    for(source in listOf(CoverSource.APPLE,CoverSource.NETEASE,CoverSource.QQ))Label("${source.label} · ${statuses[source]?:"等待搜索"}",p,10,true)
                }
                Spacer(Modifier.height(8.dp))
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(p.stroke.copy(alpha=.65f)))
            Column(Modifier.width(252.dp).fillMaxHeight().verticalScroll(rememberScrollState())){
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Label("封面预览",p,14,weight=FontWeight.SemiBold);Spacer(Modifier.weight(1f));Label(if(selected==null)"当前封面"else"待应用",p,11,true)}
                Spacer(Modifier.height(12.dp))
                Box(Modifier.width(224.dp).align(Alignment.CenterHorizontally).aspectRatio(1f).clip(RoundedCornerShape(11.dp)).border(1.dp,p.stroke,RoundedCornerShape(11.dp))){
                    CoverImageView(selected,studio,Modifier.fillMaxSize(),original,true)
                    if(selected?.animated==true&&studio!=null)AnimatedCoverPreview(selected,studio,track,Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(16.dp))
                Label(selected?.album?:track.album,p,16,weight=FontWeight.SemiBold,maxLines=2)
                Label(selected?.artist?:track.albumKey.artist,p,12,true,maxLines=2)
                Spacer(Modifier.height(10.dp))
                Label(selected?.source?.label?:"歌曲内嵌封面",p,12,true)
                Label(if(selected?.animated==true)"动态视频 + 静态封面"else"静态专辑封面",p,11,true)
                if(selected?.width!=null)Label("${selected.width} × ${selected.height} 像素",p,11,true)
                Spacer(Modifier.height(18.dp))
                Label("应用范围",p,12,true)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                    SourceChip("当前歌曲",!wholeAlbum,p){onWholeAlbum(false)}
                    SourceChip("同专辑歌曲",wholeAlbum,p){onWholeAlbum(true)}
                }
                Spacer(Modifier.height(10.dp))
                Label("写入歌曲仅保存图片；动态视频保留在插件缓存中。",p,11,true,maxLines=2)
            }
        }
        Spacer(Modifier.height(14.dp));Box(Modifier.fillMaxWidth().height(1.dp).background(p.stroke));Spacer(Modifier.height(14.dp))
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)){
            Column(Modifier.weight(1f)){
                Label(if(message.isBlank())"选择封面后应用，或单独写入歌曲文件"else message,p,12,true,maxLines=2)
                if(message.isBlank())Label("写入前自动备份，并校验音频数据与原有标签",p,10,true)
            }
            Action("取消自定义",p,onReset,Glyph.RESET,enabled=!busy,compact=true)
            Action("写入歌曲",p,onEmbed,Glyph.WRITE,enabled=selected!=null&&!busy)
            Action(if(busy)"处理中…"else"应用封面",p,onApply,Glyph.CHECK,primary=true,enabled=selected!=null&&!busy)
        }
    }
    }
}
@Composable private fun SourceChip(text:String,active:Boolean,p:StudioPalette,onClick:()->Unit){
    val shape=RoundedCornerShape(7.dp)
    Box(Modifier.clip(shape).background(if(active)p.highlight.copy(alpha=.14f)else Color.Transparent).border(1.dp,if(active)p.highlight.copy(alpha=.35f)else Color.Transparent,shape).clickable(onClick=onClick).padding(horizontal=10.dp,vertical=6.dp)){
        BasicText(text,style=TextStyle(fontFamily=LocalStudioFontFamily.current,color=if(active)p.highlight else p.secondary,fontSize=12.sp,fontWeight=if(active)FontWeight.Medium else FontWeight.Normal))
    }
}
@Composable private fun CoverCard(c:CoverCandidate,track:SelectedTrack,p:StudioPalette,studio:CoverStudio?,active:Boolean,modifier:Modifier,onClick:()->Unit){
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(if(active)p.highlight.copy(alpha=.07f)else Color.Transparent).border(if(active)2.dp else 1.dp,if(active)p.highlight else p.stroke.copy(alpha=.5f),RoundedCornerShape(10.dp)).clickable(onClick=onClick).padding(7.dp)){
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp))){
            CoverImageView(c,studio,Modifier.fillMaxSize())
            if(c.animated)Box(Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha=.7f)).padding(horizontal=6.dp,vertical=3.dp)){
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(3.dp)){StudioGlyph(Glyph.PLAY,Color.White,Modifier.size(11.dp));BasicText("动态",style=TextStyle(fontFamily=LocalStudioFontFamily.current,color=Color.White,fontSize=10.sp))}
            }
            if(active)Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(21.dp).clip(RoundedCornerShape(20.dp)).background(p.highlight),contentAlignment=Alignment.Center){StudioGlyph(Glyph.CHECK,p.onHighlight,Modifier.size(14.dp))}
        }
        Spacer(Modifier.height(7.dp));Label(c.album,p,12,weight=FontWeight.Medium);Label(c.artist,p,10,true)
        Spacer(Modifier.height(4.dp));Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Label(c.source.label,p,9,true);Spacer(Modifier.weight(1f));if(c.exact(track))BasicText("一致",style=TextStyle(fontFamily=LocalStudioFontFamily.current,color=p.highlight,fontSize=9.sp))
        }
    }
}
