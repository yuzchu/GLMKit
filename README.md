# GLMKit — GLM 本地 API 反代增强模块

[![Xposed](https://img.shields.io/badge/Xposed-Module-blue)]()
[![Target](https://img.shields.io/badge/Target-智谱清言%20v3.7.0-green)]()
[![API](https://img.shields.io/badge/API-OpenAI%20Compatible-orange)]()

> 通过 Xposed Hook 智谱清言（com.zhipuai.qingyan），在设备本地启动 **OpenAI 兼容**的 HTTP API 反代服务器。
> 无需修改目标应用、无需抓包，直接复用应用内的认证凭据和 OkHttp 客户端发起请求。

## ✨ 功能特性

- **OpenAI 兼容 API** — 支持 `/v1/chat/completions`、`/v1/models`、`/healthz` 路由
- **SSE 流式响应** — 支持 `stream: true` 流式输出，兼容 ChatGPT 客户端
- **自动捕获认证** — Hook OkHttp 拦截器，透明获取 GLM 认证 token 和 API 端点
- **本地反代** — 所有请求在设备本地处理，不经过外部服务器
- **前台保活** — 前台服务 + WakeLock 防止进程被冻结
- **零配置** — 激活模块后打开智谱清言即可，无需手动输入 API Key

## 📋 系统要求

| 项目 | 要求 |
|------|------|
| Android 版本 | ≥ 7.0 (API 24) |
| Xposed 框架 | LSPosed / EdXposed / Xposed |
| 目标应用 | 智谱清言 v3.7.0+ |
| 架构 | arm64-v8a / armeabi-v7a |

## 🚀 快速开始

### 1. 安装模块

将构建好的 `glmkit-v1.0.28.apk` 安装到设备上。

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
http://127.0.0.1:8765/v1
```

**对话补全示例：**

```bash
curl http://127.0.0.1:8765/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "glm-4",
    "messages": [
      {"role": "user", "content": "你好"}
    ],
    "stream": false
  }'
```

**流式响应示例：**

```bash
curl http://127.0.0.1:8765/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "glm-4",
    "messages": [
      {"role": "user", "content": "讲个故事"}
    ],
    "stream": true
  }'
```

**模型列表：**

```bash
curl http://127.0.0.1:8765/v1/models
```

**健康检查：**

```bash
curl http://127.0.0.1:8765/healthz
```

### 4. 在第三方工具中使用

| 工具 | 配置方式 |
|------|----------|
| OpenAI Python SDK | `base_url="http://127.0.0.1:8765/v1"`, `api_key="any"` |
| LangChain | `OpenAI(base_url="http://127.0.0.1:8765/v1", api_key="any")` |
| ChatGPT Next Web | API 地址设为 `http://127.0.0.1:8765` |
| LobeChat | 自定义 OpenAI 提供商，地址 `http://127.0.0.1:8765/v1` |

> **注意：** `api_key` 可以填任意值，认证由模块通过捕获的凭据自动处理。

## 📁 项目结构

```
glm-mod/
├── module/
│   ├── AndroidManifest.xml          # 模块 Manifest
│   ├── module.prop                  # Xposed 模块属性
│   ├── libs/                        # 依赖 JAR（如有）
│   ├── assets/
│   │   ├── xposed_init              # 入口类
│   │   └── xposed/
│   │       ├── scope.list           # 作用域：com.zhipuai.qingyan
│   │       └── java_init.list       # 入口类：com.glmkit.probe.Main
│   ├── res/
│   │   ├── values/strings.xml       # 英文字符串
│   │   └── values-zh/strings.xml    # 中文字符串
│   ├── src/com/glmkit/probe/
│   │   ├── Main.java                # Xposed 入口，Hook 逻辑
│   │   ├── GlmCapture.java          # 认证信息容器
│   │   ├── LocalApiGateway.java     # 本地 HTTP 服务器 + OpenAI 兼容路由
│   │   ├── GlmBackend.java          # GLM 后端实现
│   │   ├── LocalApiKeepAliveService.java   # 前台保活服务
│   │   ├── LocalApiKeepAliveActivity.java  # 保活控制界面
│   │   ├── SettingsActivity.java    # 模块设置界面
│   │   ├── XposedActivationProvider.java   # 激活检测 Provider
│   │   └── XposedActivationReceiver.java   # 状态广播接收器
│   └── ...
└── scripts/
    └── build.sh                     # 构建脚本
```

## 🔧 构建

### 依赖

- Android SDK (android-34)
- JDK 8+
- build-tools 34.0.0 (aapt2, d8)

### 构建命令

```bash
export ANDROID_HOME=/path/to/android-sdk
./scripts/build.sh
```

输出：`build/glmkit-v1.0.28.apk`

## 🏗️ 架构设计

```
┌──────────────────────────────────────────────────────┐
│                   智谱清言进程                        │
│                                                      │
│  ┌─────────────┐    ┌──────────────────────────┐    │
│  │  GLM App     │    │  GLMKit Xposed Module    │    │
│  │  (Retrofit   │    │                          │    │
│  │   + OkHttp)  │    │  Main.java               │    │
│  │              │    │    ├─ Hook OkHttp        │    │
│  │              │    │    │  └─ 捕获 Auth/URL   │    │
│  │              │    │    ├─ GlmCapture         │    │
│  │              │    │    └─ LocalApiGateway    │    │
│  │              │    │        └─ GlmBackend     │    │
│  └─────────────┘    └──────────────────────────┘    │
│                            │                         │
│                     ┌──────▼──────┐                 │
│                     │ HTTP Server  │                 │
│                     │  port 8765   │                 │
│                     └──────────────┘                 │
└──────────────────────────────────────────────────────┘
                            │
                    ┌───────▼───────┐
                    │  外部客户端    │
                    │  (curl/SDK/   │
                    │   LangChain)  │
                    └───────────────┘
```

### 工作流程

1. **Xposed 注入** — 模块在智谱清言进程启动时被加载
2. **Hook OkHttp** — 拦截器捕获每次 HTTP 请求的认证头和 API 端点
3. **启动网关** — 在设备本地 `127.0.0.1:8765` 启动 HTTP 服务器
4. **请求转发** — 外部客户端发送 OpenAI 格式请求 → 网关转换为 GLM 格式 → 使用捕获的 OkHttp 客户端发送 → 解析 GLM 响应 → 转换回 OpenAI 格式返回
5. **SSE 流式** — 支持流式响应，逐 token 转发

## 🔌 API 路由

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/v1/chat/completions` | 对话补全（支持 `stream` 参数） |
| GET | `/v1/models` | 返回可用模型列表 |
| GET | `/v1/diagnostic` | 诊断信息（模块版本、网关状态、后端捕获状态） |
| GET | `/healthz` | 健康检查 |

### 请求格式（OpenAI 兼容）

```json
{
  "model": "glm-4",
  "messages": [
    {"role": "system", "content": "你是一个助手"},
    {"role": "user", "content": "你好"}
  ],
  "temperature": 0.7,
  "stream": false
}
```

### 响应格式（OpenAI 兼容）

```json
{
  "id": "chatcmpl-xxx",
  "object": "chat.completion",
  "model": "glm-4",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "你好！有什么可以帮助你的？"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 10,
    "completion_tokens": 15,
    "total_tokens": 25
  }
}
```

## ⚙️ 配置

### 端口配置

默认端口 `8765`，可在 GLMKit 设置界面中修改（范围 1024-65535）。修改后需重启智谱清言生效。

### 保活服务

在 GLMKit 设置界面中可启用前台保活服务，防止智谱清言进程被系统冻结。

## 📝 技术细节

### Hook 策略

与参考项目 Deekseep（hook 混淆类名）不同，本模块采用更干净的策略：

- **Hook `okhttp3.OkHttpClient$Builder.build()`** — 捕获 OkHttpClient 实例
- **Hook `okhttp3.internal.connection.RealCall`** — 拦截 execute()/enqueue() 捕获请求 URL 和认证头
- **Hook `retrofit2.Retrofit$Builder.build()`** — 捕获 Retrofit base URL

这避免了依赖混淆类名，提高了对不同版本的兼容性。

### 认证捕获

通过 hook OkHttp 拦截器，在请求发出前捕获：
- API 端点 URL（如 `https://chatglm.cn/chatglm/...`）
- Authorization Bearer Token
- Cookie
- 自定义头部（如 `x-device-id`）

捕获的信息存储在 `GlmCapture` 中，供 `GlmBackend` 使用。

### 请求转发

`GlmBackend` 使用捕获的 OkHttp 客户端（而非新建连接），确保：
- 复用连接池
- 继承 SSL/TLS 配置
- 自动携带认证信息
- 绕过任何应用内的请求签名/加密

## 🐛 故障排除

| 问题 | 解决方案 |
|------|----------|
| 模块未激活 | 检查 LSPosed 中模块是否启用，作用域是否包含智谱清言 |
| API 不可达 | 确认智谱清言已打开并登录，检查端口是否被占用 |
| 认证失败 | 重启智谱清言，确保已登录账号 |
| 流式响应中断 | 启用保活服务，防止进程被冻结 |
| 端口冲突 | 在设置界面修改端口号（v1.0.3+ 自动尝试备用端口） |
| 排查问题 | `curl http://127.0.0.1:8765/v1/diagnostic` 查看模块状态和捕获详情 |

## 📦 更新日志

| 版本 | 修复内容 |
|------|----------|
| v1.0.28 | KeepAliveActivity 状态区显示实际网关端口；SettingsActivity 使用说明端口号动态更新；README 版本引用和 Hook 策略描述修正 |
| v1.0.27 | 网关启动失败时不发送启动广播；KeepAliveActivity 端口标签修正 |
| v1.0.26 | strings.xml 版本号动态化；README 版本引用和项目结构路径修正 |
| v1.0.25 | `isReady()` 增加认证凭证检查；`GatewayException` 状态码透传修复 |
| v1.0.24 | 健康检查优先使用实际网关端口；KeepAliveActivity 端口键名修复 |
| v1.0.23 | `getModuleVersion()` 使用模块包名；广播报告实际绑定端口 |
| v1.0.22 | 端口范围下限修正 (≥1024)；KeepAliveActivity 端口动态读取 |
| v1.0.21 | 端口配置改用 XSharedPreferences 跨进程传递；保活服务网关状态改 HTTP 检查 |
| v1.0.20 | 激活广播机制；网关状态异步 HTTP 检查 |
| v1.0.19 | UI 深色→浅色主题；诊断按钮；KeepAliveActivity 主题修正 |
| v1.0.18 | LSPosed 识别修复：Manifest meta-data + assets/xposed_init + java_init.list |
| v1.0.17 | 模型别名扩展；stream_options/logit_bias 等参数透传；o1 模型映射 |
| v1.0.16 | chunked encoding 支持；finish_reason JSON null 修复 |
| v1.0.15 | module.prop 版本同步；大小写不敏感模型映射；getBestBaseUrl 修复 |
| v1.0.14 | URL 查询参数剥离；版本号动态读取 |
| v1.0.5 | 新增 `/v1/diagnostic` 诊断端点 |
| v1.0.4 | GLM API URL 提取逻辑修复；Content-Length 健壮性 |
| v1.0.3 | Response body 泄漏修复；端口占用自动备用端口 |
| v1.0.2 | 端口配置生效；CORS preflight 支持；版本号硬编码修复 |
| v1.0.1 | GlmBackend 方法修复；NPE 防护；重复 hook 防护 |
| v1.0.0 | 初始版本 |

## 📄 许可证

本项目仅供学习和研究使用。

## 🙏 致谢

- [Deekseep](https://github.com/lllucccian/Deekseep) — 参考项目，提供了 LocalApiGateway + Backend 架构思路
- [Xposed](https://github.com/rovo89/Xposed) — Hook 框架
- [LSPosed](https://github.com/LSPosed/LSPosed) — 现代 Xposed 框架实现
