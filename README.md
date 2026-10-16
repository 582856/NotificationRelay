# NotificationRelay

平板跨设备通知扩展，包名 `com.zephyr.notificationrelay`。

当前发布包含平板正式版 `0.1.25`、平板诊断版 `0.1.25-debug` 和手机端 [RelayQuiet](phone/README.md) `0.1.3`。更新内容见 [更新记录](CHANGELOG.md)。

## 功能

- 设置中的“跨设备通知”入口打开原厂通知管理页。
- LSPosed 的模块设置入口直接打开已关联手机的通知应用列表；多台手机或尚未关联时进入原厂设备选择页。
- 应用列表右上角的加号打开 Miuix 底部搜索面板，支持在线搜索并添加应用。
- 包名和图标保存在模块私有数据目录，供订阅列表、通知卡片、详情及状态栏使用。
- 普通流转通知的状态栏图标按来源应用分别显示，同一应用保留一个图标；显示数量沿用系统状态栏空间限制，焦点通知沿用超级岛规则。
- 多条通知合并后，通知中心的折叠组显示对应应用图标、右下角设备互联来源角标和消息数量角标，保留圆角。
- 长按新增应用可确认删除；已开启的应用先取消订阅，再清理缓存。
- 点击原厂镜像类型的流转通知，通过原厂应用串流入口打开手机应用，并处理成对的重复请求。
- 平板息屏时跳过流转通知，亮屏后自动接收新通知，保留已设置的应用订阅。
- 配合手机端 RelayQuiet 推送平板亮屏、息屏状态，供手机判断流转通知是否需要点亮锁屏。
- 原厂弹幕“通知应用管理”增加设备互联，接收名单同步支持设备互联，开关沿用原厂配置。
- 流转通知弹幕使用来源应用图标并显示机型，最多显示 120 个字符；图标不可用时显示应用名。
- 隐藏游戏侧边栏白条，保留侧边栏窗口和原有手势唤出。
- Release 启用 R8 混淆和资源压缩。

## 使用

支持 Xiaomi `yingtian` 平板、Android 13 及以上，需要 root 和现代 LSPosed / Vector。

安装后启用模块，作用域勾选“米联 / `com.milink.service`”与“系统界面 / `com.android.systemui`”。在原厂通知管理页选择手机，添加应用并开启通知开关。手机需要安装对应应用并允许通知，流转沿用原厂互联与授权条件。

弹幕扩展还需勾选“平板管家 / `com.miui.securitycenter`”和“弹幕通知 / `com.xiaomi.barrage`”。适配管家 `13.2.9-260910.0.1.pad` 与弹幕通知 `3.0.0.260609`，显示沿用原厂场景、总开关及应用开关。

从旧包 `com.maple.notificationrelay` 升级时，在 LSPosed 中禁用旧模块，启用 `com.zephyr.notificationrelay` 并勾选上述作用域。

已适配平板米联 `18.2.2.1.2609281717`。通知点击串流已完成 Hook 集成，端到端效果由用户测试。

## 编译

使用 JDK 17、Android SDK platform 37 / build-tools 36.0.0、Gradle 9.3.1。

```shell
./gradlew :app:assembleRelease
```

Windows 使用 `gradlew.bat :app:assembleRelease`。APK 输出到 `app/build/outputs/apk/release/app-release.apk`，R8 映射表位于 `app/build/outputs/mapping/release/mapping.txt`。

Debug 诊断版使用 `:app:assembleDiagnostic`，沿用正式包名、签名与 R8 优化，版本后缀为 `-debug`，输出到 `app/build/outputs/apk/diagnostic/app-diagnostic.apk`。诊断版增加“通知流转入口诊断”桌面入口及日志导出，记录框架版本、进程、设备代号、实际打开的米联页面类名、入口类/方法解析与点击跳转阶段；诊断事件也写入框架日志。日志不包含通知正文、账号、设备关联标识、页面文字、Intent 参数或异常消息文本。启用并重启米联作用域后，从系统设置点击“设备互联”复现，再进入诊断页刷新并导出；没有宿主记录时一并导出 LSPosed 日志。正式版保持原有入口。两个版本覆盖安装，日常使用选择正式版。

本地存在 `build-key.jks` 时沿用本地签名；其他环境使用 Android 默认调试签名。Release APK 使用维护者签名，签名不同的自行构建版本需卸载原应用后安装。

## 参考与许可

界面使用 [Miuix 0.9.3](https://github.com/compose-miuix-ui/miuix)。通知点击桥接与会话匹配改编自 [HyperConnectToolkit](https://github.com/silverpoetry/HyperConnectToolkit)（commit `bbb80c8d5b6b601cccb32aeefbfe9db7bafc02f6`，Apache-2.0），对应 [HyperRefine](https://github.com/Ianzb/HyperRefine) 的通知点击功能。

来源、版权、修改说明与许可副本保存在 `app/src/main/assets/third_party/`，并随 APK 分发。
