# Salt Motion 设计与验证

0.1.9 新增歌曲菜单与封面编辑器，当前接入、关键词搜索、图片覆盖及元数据写入约束见 [封面编辑器](cover-studio.md)。下文保留沿用的动态封面历史约束。

## 0.1.7 的 AM 目录主搜索

本节记录沿用的 0.1.7 搜索行为。当前版本新增实现见 [缓存与取帧说明](cache-and-frames.md)。搜索、回退及验证依据在 [搜索改动](search.md) 单处维护；最高分辨率和私有 CPU 软件渲染路径保持 0.1.6 的实现。下节保留高清版本的背景。

## 0.1.6 的最高分辨率

0.1.6 的插件身份、配置及 24 / 30 FPS 两档沿用 0.1.5。用户明确要求采用 AM 网页最高分辨率，因此原先为帧率限制 H.264、约 512px 源和固定 512×512 输出的选择已被替代。HLS 选流按像素面积、平均码率依次取最大，允许 HEVC / AV1，不再因编码类型降低分辨率；仍只接受方形 SDR 普通视频及 Apple HTTPS CDN，不接入竖版、HDR 或 I-frame 素材。完整选流元数据传入会话、解码器和软件渲染器，避免只下载高清却仍输出低清。具体取舍及实测见 [高清验证](high-resolution.md)。

旧版安装包以版本号分别保存，不改插件 ID。0.1.4、0.1.5 的哈希在 previous-packages.json 留存，发布时逐一核对。用户自行导入和验收播放器窗口，本次不修改已安装插件或配置。

## 0.1.5 的 30 FPS 优化

0.1.5 源码在相邻版本目录保留，插件 ID / 命名空间沿用 dev.saltmotion，名称仍为 Salt Motion，原配置及手动绑定可继续使用。0.1.4 源码和安装文件保留；0.1.5 另用包含版本号的文件名发布。播放器导入时更新同一个插件，不创建可并存启用的新插件身份。此前将“版本分别保留”理解为独立插件并创建 dev.saltmotion30 / 0.2.0 的方案已撤回。

帧率仅 24 / 30，默认 30。FrameSchedule 用单调时钟和纳秒余数累计，先获取下一帧，再按绝对截止时间交付；取帧耗时计入周期，长停顿后重置截止时间，避免积压后的突发补帧。绘制节点每十秒统计提交帧和真正绘制过的不同交付帧，重复绘制同一帧只计一次，窗口失焦/换专辑时重置。

HLS 选择同时保留选中视频的 FRAME-RATE，加载时记录源帧率和目标帧率。较低帧率素材保持自然速度，不重复交付相同视频帧来制造 30 FPS 读数。具体素材与测量见 [帧率报告](performance.md)。

原版实测：AM 24K Magic 的清单为 30.000 FPS，screenshot-raw 加完整 sleep 的循环实际约 15 FPS。先将等待改为截止时间，截图链路仍只有约 28 FPS；完全去掉等待也未稳定达到 30，所以没有把修改设置等同于刷新优化。使用 timeBeginPeriod(1) 的隔离对照未改善，已匹配释放，未纳入插件。

