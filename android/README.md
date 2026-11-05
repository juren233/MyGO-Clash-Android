# MyGO-Clash for Android（预览版）

> 预览版：开关代理、订阅、节点、设置、分应用代理、连接和日志都有了；还没在真机上测过。
> A preview: the proxy, profiles, proxies, settings, per-app proxy, connections and logs work; it has not run on a real device yet.

## 结构

```
mobile/            Go：gomobile 绑定层（package libmygo），复用桌面端的 internal/ 包
android/           Kotlin + Jetpack Compose + miuix 的安卓工程
  build-aar.sh     用 gomobile 把 ../mobile 编成 app/libs/libmygo.aar
  app/src/main/java/io/mygo/clash/android/
    Core.kt              调用 Go 侧：内核、订阅、mihomo REST API
    ClashVpnService.kt   VpnService：建 TUN（IPv6、分应用），把 fd 交给内核；改设置时换新 TUN 不停机
    MasterKey.kt         主密钥，用 Android Keystore 的 AES 密钥包裹后存放
    Settings.kt          设置（SharedPreferences）
    Logs.kt              代理运行期间缓存内核日志
    BootReceiver.kt      开机自启
    ToggleTileService.kt 快速设置磁贴
    ui/                  首页、代理、订阅、设置，以及应用选择、连接、日志子页面（miuix）
```

### 运行方式

- 内核和界面在**同一进程**里。`App.onCreate` 调用 `Libmygo.init` 启动 `corehost`，也就是桌面端那个 mihomo 宿主。它在 `files/mygo/core.sock` 上提供 REST API，这时还没有任何配置。
- 打开代理时，`ClashVpnService` 建好 TUN（172.19.0.1/30，DNS 172.19.0.2，全局路由），然后把 `detachFd()` 拿到的 fd 交给 `Libmygo.start`。Go 侧用桌面端的 `profiles.Generate` 生成运行配置，加上 `tun.file-descriptor` 后应用。用的是 gvisor 协议栈，`dns-hijack: any:53`。
- 防回环：App 自身的流量用 `addDisallowedApplication` 排除在 VPN 外（内核出站、订阅下载、首次启动下载 geo 数据都直连），另外 mihomo 的每个出站 socket 还会经过 `dialer.DefaultSocketHook` 调到 Kotlin 的 `VpnService.protect(fd)`，双保险。
- 网络切换时，`onLinkPropertiesChanged` 把新网络的 DNS 交给内核（`Libmygo.setDNS`）。
- 订阅按桌面端的规则定时更新（默认一天，或者按服务商建议的间隔），当前订阅更新后会自动重新应用。
- geo 数据库（geoip.metadb、geosite.dat）在 `build-aar.sh` 里由 `tools/geodata` 下载，编进 AAR，和桌面端 release 版一样，首次启动不用联网下载。
- 用 `cmfa` 构建标签：mihomo 不再自己读系统 DNS 和应用列表（普通应用没有这个权限），改由 App 提供。系统 DNS 在建 VPN 之前从 `ConnectivityManager` 读取；连接属于哪个应用，通过 `getConnectionOwnerUid` 查（Android 10+），所以 `PROCESS-NAME` 规则可以用包名匹配。
- 停止时应用一份没有监听的空配置，内核随即关闭 TUN fd；内核进程本身不退出。

## 构建

需要 Go（版本以 go.mod 为准，`GOTOOLCHAIN=auto` 会自动下载），以及 Android SDK：platform 37、build-tools 37 和一个 NDK。

```bash
export ANDROID_HOME=~/Android/Sdk     # NDK 取 $ANDROID_HOME/ndk 下最新的版本
android/build-aar.sh                  # → android/app/libs/libmygo.aar（arm64 + x86_64）
cd android && ./gradlew assembleDebug # → app/build/outputs/apk/debug/app-debug.apk
```

只要 arm64 的话，`ABIS=android/arm64 android/build-aar.sh` 会快一倍，AAR 也更小。

`./gradlew dist` 会把 release APK 按桌面端的命名规则放到 `app/build/dist/`：每个 ABI 一个包，外加一个通用包，文件名形如 `mygo-clash-<版本>-android-arm64.apk`。

### CI 与发版

- `.github/workflows/android.yml`：推送到 `android-*` 分支，或者改动 `android/`、`mobile/`、`internal/` 时运行，构建 APK 并作为 artifact 上传。
- 桌面端的 `release.yml` 推送版本 tag 时也会调用它，把 APK 上传到同一个 Release，发布说明里会自动出现 Android 的下载链接。Android 构建失败不会阻塞桌面端发布。
- 签名：配置了 `MYGO_ANDROID_KEYSTORE`（Base64 编码的 .jks）、`MYGO_ANDROID_KEYSTORE_PASSWORD`、`MYGO_ANDROID_KEY_ALIAS`、`MYGO_ANDROID_KEY_PASSWORD` 这几个 secret 时，用正式密钥签名；没配置就用 debug 密钥。生成密钥的命令写在 workflow 文件开头的注释里。**正式密钥一旦丢失，已安装的用户就无法覆盖升级**，请妥善备份。

Go 侧有不需要设备的集成测试：

```bash
go test -tags with_gvisor ./mobile/
```

它会启动内核，导入一个本地订阅，选一个节点后重新应用，确认选择被保留；再经代理端口发一个请求，确认 socket 走了 `Protect`，最后停止内核。

## 已知问题 / TODO

已完成：
- [x] 主密钥存 Android Keystore（自动接管原型版本留下的密钥文件）
- [x] 网络切换后更新系统 DNS
- [x] IPv6 路由、分应用代理（仅选中 / 排除选中）、开机自启、电池优化入口
- [x] 设置页：协议栈、端口、局域网、日志级别
- [x] 订阅定时更新；连接页、日志页
- [x] 按 ABI 拆包，正式签名，随桌面端版本一起发布
- [x] 规则页；检测页（出口 IP 的三种视角、流媒体和 AI 服务解锁检测）
- [x] 订阅编辑：名称、链接、User-Agent、自动更新间隔
- [x] 界面语言：中文、英文（按系统语言切换）

还没做：
- [ ] 真机测试：界面和 VPN 只在 CI 和本地编译验证过，没有在真机上跑过
- [ ] 内核和界面在同一进程，界面崩溃会带走 VPN。以后考虑把 VpnService 放到 `:core` 进程
- [ ] 扩展（合并 / 脚本）的编辑界面
- [ ] 检测要经过代理端口（App 自身的流量不进 VPN），代理端口设为 0 时检测不可用
- [ ] Tailscale（要和 TUN 共用一个 VPN）、WebDAV 同步
- [ ] Tailscale 编进去了但没用上，可以用构建标签去掉来减小 APK
