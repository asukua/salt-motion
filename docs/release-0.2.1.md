# Salt Motion 0.2.1：模组菜单的开源链接

修复 Salt Player 模组管理中“开源链接”置灰的问题。Gradle spmod 配置现在声明 `PluginOpenSourceUrl`，最终安装包根目录及 classes 内两份 Manifest 均包含 `Plugin-Open-Source-Url: https://github.com/asukua/salt-motion`。

这项元数据供 SPW 模组菜单读取，与 Steam 网页描述中的链接无关。已安装的 0.2.0 缺少字段，需要正常更新 / 导入 0.2.1 后生效；本次没有改写已安装的包或播放器数据库。

已验证：

- 最终包的两份 Manifest 一致，开源链接为新仓库规范地址。
- SPW 1.19.2 实际宿主字节码会从该字段构造模组信息。
- 全部运行时 class 与 0.2.0 逐字节一致，只修改版本和开源链接元数据。
- SPW 精简运行时重新执行 66 项回归通过；完整 JDK 任务复用相同代码的通过缓存。
- 最终包经真实 PF4J 加载，播放页封面连接、菜单和 UI 目标检查通过。
- 所有历史安装包 SHA-256 未变化。

未重新测试在线封面检索或重做界面渲染；本次不改变相关实现。未发布或覆盖 Steam 工坊文件。