最终换用 mpv 独立软件渲染上下文的帧就绪回调，减少逐帧同步截图请求。回调只释放信号，不调用任何 mpv API；读取只执行 render API，关闭先撤销回调、释放 render context，再销毁 core 和 CPU 缓冲。未启用 ADVANCED_CONTROL，也不创建或共享 GPU Surface。参数 ABI 和生命周期依据 [mpv render.h](https://github.com/mpv-player/mpv/blob/master/include/mpv/render.h)；原版因共享视频 Surface 导致退出的约束仍成立，不因名称中有 render 就重用旧 GPU 路径。

下述 0.1.x 验证记录是原版历史，用于保留宿主兼容性和权限约束；本版性能验证在文末单独记录。

0.1.5 验证（2026-10-25）：完整 JDK 和播放器运行时各 36 项测试通过，包隔离及宿主链连接通过；实际 AM 流的软件渲染、重复释放和帧率验证见 [历史帧率报告](performance.md)。旧包指纹未变。未接管实际播放器界面；窗口帧率通过新诊断日志留给用户检查。

## 宿主封面接入

SPW dev23 没有公开的动态封面替换接口。实际宿主中，方形和圆形封面在同一 FullScreenCover 编译类内调用同一个 Image 重载，直接 replaceComposable 会因「2 calls」而被拒绝；私有 Composable 的重组回调也产生重复调用。普通 hookMethod 则明确拒绝 Composer 参数，因此不能只确认 JVM 签名存在就认为可以注册。

当前选择：Hook 两个返回 Modifier 的普通函数，仅当调用栈的类、方法、完整描述符和字节码位置都匹配已核实封面时，连接独立绘制节点。原生 Image 继续管理布局、圆角、圆形裁剪、旋转和 alpha。先按 lambda 所属类筛选，避免对普通曲库列表的所有调用进行栈扫描。

当前登记 Steam 1.19.2。调用类为 androidx.compose.ui.ȇ（U+0207），其类 SHA-256、两个方法描述符和 BCI 在 [CoverTarget](../src/main/kotlin/dev/saltmotion/hook/CoverTarget.kt) 单处维护。方形入口为 drawWithCache，圆形入口为 graphicsLayer。verify-host.py 从该契约读取数据并核对实际 Code 指令；VerifyHost.java 用真实宿主解析器验证两种普通方法签名。资源检查不预加载待接入类。

0.1.1 在真正的 Hook 回调中读取 StackFrame.descriptor 时会抛出 UnsupportedOperationException：JDK 25 要求 StackWalker 显式开启 RETAIN_CLASS_REFERENCE。宿主拦截 Hook 异常后仍显示插件已启用，动画节点实际未挂载，手动绑定同样无效。0.1.2 保留原有调用点校验，只在遍历期间允许方法签名访问；栈帧不持久缓存，不预加载宿主封面类。回归必须执行实际栈遍历，原先纯字符串 callsite 测试无法覆盖此约束。

0.1.2 窗口日志随后证实调用点已命中但节点仍未挂载。实际 Steam 包的 WorkshopMethodAdvice.exit 字节码对 getResult() 只执行 POP，没有写回 @Advice.Return 参数，因此 setResult 不改变真实返回值。0.1.3 用 [CoverModifier](../src/main/kotlin/dev/saltmotion/hook/CoverModifier.kt) 连接节点：这两个调用都先创建 receiver.then(new Element)，Hook 只在返回对象为 CombinedModifier、outer 与原 receiver 引用相同、inner 为准确预期元素时，把新对象的 inner 改为 inner.then(animation)。宿主仍返回同一个对象；原 receiver、共享节点及已挂载节点均不修改。字段操作仅发生在新对象暴露给 Compose 之前，依赖 JDK 25 对普通实例 final 字段的反射支持；结构不符就保留静态封面并记录日志。没有修改宿主文件或 Advice，也未扩展全局 Hook 权限。真实宿主 probe 必须分别验证方形/圆形返回对象内含动画元素、原 receiver 未变和重复/错误连接被拒绝；仅创建 MotionCoverElement 不足以证明接入。

代价：依赖精确宿主字节码和 JVM 调用栈，升级必须复核。版本或类字节不同则不注册。静态及解析器验证不证明宿主窗口中的栈、裁剪和交互已通过；以后若有正式封面接口应优先替换此适配。

## AM 身份与传输

自动识别使用 albumArtist（缺失时 artist）和 album，搜索结果必须规范化后完整相等且 ID 唯一，不删除版本词。手动绑定可明确选择其他语言或版本，但资源类型、返回 ID 和 Apple 专辑 URL 必须一致。只读取目标专辑的方形 editorialVideo，不借用推荐专辑或竖版素材。

developerToken 在运行时从 Apple 公开 Web 应用发现，只存内存，按到期时间保留一分钟裕量且最多缓存一小时。401/403 后从新页面重新发现一次；不使用硬编码 token，不读取用户账号凭据，不在日志中写入 bearer、bundle 或请求头。

0.1.0 的联网验证中，HttpURLConnection 能获取网页，但目录请求返回 401；换为显式发送 Web Origin 的 JDK HttpClient 后，请求返回 200。然而当时使用完整 JDK 验证，未覆盖宿主裁剪运行时。用户的启用崩溃随后证实 SPW 1.19.2 的 runtime/release 没有 java.net.http 模块，原方案的可用前提在宿主不成立。

0.1.1 改用随包分发的 OkHttp 4.12.0 / Okio 3.9.1。它们保留 Origin、响应大小限制和整个请求的取消期限，关闭时连同尚未提交给 dispatcher 的请求一起取消并回收连接池。重定向逐跳校验 Apple 域名，带 bearer 请求不跟随重定向；Kotlin 仍由宿主提供，不重复打包。

还验证过 Apache HttpClient 5.6.5：完整 JDK 测试通过，但实际裁剪运行时在连接管理器初始化时因缺少 jdk.net.Sockets 失败，因此未采用。回归与包检查同时排除 java.net.http 和 jdk.net 依赖，不能仅依据库声明的最低 Java 版本选择网络层。也未选择改造播放器运行时、丢失 Origin 的旧传输或修改 JVM 全局 restricted-header 属性。

最多缓存 64 个结果：成功六小时，空结果或失败一分钟，地区和绑定属于缓存键。HLS 按本页 0.1.6 规则选择最高分辨率；平均码率缺失时用峰值码率比较。边长限制 128..4096，超过内存保护边界或无合适流时保留静态封面，不把不支持的素材静默缩小。

## 解码、生命周期与验证边界

Salt Artist 曾记录共享视频 Surface/纹理路径引起退出，因此使用独立 libmpv C API handle、CPU 解码和普通 Skia raster Image，不创建 Surface，不使用 MediaMP JNI wrapper 地址充当 C handle。宿主 factory 仅用于加载已有 native 库，临时会话随即关闭。解码静音、禁用音轨及用户配置。

软件渲染按 HLS 所选尺寸输出 bgr0，CPU 地址及行 stride 对齐 64 字节。FrameLayout 在分配前验证尺寸和整数运算；2160×2160 的 stride 为 8640，每帧像素 18,662,400 字节。有行填充时去除填充，再交付独立 BGRA 数组并补全 alpha，不复用已发布数组。渲染与关闭互斥，回调只使用信号量；读帧等待最多 100ms。绘制、替换和图像释放继续共享锁，native 销毁放到后台。DecoderPool 最多两个 native 解码器，销毁完成后才释放槽位。

节点挂载启动监听，卸载立即取消任务及退休会话；焦点和配置约 350ms 复核一次。换专辑、设置、焦点或卸载时推进 generation，拒绝晚到结果；绘制时再次核对当前专辑。失败至少间隔一分钟重试。音乐暂停时动画不强制暂停。

可复核输出在 build/reports/tests/test 和 build/reports/motion-smoke。包检查脚本确认描述符、权限、配置和许可正确，未携带宿主及测试依赖。隔离 probe 不附加到正在运行的 SPW，也不修改安装文件：

    $hostApp = 'C:\path\to\Salt Player for Windows\app'
    $pluginJar = (Resolve-Path 'build/intermediates/spmod/salt-motion-0.1.6.jar').Path
    $probeClasspath = "$pluginJar;$hostApp\ffmpeg-x64.dll"
    New-Item -ItemType Directory -Force build/probe,build/reports/motion-smoke | Out-Null
    & "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 -cp $probeClasspath -d build/probe scripts/VerifyHost.java scripts/SmokeProbe.java
    & "$env:JAVA_HOME\bin\java.exe" --enable-native-access=ALL-UNNAMED -cp "$probeClasspath;build/probe" VerifyHost "$hostApp\ffmpeg-x64.dll"

上述 VerifyHost 只检查 Hook 目标，SmokeProbe 还需包内 HTTP 依赖。完整运行时验证请运行 scripts/verify-runtime.ps1 -Online；它将原安装 runtime 复制到 build/runtime-check，仅在副本加入同版 java.exe，再用 PF4J PluginClassLoader 从最终包的 classes 和 lib 中解析依赖。启动探针明确断言 java.net.http 不存在，并覆盖 AppleHttp 和 MotionRuntime 构造、关闭、自动匹配及三轮实际帧读取。

SmokeProbe 尝试自动匹配和指定 ID，要求三轮各取得 15 帧、每轮至少两种画面，并编码一张 raster PNG。这证明素材和独立解码路径；宿主中的 Hook 生效、显示及停用恢复仍需窗口验收。

0.1.0 历史验证（2026-10-25）：完整 JDK 下 23 项测试通过、三轮各 15 帧通过，但没有覆盖播放器裁剪运行时，未能发现随后报告的启用崩溃。

0.1.1 验证（2026-10-25）：在播放器实际 runtime 的副本中复现了 0.1.0 的 NoClassDefFoundError。新版 30 项测试分别在完整 JDK 和该 runtime 中通过；PF4J 从最终包独立加载 OkHttp、Okio 并成功创建/关闭 AppleHttp 和 MotionRuntime；AM 目录实际返回 200，自动匹配 folklore，三轮各 15 个变化帧及 raster 编码通过。安装目录未修改。包检查排除了可选网络模块引用及重复宿主依赖。播放器窗口中的完整显示与交互仍需覆盖更新后验收。

0.1.2 验证（2026-10-25）：实际播放器已启用 0.1.1、手动绑定 24K Magic 时仍为静态封面，日志无加载记录；临时诊断确认 Hook 收到封面 lambda 后中断。在独立 JDK 25 复现 descriptor 访问抛异常，修复后实际栈遍历回归通过；完整 JDK 和实际裁剪运行时均通过 31 项测试。窗口自动化在替换最终 0.1.2 前因 Esc 停止，因此最终包的宿主显示尚未验收；播放器当前仍为临时诊断版 0.1.1。

0.1.3 验证（2026-10-25）：用户随后安装 0.1.2，实际日志命中封面入口，但没有节点挂载。确认普通 Hook 返回值写回问题后，完整 JDK / 播放器精简运行时各 31 项测试通过；最终包的 PF4J probe 用真实宿主类验证方形、圆形连接及结构保护，包检查与精确调用点校验通过。用户启用后，实际日志确认节点挂载并执行 draw；随后 getCurrentMediaItem 抛出 PluginPermissionDeniedException，明确缺少 library-read，故尚未发起 AM 查询。该信息读取接口也受音乐库读取权限保护；独立 probe 未初始化实际播放 API，未覆盖授权。

0.1.4 声明 class-transform 与 library-read 两项权限，启动先通过同一 getCurrentMediaItem 接口检查实际授权；拒绝时显示明确提示并退出启动。仍通过宿主正常授权界面授予权限，不修改权限存储。包检查精确核对两项权限，防止构建产物遗漏或扩权；插件无需 library-write。

0.1.4 验证（2026-10-25）：完整 JDK 与播放器精简运行时各 31 项测试通过，最终包的 PF4J / 方形圆形链连接 probe 通过。包内两份清单均含 class-transform,library-read；构造遗漏 library-read 的包确认检查会拒绝。未操作播放器，交由用户更新并授权后检查实际动画；尚未把显示成功作为已验证结果。
