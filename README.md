# GLMKit — GLM 全功能增强模块 v2.0.0

[![Version](https://img.shields.io/badge/Version-v2.0.0-blue)]()
[![Xposed](https://img.shields.io/badge/Xposed-API%20102-blue)]()
[![Target](https://img.shields.io/badge/Target-智谱清言%20com.zhipuai.qingyan-green)]()
[![API](https://img.shields.io/badge/API-OpenAI%20Compatible-orange)]()

> 通过 Xposed Hook 智谱清言（com.zhipuai.qingyan），在设备本地启动 **OpenAI 兼容**的 HTTP API 反代服务器。
> v2.0.0 全功能适配 Deekseep 架构，支持对话编辑、历史管理、外观自定义、图片截取、分身环境等完整功能。

## ✨ 功能特性

### 核心 API 反代
- **OpenAI 兼容 API** — `/v1/chat/completions`、`/v1/models`、`/v1/diagnostic`、`/healthz`
- **SSE 流式响应** — 支持 `stream: true`，兼容 ChatGPT/NextWeb/LobeChat 等客户端
- **自动捕获认证** — Hook OkHttp 多策略拦截，透明获取 GLM authToken、apiUrl、capturedModel
- **本地反代** — 所有请求在设备本地处理，不经过外部服务器
- **零配置** — 激活模块后打开智谱清言即可，无需手动输入 API Key

### Deekseep 全功能适配 (v2.0.0 新增)
- **对话编辑器** — 编辑/注入/重发消息，会话级操作
- **历史管理** — 导出/导入/搜索对话历史，JSON 格式
- **外观自定义** — 字体大小、行间距、聊天背景、侧栏宽度
- **图片截取** — 提取对话中的图片，本地保存
- **工具箱** — 一键操作：清空对话、导出数据、修复数据库等
- **Web 控制台** — 浏览器访问的完整管理界面
- **自动删除会话** — 对话后自动清理 GLM 服务端会话记录
- **多语言** — 中英文界面
- **Cloudflare 隧道** — 内置 libcloudflared.so，支持远程访问

### 分身环境支持
- **动态路径** — DataPaths 三层初始化，自动适配分身 dataDir
- **端口隔离** — 按 userId 自动偏移 (16766 + userId)
- **认证隔离** — 共享 auth 文件按 userId 隔离

## 📋 系统要求

| 项目 | 要求 |
|------|------|
| Android 版本 | ≥ 7.0 (API 24) |
| Xposed 框架 | LSPosed (libxposed API 102) |
| 目标应用 | 智谱清言 (com.zhipuai.qingyan) |
| 架构 | arm64-v8a |

## 🚀 快速开始

### 1. 下载安装

```bash
# 下载 APK
wget https://gitee.com/feiwenvip/GLMKit/releases/download/v2.0.0/glmkit-v2.0.0.apk
```

将 `glmkit-v2.0.0.apk` 安装到设备上。

### 2. 激活模块

1. 打开 LSPosed 管理器
2. 在模块列表中找到 **GLMKit**
3. 启用模块
4. 在作用域中勾选 **智谱清言** (`com.zhipuai.qingyan`)
5. 重启智谱清言（或重启设备）

### 3. 使用 API

模块激活后，打开智谱清言并登录。模块将自动在本地启动 API 服务器。

**API 端点：**

```
http://127.0.0.1:16766/v1
```

> 分身环境下端口自动偏移：`16766 + userId`（如分身 user 999 → 17765）

**对话补全示例：**

```bash
curl http://127.0.0.1:16766/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "glm-4",
    "messages": [
      {"role": "user", "content": "你好"}
    ],
    "stream": false
  }'
```

**模型列表：**

```bash
curl http://127.0.0.1:16766/v1/models
```

**健康检查：**

```bash
curl http://127.0.0.1:16766/healthz
```

**诊断信息：**

```bash
curl http://127.0.0.1:16766/v1/diagnostic
```

### 4. 在第三方工具中使用

| 工具 | 配置方式 |
|------|----------|
| OpenAI Python SDK | `base_url="http://127.0.0.1:16766/v1"`, `api_key="any"` |
| LangChain | `OpenAI(base_url="http://127.0.0.1:16766/v1", api_key="any")` |
| ChatGPT Next Web | API 地址设为 `http://127.0.0.1:16766` |
| LobeChat | 自定义 OpenAI 提供商，地址 `http://127.0.0.1:16766/v1` |

> **注意：** `api_key` 可以填任意值，认证由模块通过捕获的凭据自动处理。

## 📁 项目结构

```
glm-mod/
├── module/
│   ├── AndroidManifest.xml
│   ├── build.sh                    # 构建脚本 (d8 打包)
│   ├── libs/                       # 依赖 JAR (JSch 等)
│   ├── res/                        # 资源文件 (中英文)
│   ├── src/com/glmkit/probe/       # 43 个 Java 源文件
│   │   ├── Main.java               # Xposed 入口 + Hook 逻辑 (14000+ 行)
│   │   ├── DataPaths.java          # 动态路径管理 (分身兼容)
│   │   ├── GlmCapture.java         # 认证信息容器
│   │   ├── GlmBackend.java         # GLM 后端实现 (Backend 接口)
│   │   ├── LocalApiGateway.java    # 本地 HTTP 服务器 + OpenAI 路由
│   │   ├── XposedCompat.java       # libxposed API 102 兼容层
│   │   ├── SettingsActivity.java   # 模块设置界面
│   │   ├── ChatEditorUi.java       # 对话编辑器 UI
│   │   ├── HistoryBridge.java      # 历史管理
│   │   ├── ChatAppearance.java     # 外观自定义
│   │   ├── ImageCutoutUi.java      # 图片截取
│   │   ├── GLMKitTools.java        # 工具箱
│   │   ├── GLMKitUi.java           # 主 UI 控制面板
│   │   └── ...                     # 其他功能模块
│   └── xposed/
│       ├── module.prop             # Xposed 模块属性
│       ├── scope.list              # 作用域: com.zhipuai.qingyan
│       └── java_init.list          # 入口: com.glmkit.probe.Main
└── scripts/
    └── build.sh
```

## 🔧 构建

### 依赖

- Android SDK (android-34)
- JDK 8+
- build-tools 35.0.0 (aapt2, d8)

### 构建命令

```bash
export ANDROID_HOME=/path/to/android-sdk
cd module && bash build.sh
```

输出：`module/glmkit-v2.0.0.apk` (9.8MB)

## 🏗️ 架构设计

```
┌──────────────────────────────────────────────────────────┐
│                    智谱清言进程                            │
│                                                          │
│  ┌─────────────┐    ┌────────────────────────────────┐  │
│  │  GLM App     │    │  GLMKit Xposed Module (v2.0)  │  │
│  │  (OkHttp)    │    │                                │  │
│  │              │    │  Main.java (入口)              │  │
│  │              │    │    ├─ DataPaths (动态路径)     │  │
│  │              │    │    ├─ XposedCompat (兼容层)    │  │
│  │              │    │    ├─ hookOkHttp (多策略)      │  │
│  │              │    │    │  └─ 捕获 Auth/URL/Model  │  │
│  │              │    │    ├─ GlmCapture              │  │
│  │              │    │    ├─ LocalApiGateway         │  │
│  │              │    │    │  └─ GlmBackend           │  │
│  │              │    │    └─ UI 模块 (编辑/历史/外观) │  │
│  └─────────────┘    └────────────────────────────────┘  │
│                            │                             │
│                     ┌──────▼──────┐                     │
│                     │ HTTP Server  │                     │
│                     │ port 16766+N │                     │
│                     └──────────────┘                     │
└──────────────────────────────────────────────────────────┘
                            │
                    ┌───────▼───────┐
                    │  外部客户端    │
                    │  (curl/SDK/   │
                    │   NextWeb)    │
                    └───────────────┘
```

### 分身路径三层初始化

```
1. onPackageLoaded → DataPaths.init(pkg) + initDynamicPaths()
   (Application 可能未创建，可能拿到 fallback 路径)
2. Application.onCreate hook → DataPaths.init(TARGET) + initDynamicPaths()
   (关键时机：Application 创建后拿到真实 dataDir)
3. Activity.onResume hook → !DataPaths.isRealInit() && DataPaths.init(TARGET)
   (兜底：万一 onCreate hook 没触发)
```

### Hook 策略 (多策略降级)

| 策略 | Hook 点 | 说明 |
|------|---------|------|
| 0 | okhttp3.OkHttpClient$Builder.build() | 捕获 OkHttpClient 实例 |
| 1 | okhttp3.internal.connection.RealCall | 拦截 execute()/enqueue() |
| 2 | nu.z (混淆类) RequestBody.create | 拦截请求体 |
| 3 | okhttp3.RequestBody (后备) | 请求体拦截后备 |
| 4 | SSL/TLS hostname | 从 bigmodel 域名捕获 auth |
| 5 | HttpURLConnection | 非 OkHttp 兜底 |

## 🔌 API 路由

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/v1/chat/completions` | 对话补全（支持 `stream`） |
| GET | `/v1/models` | 可用模型列表 |
| GET | `/v1/diagnostic` | 诊断信息（hook 状态、捕获状态） |
| GET | `/v1/settings` | 读取设置 |
| POST | `/v1/settings/auto-delete` | 切换自动删除会话 |
| GET | `/v1/logs` | 诊断日志 |
| GET | `/healthz` | 健康检查 |

## ⚙️ 配置

### 端口配置

默认端口 `16766`，可在 GLMKit 设置界面中修改（范围 1024-65535）。

**端口优先级：** 自定义端口 > userId 偏移 > 默认 16766
**端口冲突 fallback：** 顺序递增 (port+1, +2, ... +100)

### 自动删除会话

对话后自动删除 GLM 服务端会话记录，默认开启。可在 Web 控制台或设置中关闭。

## 🐛 故障排除

| 问题 | 解决方案 |
|------|----------|
| 模块未激活 | 检查 LSPosed 中模块是否启用，作用域是否包含智谱清言 |
| API 不可达 | 确认智谱清言已打开并登录，检查端口是否被占用 |
| 认证失败 | 重启智谱清言，确保已登录账号 |
| 流式响应中断 | 启用保活服务，防止进程被冻结 |
| 端口冲突 | 模块自动尝试备用端口，也可在设置中手动修改 |
| 分身路径错误 | v2.0.0 已修复，DataPaths 三层初始化自动适配 |
| 排查问题 | `curl http://127.0.0.1:16766/v1/diagnostic` 查看模块状态 |

## 📦 版本历史

| 版本 | 说明 |
|------|------|
| **v2.0.0** | **全功能适配 Deekseep：43 个源文件，50K+ 行代码，对话编辑/历史管理/外观自定义/图片截取/工具箱/Web 控制台/分身支持/Cloudflare 隧道。零 Deekseep 残留。** |
| v1.0.77 | 独立 RealCall hook + 混淆 fallback |
| v1.0.75 | 诊断增强：hook 状态/拦截计数/日志查看 |
| v1.0.74 | 修复分身自定义端口不生效 (跨包 SharedPreferences) |
| v1.0.73 | 修复分身端口冲突 (自定义端口优先级 + 顺序递增 fallback) |
| v1.0.71 | 分身支持：按 userId 自动偏移端口 + auth 文件隔离 |
| v1.0.70 | 修复 checkbox CSS + SSE 多字段捕获 + 删除 fallback |
| v1.0.68 | 对话后自动删除会话开关 |
| v1.0.67 | 修复模型捕获根因 (assistant_id + meta_data 过滤) |
| v1.0.66 | 修复模型捕获 + chat_mode 映射 |
| v1.0.49 | Deekseep 架构：网关在智谱清言进程内运行 |
| v1.0.33 | 网关控制 + API Key 验证 |
| v1.0.0 | 初始版本 |

## 📊 项目规模 (v2.0.0)

| 指标 | 数值 |
|------|------|
| Java 源文件 | 43 |
| 代码行数 | 50,873 |
| 编译类 | 659 |
| classes.dex | 1.8MB |
| APK | 9.8MB (含 libcloudflared.so 28.7MB) |
| 编译错误 | 0 |
| Deekseep 残留 | 0 |

## 📄 许可证

本项目仅供学习和研究使用。

## 🙏 致谢

- [Deekseep](https://gitee.com/feiwenvip/Deekseep) — 参考项目，提供了完整的功能架构和 UI 设计
- [libxposed](https://github.com/libxposed/api) — 现代 Xposed API
- [LSPosed](https://github.com/LSPosed/LSPosed) — 现代 Xposed 框架实现
