# Debian

ARM64 已 root 安卓手机的本地 Debian 命令行。Android 9+，需要可用的 `su`、Android Toybox 和较新的系统 WebView。无遥测、无远程网页；系统镜像在首次安装时按需联网下载，装好后启动终端无需联网。此构建不包含非 root VM 后端。

本项目从 `0.1.27` 独立重写；范围和验收见[重写计划](REWRITE.md)，编码遵循[项目规范](AGENTS.md)。

## 在 Android Studio 中打开

选择 **File → Open**，打开含 `settings.gradle` 和 `gradlew` 的项目根目录，**不要只打开 `app/`**。Gradle 分发选择项目的 Wrapper（8.8），使用 JDK 17 或 21，配置 Android SDK 34，并先按下文从源码生成 ARM64 启动器；同步后选择 `app` 运行配置。首次在新电脑上同步需要下载 Gradle 和 Maven 依赖，但构建 APK 不需要 Debian 镜像。运行终端仍需要已 root 的 ARM64 Android 设备。

```text
settings.gradle               根项目 Debian，包含 :app
build.gradle                  Android Gradle 插件版本
app/build.gradle              Android 配置和依赖
app/src/main/AndroidManifest.xml
app/src/main/java/            Kotlin 源码：Compose 界面、镜像与存储、root 调用、终端宿主与会话
app/src/main/res/             Android 资源
app/src/main/assets/          离线终端（不含系统镜像）
app/src/main/cpp/             原生启动器 C 源码
app/src/main/jniLibs/         本地生成的 ARM64 启动器（不入库）
gradle/wrapper/               Gradle Wrapper 配置和 JAR
gradlew / gradlew.bat          Linux、macOS / Windows 构建入口
tools/                        Termux 构建及资产准备脚本
```

## 打开流程

1. 「1/3 · 授予权限」：自动向 root 管理器申请权限。拒绝或超时只在本页显示原因，可点「重新请求授权」。
2. 「2/3 · 下载系统」：选稳定版或测试版后，通过 HTTPS 读取 Debian `Release` 的当前稳定版主版本号或测试版代号，并从 Docker Hub `library/debian` 实时筛选近期 ARM64 slim 版本（最多显示 12 个）。稳定版列出点版本，测试版列出日期快照。选择版本不会下载镜像；点击下载后，根据选定的 ARM64 manifest 摘要读取清单，显示已下载大小、总量和百分比。仅接受单层 gzip rootfs；校验字节数及 SHA-256 后，原子保存到应用私有目录。不接受自定义镜像地址。访问镜像源失败或限流可在当前页重试；中断的传输需重新下载，不做断点续传。
3. 「3/3 · 安装系统」：完整下载后自动进入此页，确认安装时不再请求网络。重开应用可复用下载；失败可重试安装或重新选择版本。root 启动器在未挂载的临时目录再次校验镜像长度、摘要，解包并探测 Bash/dpkg 后才原子发布。安装成功后清理压缩包。

安装后以及以后打开都直接进入 Bash，初始身份为 root。Android root 管理器记住授权后可以避免再次弹框。终端顶部保留透明的 Android 状态栏：时间、电量等图标显示在终端底色上，首行命令从状态栏下方开始。没有应用标题栏、身份标签、用户选择或重启按钮。底部导航栏默认隐藏，可从边缘滑动临时唤出；仅在软键盘打开时显示与终端同底色的两排快捷键：`ESC TAB HOME ↑ END PGUP`、`CTRL ALT ← ↓ → PGDN`。上下左右四个方向键按下立即发送一次，持续按住后按 Android 系统的延迟和频率连续发送；松手、滑出按钮、收起键盘或窗口失焦时停止。点亮 `CTRL` 或 `ALT` 后再按目标键；可组合使用，再点一次取消，收起键盘也会取消。
账号和密码由 Debian 管理，启动器不创建账号，不预设密码，也不改命令提示符。需要时在命令行用 `su - 用户名` 切换到已有账号；用 `exit` 返回上一级 Shell，最外层 Bash 正常退出时关闭终端。当前网络的 DNS 写入 Debian；网络切换后请重新打开会话。

单指上下拖动回看会话历史（最多 2500 行）；全屏终端程序使用其自己的滚动逻辑。双指捏合或张开改变终端字号（8–36），行列数随窗口重算，字号在下次打开时保留。按下时不改变输入焦点。没有选区时，单指在原处按住不超过 0.5 秒并松手，才进入输入：键盘收起则唤起，键盘已展开则保持。按系统返回键收起输入法后，可再次轻点唤起。在文字上原地按住超过 0.5 秒进入选择：键盘收起时不弹出，展开时不释放编辑焦点；松手保留高亮和两端手柄，不会自动复制。轻点高亮文字即可复制整个选区并收起手柄；轻点终端内选区以外的位置只取消选择，不改变剪贴板或输入法。复制或取消后，反复开合键盘不会再显示旧高亮。保持按住并拖动可跨行扩选，也可以在松手后拖动任一手柄微调范围。行尾空白处也可起选，空选区不会覆盖剪贴板。

