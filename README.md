# NotificationRelay

平板跨设备通知扩展，包名 `com.zephyr.notificationrelay`。

## 功能

- 设置中的“跨设备通知”入口打开原厂通知管理页。
- LSPosed 的模块设置入口直接打开已关联手机的通知应用列表；多台手机或尚未关联时进入原厂设备选择页。
- 应用列表右上角的加号打开 Miuix 底部搜索面板，支持在线搜索并添加应用。
- 包名和图标保存在模块私有数据目录，供订阅列表、通知卡片、详情及状态栏使用。
- 长按新增应用可确认删除；已开启的应用先取消订阅，再清理缓存。
- 点击原厂镜像类型的流转通知，通过原厂应用串流入口打开手机应用，并处理成对的重复请求。
- 平板息屏时跳过流转通知，亮屏后自动接收新通知，保留已设置的应用订阅。
- Release 启用 R8 混淆和资源压缩。

## 使用

支持 Xiaomi `yingtian` 平板、Android 13 及以上，需要 root 和现代 LSPosed / Vector。

安装后启用模块，作用域勾选“米联 / `com.milink.service`”与“系统界面 / `com.android.systemui`”。在原厂通知管理页选择手机，添加应用并开启通知开关。手机需要安装对应应用并允许通知，流转沿用原厂互联与授权条件。

从旧包 `com.maple.notificationrelay` 升级时，在 LSPosed 中禁用旧模块，启用 `com.zephyr.notificationrelay` 并勾选上述作用域。

已适配平板米联 `18.2.2.1.2609281717`。通知点击串流已完成 Hook 集成，端到端效果由用户测试。

## 编译

使用 JDK 17、Android SDK platform 37 / build-tools 36.0.0、Gradle 9.3.1。

```shell
./gradlew :app:assembleRelease
```

Windows 使用 `gradlew.bat :app:assembleRelease`。APK 输出到 `app/build/outputs/apk/release/app-release.apk`，R8 映射表位于 `app/build/outputs/mapping/release/mapping.txt`。

本地存在 `build-key.jks` 时沿用本地签名；其他环境使用 Android 默认调试签名。Release APK 使用维护者签名，签名不同的自行构建版本需卸载原应用后安装。

## 参考与许可

界面使用 [Miuix 0.9.3](https://github.com/compose-miuix-ui/miuix)。通知点击桥接与会话匹配改编自 [HyperConnectToolkit](https://github.com/silverpoetry/HyperConnectToolkit)（commit `bbb80c8d5b6b601cccb32aeefbfe9db7bafc02f6`，Apache-2.0），对应 [HyperRefine](https://github.com/Ianzb/HyperRefine) 的通知点击功能。

来源、版权、修改说明与许可副本保存在 `app/src/main/assets/third_party/`，并随 APK 分发。
