@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package dev.saltmotion

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import dev.saltmotion.editor.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.awt.EventQueue
import java.nio.file.*

/** Renders the actual Compose panel into images and exercises semantics in a private scene. */
object StudioRenderProbe {
    @JvmStatic fun main(args:Array<String>) {
        try {
            val root=Path.of(args[0]);Files.createDirectories(root)
            val imageRoot=Path.of(args[1])
            HostUiBridge.verify()
            // Supply the real host's SaltTheme local, with the same bundled MiSans resource.
            @Suppress("UNCHECKED_CAST")
            val textStylesLocal=Class.forName("androidx.compose.ui.\u02b6").getMethod("\u0529").invoke(null) as ProvidableCompositionLocal<Any>
            val stylesConstructor=Class.forName("androidx.compose.ui.\u02b2").getConstructor(TextStyle::class.java,TextStyle::class.java,TextStyle::class.java,TextStyle::class.java)
            val miSans=FontFamily(Font("composeResources/voxzen.composeapp.generated.resources/font/misans.ttf"))
            fun nativeStyles(font:FontFamily):Any {
                val style=TextStyle(fontFamily=font)
                return stylesConstructor.newInstance(style,style,style,style)
            }
            val images=Files.list(imageRoot).use{it.filter{f->f.toString().endsWith(".jpg")||f.toString().endsWith(".png")}.toList()}
            require(images.isNotEmpty())
            val track=SelectedTrack("probe","Thunderstruck","AC/DC","Iron Man 2","AC/DC","D:\\fixture.flac")
            val cards=listOf("APPLE-album","NETEASE-album","QQ-album").mapIndexed{i,name->
                val f=images.firstOrNull{it.fileName.toString().substringBeforeLast('.')==name}?:images[i%images.size]
                val metadata=java.util.Properties();val info=imageRoot.resolve("$name.properties")
                if(Files.exists(info))Files.newInputStream(info).use(metadata::load)
                CoverCandidate(CoverSource.entries[i],"fixture-$i",metadata.getProperty("album","Iron Man 2"),metadata.getProperty("artist","AC/DC"),"","",localFile=f)
            }+CoverCandidate(CoverSource.APPLE,"motion-fixture","24K Magic","Bruno Mars","","",motionRef="https://music.apple.com/us/album/24k-magic/1161503945",localFile=imageRoot.resolve("MOTION-24k-magic.png").takeIf{Files.exists(it)}?:images.last())
            for(light in listOf(false,true)){
                var scene:ImageComposeScene?=null
                var query by mutableStateOf("AC/DC Iron Man 2")
                var font by mutableStateOf<FontFamily>(miSans)
                var selected by mutableStateOf<CoverCandidate?>(cards.first())
                var searchCount=0;var applyCount=0;var embeddedCount=0
                val p=if(!light)StudioPalette()else StudioPalette(Color(0xff087fc1),Color(0xff202124),Color(0xff71767b),Color(0xfff5f5f5),Color.White,Color(0xffdedfe1),Color.White,Color(0xffb83d3d))
                EventQueue.invokeAndWait{
                    scene=ImageComposeScene(1040,738,Density(1f),LayoutDirection.Ltr,Dispatchers.Unconfined){
                        CompositionLocalProvider(textStylesLocal provides nativeStyles(font)) {
                        StudioPanel(track,p,query,{query=it},false,{}, {searchCount++}, cards,CoverSource.entries.associateWith{"已完成"},null,{},selected,{selected=it},false,{},false,"",null,null,{}, {},{applyCount++},{embeddedCount++},{})
                        }
                    }
                }
                repeat(12){Thread.sleep(80);EventQueue.invokeAndWait{scene!!.render(System.nanoTime()).close()}}
                fun descendants(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::descendants)
                fun nodes()=scene!!.semanticsOwners.flatMap{descendants(it.rootSemanticsNode)}
                fun checkFonts(expected:FontFamily) {
                    val layouts=nodes().flatMap { node ->
                        val results=mutableListOf<TextLayoutResult>()
                        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                        results
                    }
                    check(layouts.size>=20){"Missing rendered text layouts: ${layouts.size}"}
                    for(layout in layouts)check(layout.layoutInput.style.fontFamily==expected){"Font mismatch: ${layout.layoutInput.text}"}
                }
                fun click(label:String){
                    val n=nodes().first{node->node.config.getOrNull(SemanticsProperties.Text)?.any{it.text==label}==true&&node.config.getOrNull(SemanticsActions.OnClick)!=null}
                    check(n.config[SemanticsActions.OnClick].action!!.invoke())
                }
                EventQueue.invokeAndWait{
                    checkFonts(miSans)
                    font=FontFamily.Monospace
                }
                repeat(4){Thread.sleep(60);EventQueue.invokeAndWait{scene!!.render(System.nanoTime()).close()}}
                EventQueue.invokeAndWait{
                    checkFonts(FontFamily.Monospace)
                    font=miSans;query=""
                }
                repeat(4){Thread.sleep(60);EventQueue.invokeAndWait{scene!!.render(System.nanoTime()).close()}}
                EventQueue.invokeAndWait{
                    checkFonts(miSans)
                    val editable=nodes().first{it.config.getOrNull(SemanticsActions.SetText)!=null}
                    check(editable.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Nirvana Come As You Are")))
                    scene!!.render(System.nanoTime()).close()
                    check(query=="Nirvana Come As You Are")
                    click("搜索");click("应用封面");click("写入歌曲")
                    check(searchCount==1&&applyCount==1&&embeddedCount==1)
                    query="AC/DC Iron Man 2"
                    click("动态封面 1")
                }
                repeat(10){Thread.sleep(80);EventQueue.invokeAndWait{scene!!.render(System.nanoTime()).close()}}
                EventQueue.invokeAndWait{
                    checkFonts(miSans)
                    scene!!.render(System.nanoTime()).use{frame->frame.encodeToData(EncodedImageFormat.PNG)!!.use{data->Files.write(root.resolve(if(light)"cover-studio-light-motion.png"else"cover-studio-dark-motion.png"),data.bytes)}}
                    click("静态封面 3")
                }
                repeat(10){Thread.sleep(80);EventQueue.invokeAndWait{scene!!.render(System.nanoTime()).close()}}
                EventQueue.invokeAndWait{
                    scene!!.render(System.nanoTime()).use{frame->frame.encodeToData(EncodedImageFormat.PNG)!!.use{data->Files.write(root.resolve(if(light)"cover-studio-light.png"else"cover-studio-dark.png"),data.bytes)}}
                    scene!!.close()
                }
                println("PASS ${if(light)"light"else"dark"} Compose panel; all text/input follows host MiSans; live font switch; editable keywords; independent search/apply/embed actions")
            }
            val runtime=MotionRuntime(PluginLog(null),MotionSettings(),root.resolve("native-scene/motion-cache"))
            try{
                val studio=runtime.studio!!
                val poster=studio.store.importImage(Files.readAllBytes(images.first()))
                studio.store.apply(listOf("D:\\cover-scene-fixture.flac"),poster,"Fixture",null)
                val factory=Class.forName("NativeCoverCall")
                val call=factory.getMethod("create",Modifier::class.java).invoke(null,Modifier.size(160.dp)) as com.xuncorp.spw.workshop.api.hook.UiHookCall
                var scene:ImageComposeScene?=null
                EventQueue.invokeAndWait{
                    scene=ImageComposeScene(300,240,Density(1f),LayoutDirection.Ltr,Dispatchers.Unconfined){
                        val p=hostPalette()
                        Column(Modifier.fillMaxSize().background(p.background).padding(12.dp)){
                            NativeCoverMenuLabel();Spacer(Modifier.height(10.dp));CustomCoverImage(call,studio)
                        }
                    }
                }
                repeat(10){Thread.sleep(70);EventQueue.invokeAndWait{scene!!.render(System.nanoTime()).close()}}
                EventQueue.invokeAndWait{scene!!.render(System.nanoTime()).use{frame->frame.encodeToData(EncodedImageFormat.PNG)!!.use{Files.write(root.resolve("native-cover-entry-and-override.png"),it.bytes)}}}
                studio.store.reset(listOf("D:\\cover-scene-fixture.flac"))
                repeat(8){Thread.sleep(70);EventQueue.invokeAndWait{scene!!.render(System.nanoTime()).close()}}
                EventQueue.invokeAndWait{scene!!.render(System.nanoTime()).use{frame->frame.encodeToData(EncodedImageFormat.PNG)!!.use{Files.write(root.resolve("native-cover-entry-and-default.png"),it.bytes)}};scene!!.close()}
                val calls=(factory.getField("nativeLoaderCalls").get(null) as java.util.concurrent.atomic.AtomicInteger).get()
                check(calls>0)
                check(!Files.readAllBytes(root.resolve("native-cover-entry-and-override.png")).contentEquals(Files.readAllBytes(root.resolve("native-cover-entry-and-default.png"))))
                println("PASS native menu label, actual AudioCover/Vri/Coil models, live static override and original-renderer fallback after reset")
                if(Files.exists(root.resolve("native-scene/motion-cache/1161503945/video.m3u8"))){
                    val motion=cards.last()
                    val broken=track.copy(title="Wrong title",artist="",album="",albumArtist="")
                    var preview:ImageComposeScene?=null
                    EventQueue.invokeAndWait{preview=ImageComposeScene(256,256,Density(1f),LayoutDirection.Ltr,Dispatchers.Unconfined){AnimatedCoverPreview(motion,studio,broken,Modifier.fillMaxSize())}}
                    var first:ByteArray?=null
                    for(i in 0 until 100){
                        Thread.sleep(100)
                        EventQueue.invokeAndWait{preview!!.render(System.nanoTime()).use{frame->frame.encodeToData(EncodedImageFormat.PNG)!!.use{if(it.size>5000)first=it.bytes}}}
                        if(first!=null)break
                    }
                    check(first!=null){"No decoded editor preview"}
                    Files.write(root.resolve("editor-motion-first.png"),first)
                    repeat(15){Thread.sleep(100);EventQueue.invokeAndWait{preview!!.render(System.nanoTime()).close()}}
                    var last=byteArrayOf()
                    EventQueue.invokeAndWait{preview!!.render(System.nanoTime()).use{frame->frame.encodeToData(EncodedImageFormat.PNG)!!.use{last=it.bytes}};preview!!.close()}
                    Files.write(root.resolve("editor-motion-last.png"),last)
                    check(!first!!.contentEquals(last)){"Editor preview pixels did not change"}
                    println("PASS production animated preview; broken local tags; shared full-quality album cache; changing rendered pixels")
                }
            }finally{runtime.close()}
            System.exit(0)
        }catch(error:Throwable){error.printStackTrace();System.exit(2)}
    }
}
