# 禹邻优鲜 · 骑手端(Android)

Wave 0 工程骨架。设计依据:`docs/rider/06-Android骑手端设计.md`,API 契约:`docs/rider/04-API契约.md`(冻结)。

## 环境要求

| 项 | 要求 |
| --- | --- |
| JDK | 17+(本机使用 Android Studio 自带 JBR,OpenJDK 21) |
| Android SDK | compileSdk 36(`local.properties` 的 `sdk.dir` 指向本机 SDK) |
| Gradle | 8.14.3(wrapper 已配置,distributionUrl 指向腾讯镜像) |
| AGP / Kotlin | 8.13.2 / 2.3.21 |
| Hilt | 2.58(2.59+ 要求 AGP 9,详见 `gradle/libs.versions.toml` 注释) |

## 打开方式

1. Android Studio → Open → 选择 `rider-android` 目录(不要选仓库根)。
2. 首次同步前确认 `local.properties` 存在且 `sdk.dir` 正确(该文件不入库)。
3. 命令行构建:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
cd rider-android
.\gradlew assembleDebug --console=plain
```

## 镜像说明(中国大陆网络,成败关键)

- `settings.gradle.kts` 中 KSP 固定走 Maven Central（阿里云对该 plugin marker 会 502）；其余依赖官方源在前，阿里云作回落。
- `gradle/gradle-wrapper.properties` 的 `distributionUrl` 指向 `https://mirrors.cloud.tencent.com/gradle/`。

## 模块图

```
app                       壳:Application、MainActivity、导航图、权限编排
 ├─► feature/auth         登录、改密、定位授权同意
 ├─► feature/shift        上下班、检查清单、疲劳弹窗
 ├─► feature/task         任务列表、波次、详情、状态流转
 ├─► feature/map          地图、路线、导航、顺序调整
 ├─► feature/exception    异常上报、拍照
 ├─► feature/earning      收入、结算、服务分、申诉
 ├─► feature/message      消息中心
 └─► feature/profile      个人中心、设置、保活向导、关于
        │
        ▼(feature 之间不互相依赖,跨 feature 跳转走 app 导航图)
core/common  designsystem  network  database  datastore  model  location  push
```

依赖方向严格单向:`app → feature → core`。`gradle/libs.versions.toml` 为 Wave 0 定稿版本目录,其他 Agent 只读。

## 已知暂缓项(TODO)

- **极光推送已注释暂缓**(`core/push/build.gradle.kts`):阿里云镜像最高只有 jpush 4.0.5 / jcore 2.7.4,其 AAR 与 targetSdk 36 不兼容(PushReceiver 缺 `android:exported`,Android 12+ 强制拒绝)。待官方 5.x 可解析后启用;AppKey 占位符已留在 `app/build.gradle.kts` 的 `manifestPlaceholders`。
- 高德 navi-3dmap 已内置 3dmap 与定位类,故未单独引入 `com.amap.api:3dmap` / `com.amap.api:location`(会重复类)。Android Key 走仓库外 `~/.yulin/rider-publish.env`，用 `scripts/rider-configure-amap-key.sh` 写入；正式发布用仓库根目录 `scripts/rider-publish.sh`。
- 通知渠道自定义提示音(`res/raw/new_task.mp3`)资源就位后需新建渠道 id 迁移(渠道声音创建后不可改)。
