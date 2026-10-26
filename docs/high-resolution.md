# 0.1.6：AM 网页最高分辨率

## 选择及代价

用户要求采用 AM 网页提供的最高分辨率。此前约 512px H.264 源及固定 512×512 输出是为软件解码和刷新率作出的限制，本版将其替换为按清单分辨率优先、同分辨率按最高平均码率选流。平均码率缺失时使用峰值码率。支持 H.264、HEVC、AV1；继续排除非方形、HDR、I-frame 和非 Apple CDN 地址。分配前的尺寸保护上限为 4096，不代表每张专辑都有该分辨率。

所选宽高贯穿 HLS、MotionRuntime 会话工厂、NativeFrames 和软件渲染器，不只提高下载流的清晰度。FrameLayout 对尺寸和溢出作检查，并分别计算像素行长、64 字节对齐 stride 和分配大小。有填充的行在交付前打包；每帧数据仍独立拥有，保持读帧/释放互斥及最多两个解码器的限制。

不采用直接 GPU Surface/纹理共享，原因仍是原有宿主兼容性和退出问题。隔离对照尝试过 mpv 的 hwdec=auto-copy，它将解码结果复制回系统内存，符合 [mpv 文档](https://mpv.io/manual/stable/#options-hwdec) 的 CPU 后处理路径；但单路 30 档仅从约 16 改善到约 17 FPS，仍未接近目标，本次未纳入生产包。此结果不用于声称播放器实际启用了某种硬件解码器。

24 / 30 FPS 两档及默认 30 保持不变；原有 24 档配置照常保留。最高画质优先会增加像素转换、复制、分配和绘制开销，不能沿用低清版约 30 FPS 的测量作为本版保证。

## 验证

2026-10-25，在 Ryzen 7 6800H（8 核 / 16 线程）上，使用 Steam SPW 1.19.2 的裁剪运行时副本、最终安装包和与播放器相同的 -Xmx512M。未附加或操作正在运行的播放器。

- 完整 JDK 和播放器运行时各 38 项测试通过，覆盖最高 HEVC、同尺寸码率排序、H.264 回退、HDR/I-frame/外部地址排除、尺寸/行填充及既有生命周期约束。
- 最终包的原插件身份、两项权限、PF4J 依赖加载及真实宿主方形/圆形修饰器链通过。
- folklore（1524793738）自动匹配及解码选中 2160×2160 HEVC，平均码率 24,775,956 bps。三轮各 15 帧均有 15 种不同像素；实际 raster PNG 为 2160×2160，首帧已查看。
- 八个 native 上下文反复创建/销毁、已交付字节不可变、读帧与关闭并发及重复关闭通过。0.1.4、0.1.5 包在发布前后核对哈希。

Bruno Mars — 24K Magic（1161503945）选中 2160×2160 HEVC，平均码率 25,616,140 bps，源帧率 30.000。使用生产 NativeFrames / FrameSchedule 取帧，将完整尺寸图像绘制到 486×486 的 Skia CPU raster 目标；每个样本八秒，并对像素校验以统计变化帧。

| 目标 FPS | 模式 | 实际变化帧/秒 | 帧间隔 P95 |
| ---: | --- | ---: | ---: |
| 24 | 单路 | 10.82 | 204.62 ms |
| 30 | 单路 | 15.95 | 108.14 ms |
| 30 | 双路 A | 12.17 | 179.28 ms |
| 30 | 双路 B | 11.84 | 177.10 ms |

这是隔离取帧及 raster 绘制结果，不能直接当作播放器窗口刷新率。短时测量也受联网和系统负载影响，24 档较低的结果不代表限制为 24 必然更慢。实际窗口仍由用户导入后检查，并结合 diagnostics.log 的 target / submitted / drawn 记录判断。此前低分辨率测量保留在 [历史帧率报告](performance.md)。

## 可复核输出

- build/reports/high-resolution-verification.log：最终包构建、38 项回归和运行时验证。
- build/reports/runtime-regression/packaged-runtime.log、first-frame.png：三轮变化帧、实际像素尺寸与生命周期。
- build/reports/frame-rate/24k-magic.log、24k-magic/summary.txt：生产软件解码性能。
- build/reports/frame-rate/copy-back.log、copy-back/summary.txt：未采用的复制回内存对照，实验类位于 build/experiments/copy-back。
- previous-packages.json：旧版安装包的预期指纹。

0.1.6 源码单独保存在 versions/0.1.6，安装文件按版本号另存。插件 ID 仍为 dev.saltmotion，安装时更新同一个 Salt Motion。
