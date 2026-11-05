# MyGO-Clash for Android（原型）

> 早期原型：能开 VPN、导入订阅、切换节点，其他功能还没做。
> An early prototype: it turns the VPN on, adds subscriptions and switches proxies; nothing more yet.

## 结构

```
mobile/            Go：gomobile 绑定层（package libmygo），复用桌面端的 internal/ 包
android/           Kotlin + Jetpack Compose + miuix 的安卓工程
  build-aar.sh     用 gomobile 把 ../mobile 编成 app/libs/libmygo.aar
  app/src/main/java/io/mygo/clash/android/
    Core.kt              调用 Go 侧：内核、订阅、mihomo REST API
    ClashVpnService.kt   VpnService：建 TUN，把 fd 交给内核
    ToggleTileService.kt 快速设置磁贴
    ui/                  首页、代理、订阅三个页面（miuix）
```

### 运行方式

- 内核和界面在**同一进程**里。`App.onCreate` 调用 `Libmygo.init` 启动 `corehost`，也就是桌面端那个 mihomo 宿主。它在 `files/mygo/core.sock` 上提供 REST API，这时还没有任何配置。
- 打开代理时，`ClashVpnService` 建好 TUN（172.19.0.1/30，DNS 172.19.0.2，全局路由），然后把 `detachFd()` 拿到的 fd 交给 `Libmygo.start`。Go 侧用桌面端的 `profiles.Generate` 生成运行配置，加上 `tun.file-descriptor` 后应用。用的是 gvisor 协议栈，`dns-hijack: any:53`。
- 防回环：mihomo 的每个出站 socket 都经过 `dialer.DefaultSocketHook` 调到 Kotlin 的 `VpnService.protect(fd)`。
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

CI 见 `.github/workflows/android.yml`：推送到 `android-*` 分支，或改动 `android/`、`mobile/`、`internal/` 时，会构建 release APK（暂时用 debug key 签名）并作为 artifact 上传。

Go 侧有不需要设备的集成测试：

```bash
go test -tags with_gvisor ./mobile/
```

它会启动内核，导入一个本地订阅，选一个节点后重新应用，确认选择被保留；再经代理端口发一个请求，确认 socket 走了 `Protect`，最后停止内核。

## 已知问题 / TODO

- [ ] 主密钥还是存在文件里（`files/mygo/secure`），要换成 Android Keystore。桌面端用的是系统钥匙串。
- [ ] 网络切换（Wi-Fi ↔ 移动数据）后没有更新给 mihomo 的系统 DNS。
- [ ] 不支持 IPv6 路由，不支持分应用代理（`addAllowedApplication` / `addDisallowedApplication`）。
- [ ] 内核和界面在同一进程。界面崩溃会带走 VPN，以后考虑把 VpnService 放到 `:core` 进程。
- [ ] APK 偏大（release 约 57MB，两种 ABI），因为 Tailscale 也编进去了。可以按 ABI 拆分，或者用构建标签把 Tailscale 拿掉。
- [ ] 订阅的自动更新、扩展脚本编辑、WebDAV 同步、Tailscale 页面、连接和日志页面都还没做。
- [ ] 没有在真机上测过界面和 VPN，只做了编译和 Go 侧测试。
