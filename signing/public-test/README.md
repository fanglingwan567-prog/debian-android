# 公开共用测试签名（非安全发布密钥）

**此目录的私钥和密码由项目所有者明确授权公开，属于有意发布的测试材料。禁止将其用于正式安全发布、其他应用或任何需要身份可信性的场景。**

这是 GitHub Release `v0.1.34` 使用的同一把 RSA 3072 私钥和证书，导出副本的密码改为公开值 `android`。文件名、密码变化不改变 Android 签名身份；无需重新签名或替换已经发布的 APK。

## 风险与兼容边界

- 任何人都能使用此私钥签名。签名相同不再能证明 APK 来自项目维护者；第三方可制作 Android 接受为本应用更新的恶意 APK。本应用涉及 root 权限，风险尤其高。
- 私钥一旦公开，即使以后删除文件、修改密码或清理 Git 历史，也不能恢复它的保密性。需安全发布时使用另一把不公开的私钥，并单独规划签名和数据迁移。
- 安装现成 APK 不需要私钥。本目录仅帮助其他人自行编译时沿用 `v0.1.34` 的测试签名，而非绕过 Android 的安装检查。
- 同包名、同证书和兼容的版本号是覆盖安装的必要条件；仍可能因设备 ABI、系统版本、降级、APK 损坏等原因安装失败。
- 此密钥**不能覆盖使用旧测试证书或个人 debug 证书签名的版本**。请先备份应用内 Debian 数据，不要直接卸载或清除数据。
- 仅从可信来源下载 APK。公开证书指纹只能识别共用证书，不能证明发布者身份；应从可信渠道核对 APK 的 SHA-256 校验值。

## 文件与填写信息

| 项目 | 内容 |
| --- | --- |
| 密钥库 | `debian-android-public-test.p12` |
| 类型 | PKCS12 |
| 别名 | `debian-android-release` |
| 密钥库密码 | `android`（有意公开） |
| 私钥密码 | `android`（有意公开） |
| 证书 | `certificate.pem` |
| 配置信息 | `signing.properties`（供人工或外部工具读取，不自动接入 Gradle） |
| 证书主体 | `CN=Debian Android Release`（历史名称，不代表密钥仍然保密） |
| 算法 | RSA 3072 / SHA256withRSA |
| 到期时间 | 2054-04-09 05:21:39 UTC |

证书 SHA-256：

```text
04:53:F8:DB:B3:F5:B1:DF:90:D0:15:9D:36:69:84:2F:5D:36:A2:1F:9E:BB:E0:B7:75:20:B7:22:73:C5:A3:89
```

## 给本地 Release APK 签名

先按根目录 README 从源码生成 native 启动器，再构建 `:app:assembleRelease`。Gradle 默认仍生成未签名 APK；不会静默使用公开密钥。

从项目根目录执行以下命令，将 `BUILD_TOOLS` 指向本机可运行的 Android SDK Build Tools（版本号按本机安装情况调整）：

```sh
set -eu
BUILD_TOOLS="$ANDROID_HOME/build-tools/36.0.0"
KEYSTORE="signing/public-test/debian-android-public-test.p12"
UNSIGNED="app/build/outputs/apk/release/app-release-unsigned.apk"
SIGNED="app/build/outputs/apk/release/app-release-public-test.apk"

"$BUILD_TOOLS/zipalign" -P 16 -f 4 "$UNSIGNED" "$SIGNED"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" --ks-key-alias debian-android-release \
  --ks-pass pass:android --key-pass pass:android "$SIGNED"
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$SIGNED"
"$BUILD_TOOLS/zipalign" -P 16 -c 4 "$SIGNED"
```

也可在 Android Studio 的 **Generate Signed Bundle / APK** 中选择 APK，填写上表的密钥库、别名和公开密码，选择 `release`。此目录不包含 GitHub 登录令牌，也不应加入任何其他账号凭据。
