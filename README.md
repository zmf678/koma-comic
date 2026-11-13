# ARK Reader

**ARK Reader** 是一款面向 Android 平板与手机打造的纯本地 & WebDAV 开源漫画阅读器。设计上专注本地漫画收藏、大屏横竖屏自适应阅读以及轻量零打扰体验：无广告、无使用期限弹窗、无第三方分析或统计 SDK。

> 包名：`com.arkreader.comic` · 最低系统要求：Android 9.0 (API 28) · 目标 SDK：Android 14 (API 34)

---

## 核心特性

### 1. 多仓库管理与格式支持
- **本地目录（SAF）**：通过系统 `OpenDocumentTree` 持久化读取本地存储/TF 卡漫画目录，支持创建 `.nomedia` 防止系统相册索引、自定义封面文件名与首页图库隐藏。
- **WebDAV 仓库**：内置轻量 WebDAV 客户端（支持基础认证与连通性测试），可直接扫描并流式阅读远程目录、压缩包或 PDF。
- **支持格式**：
  - 本地/远程图片文件夹（`jpg` / `jpeg` / `png` / `webp` / `bmp` / `gif`）
  - 压缩包与电子书漫画（`ZIP` / `CBZ` / `EPUB`）
  - 原生 PDF 漫画（基于 Android `PdfRenderer`，支持清晰度倍率调节）

### 2. 专为平板与大屏优化的阅读器
- **竖屏单页 / 横屏自动双页（`AUTO_LANDSCAPE`）**：
  - 竖屏持握时以单页完整呈现；横屏旋转后自动合并双页并排阅读，保持当前阅读页码衔接不断档（也支持随时切换为「始终单页」「始终双页合并」「始终双页分割」）。
  - 对本地章节自动预读页面宽高比，原图本身已是宽幅跨页画面的图片会独立铺开，避免误拼。
- **阅读器 UI 栏快捷控制**：
  - **更改跨页匹配**：在底部 UI 控制栏一键切换跨页配对奇偶偏移（首页单页起 / 成对跨页起），即时对齐错位的双页跨页图。
  - **双页拼接顺序切换**：一键在「左→右（美漫/国漫）」与「右→左（日漫）」之间切换。
- **丰富阅读辅助配置（支持多套预设与按漫画绑定）**：
  - 阅读方向：垂直连贯滚动、水平左滑、水平右滑
  - 手势与按键：双指缩放、双击放大、可自定义的点击翻页区域（左右 / 对角线）、左右互换、音量键翻页、触感反馈
  - 图像后处理：智能裁剪白边（可调强度与单边 10% 保护）、空白页检测、左右留白、阅读背景色（纯黑 / 深灰 / 浅灰 / 纯白）、屏幕常亮、书签管理

### 3. 书架、隐私锁与外观主题
- **多视图自适应布局**：平板（`>= 600dp`）采用侧边 `NavigationRail` 导航栏 + 多列网格；手机端自动切换为底部 `BottomNavigationView`。
- **书架管理与加密锁**：支持自建书架、从首页「全部」隐藏私密书架，以及为书架配置 **数字 PIN** 或系统 **`BiometricPrompt` 生物识别（指纹）** 锁，可选切出后台 2 秒自动上锁。
- **自然排序与繁简检索**：章节与标题按数字自然排序（如 `第2话` 排在 `第10话` 之前），搜索支持大小写不敏感与常见繁体/简体折叠匹配。
- **主题系统**：跟随系统 / 浅色 / 深色模式 + 8 种主题强调色，支持 4 种视图密度（大/中/小网格与列表）与缓存占用清理。

---

## 项目结构

```text
ark-reader/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/arkreader/comic/
│       │   │   ├── ArkReaderApp.kt                 # 应用入口与全局单实例（DB / Prefs / Cache）
│       │   │   ├── data/                           # 数据模型、SQLite 存储与偏好设置
│       │   │   ├── engine/                         # 扫描器、WebDAV、PDF/ZIP/SAF 页面源、图像处理、跨页排版
│       │   │   └── ui/                             # 主页、详情页、阅读器、仓库、书架与设置界面
│       │   └── res/                                # 平板/手机响应式布局、主题色与矢量图标
│       └── test/java/com/arkreader/comic/
│           └── CoreEngineTest.kt                   # 核心引擎单元测试
├── build.gradle.kts
├── gradle.properties
├── settings.gradle.kts
└── LICENSE
```

---

## 本地编译与安装

### 环境要求
- **JDK**：Java 17 或 Java 21
- **Android SDK**：Compile/Target SDK 34（`platforms;android-34`、`build-tools;34.0.0`）
- **Gradle**：8.7+

### 配置与构建步骤

1. 在项目根目录创建 `local.properties` 指定本地 Android SDK 路径（例如 Windows）：
   ```properties
   sdk.dir=D\:\\Android
   ```
2. 运行单元测试与 Debug APK 构建：
   ```bash
   gradle testDebugUnitTest assembleDebug --no-daemon
   ```
   构建产物位于：`app/build/outputs/apk/debug/app-debug.apk`
3. 通过 ADB 安装到平板或手机：
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

---

## 开源协议

本项目基于 [MIT License](LICENSE) 开源。