手势统一由 WebView 内的 Pointer Events 状态机识别：移动超过系统阈值后，本次触摸不会再被判为点击，即使滑回起点也不恢复。Android 层不识别手势，只在 Web 主动申请时显示输入法、反馈键盘可见状态并丢弃过期请求。键盘隐藏时，网页禁止输入框自动拉起软键盘；确认单击后才恢复。浏览器兼容触摸和鼠标事件受到拦截，避免重复触发焦点变化。

## 范围与存储

使用内核 `chroot(2)`，没有 CPU 仿真，不需要再下载 GNU chroot。每次新建私有 mount namespace；提供 PTY、最小 `/dev`、只读 `/proc`，不主动映射 Android 存储。初始 Shell 使用的 root 是**手机 root**，不是安全沙箱；不要在其中运行不信任的代码。根目录位于应用私有的 `files/debian/root/`（主用户内置存储通常是 `/data/user/0/app.birch.debian/files/debian/root/`）。从 0.1.17 起包名为 `app.birch.debian`，与旧包 `app.birch.shell` 可以并存；首次使用新包需重新授权并安装系统，不读取或迁移旧包数据。同包名覆盖更新保留这套系统；卸载或清除应用数据时，Debian 和其中的文件也随应用数据删除，请提前导出重要文件。0.1.5 起仅使用这个位置；不查找、迁移或清理旧开发版本的外部实例。

没有 systemd/PID 1 或 Docker 兼容性承诺，不提供退出后持久后台会话。`/proc` 与宿主内核相关；SELinux/厂商内核/root 管理器可能拒绝 mount 或 exec，应用不会关闭 SELinux。

终端会话由应用内的 Kotlin Service 持有。按 Home 或切换应用不主动停止；确认退出、划掉最近任务、最外层 Bash 退出或应用进程结束时，native 监督进程停止并回收本次会话的全部后代进程，包括 `nohup`、`setsid` 和双重 fork 的后台服务，随后私有挂载随命名空间释放。系统文件和已保存的数据保留，下次打开创建新会话。监督进程通过 Linux subreaper 收养孤儿进程，并监测应用进程，不依赖 PID/user namespace，也不按进程名称清理其他应用或其他会话。此机制不是 root 安全隔离；主动绕过监督进程的宿主操作不属于会话管理范围。

## 工具与构建

- 镜像：Docker Official Images 的 `library/debian`，不在安装包中锁死版本号或摘要。每次选择分支重新获取目录；一次下载锁定所选清单摘要及其层摘要，不跟随可变标签。网络请求仅在普通应用进程发出，WebView 保持离线。
- 安装引导使用 Kotlin **1.9.24**、Compose 编译器 **1.5.14**、Compose BOM **2024.06.00**（UI **1.6.8**、Material 3 **1.2.1**）。颜色取自原 `Theme.Material3.DayNight`，波浪进度使用 Compose Canvas，终端快捷键和确认框使用 Compose。Material Components **1.12.0** 提供 Activity 主题和系统动态配色。终端采用 xterm.js **6.0.0**、addon-fit **0.11.0**；上游许可证在 assets/term 中。`tools/assets.py` 在校验上游包后应用一处本地修正：清除选区时也更新 DOM 渲染器缓存，避免终端高度改变时重画已经取消的选区。Debian 软件版权信息保留在镜像 `/usr/share/doc` 中。
- 本次工具链：SDK 34、AGP 8.4.1、Gradle 8.8、JDK 21、NDK r29 sysroot。公开测试版使用本地 Android 测试证书签名；GitHub 附件为不可调试的 Release 构建，不是商店发行签名。
- `tools/assets.py` 只在重新获取打包输入时需要联网，只下载固定摘要的 xterm 包。源码和所需资产已就位。

仓库不提交 APK、Debian 镜像或编译好的 `libbirch.so`。首次克隆或修改 `launcher.c` 后，先从项目根目录运行：

```sh
sh tools/native.sh
```

此步骤需要 NDK r29 的 sysroot，以及可在本机运行的 `clang`、`ld.lld`。Termux 使用本机的 LLVM 工具，脚本默认查找 `~/android-ndk-r29`。Linux/macOS 可将 `ANDROID_NDK_HOME` 指向 NDK 根目录，并将其中 `toolchains/llvm/prebuilt/<宿主目录>/bin` 加到 `PATH`；Windows 可在 WSL 使用 Linux 版 NDK 执行此步骤。

生成启动器后可使用 Android Studio，或从命令行构建（Windows 用 `gradlew.bat`）：

```sh
./gradlew :app:assembleDebug
```

标准输出：`app/build/outputs/apk/debug/app-debug.apk`。此 APK 会包含本地生成的启动器。

Termux 首次构建时从项目配置的官方仓库同步依赖，并指定本机 ARM64 的 `aapt2`：

```sh
ANDROID_HOME="$HOME/android-sdk" ./gradlew "-Pandroid.aapt2FromMavenOverride=$(command -v aapt2)" :app:assembleDebug
```

同步后可离线构建：

```sh
sh tools/assemble.sh :app:assembleRelease
```

输出：`app/build/outputs/apk/release/app-release-unsigned.apk`。分发前需用自己的证书签名；本项目公开测试包继续使用此前的测试证书，以支持覆盖安装。签名密钥不入库。

GitHub 源码仅包含应用源码、资源、构建工具和项目文档，不包含测试程序、APK、Debian 镜像或编译好的 native 启动器。可安装的 Release APK 单独发布在 GitHub Releases。
