# GLMKit 项目全流程指南

> 本文档记录 GLMKit (com.glmkit.proxy) LSPosed/Xposed 模块的完整开发流程，
> 供未来 APP 升级时参考，确保一次成功完成升级。

## 1. 项目概述

GLMKit 是一个 Xposed 模块，通过 Hook 智谱清言 (com.zhipuai.qingyan) 的 OkHttp 网络层，
捕获其真实 API 请求中的 **authToken**、**apiUrl** 和 **capturedModel**，
并在本地启动 OpenAI 兼容的 HTTP 网关代理，实现聊天功能。

### 核心架构

```
智谱清言 APP (com.zhipuai.qingyan)
    │
    ├── OkHttp 网络层 ← Xposed Hook 拦截
    │       │
    │       ├── Request.Builder.addHeader() → 捕获 authToken (Authorization: Bearer xxx)
    │       ├── Request.Builder.url()       → 捕获 apiUrl (https://chatglm.cn/...)
    │       └── RequestBody.create()       → 捕获 capturedModel (assistant_id + chat_mode)
    │
    └── 本地网关 (LocalApiGateway, 端口 15236)
            │
            ├── POST /v1/chat/completions  → 转发到 GLM 后端 (SSE 流式)
            ├── GET  /v1/models            → 返回持久化模型列表
            ├── POST /v1/models/capture    → 捕获当前模型并持久化
            ├── POST /v1/models/add        → 手动添加模型
            ├── POST /v1/models/delete     → 删除模型
            └── GET  /                     → Web UI 控制面板
```

## 2. 关键文件清单

| 文件 | 职责 |
|---|---|
| `module/src/com/glmkit/probe/Main.java` | Xposed 入口，所有 Hook 逻辑，模型提取 |
| `module/src/com/glmkit/probe/GlmCapture.java` | 捕获数据容器 (authToken, apiUrl, capturedModel) |
| `module/src/com/glmkit/probe/GlmBackend.java` | GLM 后端请求构建 (buildGlmRequestBody) |
| `module/src/com/glmkit/probe/LocalApiGateway.java` | 本地 HTTP 网关服务器 + Web UI |
| `module/src/com/glmkit/probe/LocalApiKeepAliveService.java` | 前台服务保活 |
| `module/module.prop` | LSPosed 模块元数据 (版本号) |
| `scripts/build.sh` | 编译脚本 (APK 名称含版本号) |
| `module/AndroidManifest.xml` | 清单文件 (versionCode) |


## 3. Hook 原理与关键代码位置

### 3.1 Hook 入口

`Main.java` → `handleLoadPackage()` → 当 `lpparam.packageName.equals("com.zhipuai.qingyan")` 时执行：

| Hook 方法 | 目标类 | 目标方法 | 捕获内容 |
|---|---|---|---|
| `hookRequestBuilder` | `okhttp3.Request$Builder` (混淆: `nu.e`) | `addHeader` | authToken (Authorization 头) |
| `hookRequestUrl` | 同上 | `url` | apiUrl (请求 URL) |
| `hookRequestBodyCreate` | `okhttp3.RequestBody` (混淆: `nu.z`) | `create` | capturedModel (请求体中的模型) |
| `hookResponseBody` | `okhttp3.ResponseBody` (混淆: `nu.y`) | `create` | SSE 响应体 (用于日志) |

### 3.2 模型捕获流程 (关键！)

```
RequestBody.create(MediaType, String body)
    │
    ├── 检查 1: body.length() >= 10
    ├── 检查 2: !GlmCapture.isGatewayRequest() (跳过网关自身请求)
    ├── 检查 3: body 包含 GLM 特有字段 (assistant_id 或 meta_data 或 "model")
    │
    └── extractModelFromJson(body)
            │
            ├── 优先: 提取 "assistant_id" → 提取 meta_data.chat_mode → 映射后缀
            │       assistant_id="65940acff94777010aa6b796" + chat_mode="zero" → "65940acff94777010aa6b796:thinking"
            │
            └── 回退: 提取 "model" 字段 (OpenAI 兼容格式)
```

### 3.3 chat_mode 值映射

| chat_mode 值 | 用户友好后缀 | 说明 |
|---|---|---|
| `"zero"` | `thinking` | 深度思考模式 |
| `""` (空) | `fast` | 快速模式 |
| `"deep_research"` | `deep_research` | 深度研究模式 |

### 3.4 GLM API 请求体结构

```json
{
  "assistant_id": "65940acff94777010aa6b796",
  "meta_data": {
    "chat_mode": "zero"
  },
  "conversation_id": "...",
  "messages": [...]
}
```

> **注意**: GLM API 请求体**没有** `"model"` 字段！模型由 `assistant_id` + `meta_data.chat_mode` 决定。
> 这是 v1.0.66 捕获失败的根因：过滤条件 `body.contains("\"model\"")` 永远不匹配。


## 4. 本地网关 (LocalApiGateway)

### 4.1 端口与端点

- **端口**: 15236 (硬编码在 LocalApiGateway.java)
- **API Key**: 与捕获的 authToken 相同

| 端点 | 方法 | 功能 |
|---|---|---|
| `/` | GET | Web UI 控制面板 (HTML) |
| `/v1/chat/completions` | POST | 聊天转发 (SSE 流式) |
| `/v1/models` | GET | 模型列表 (免 API Key) |
| `/v1/models/capture` | POST | 捕获当前模型并持久化 |
| `/v1/models/add` | POST | 手动添加模型 |
| `/v1/models/delete` | POST | 删除模型 |

### 4.2 聊天转发流程

```
客户端 POST /v1/chat/completions {model: "65940acff94777010aa6b796:thinking", messages: [...]}
    │
    ├── 解析 model → 拆分为 assistant_id + suffix
    ├── suffix → chatMode (thinking→"zero", fast→"", deep_research→"deep_research")
    ├── GlmBackend.buildGlmRequestBody() → 构建 GLM API 请求体
    │       {assistant_id, meta_data:{chat_mode}, messages, stream:true}
    ├── 设置 ThreadLocal isGatewayRequest=true (防止 Hook 反馈循环)
    ├── 发送到 https://chatglm.cn/chatglm/backend-api/assistant/stream
    └── SSE 响应透传回客户端
```

### 4.3 模型持久化

- **存储**: SharedPreferences (`glm_models`)
- **格式**: JSON 数组 `["65940acff94777010aa6b796:thinking", "65940acff94777010aa6b796:fast"]`
- **默认模型** (空列表时返回):
  - `65940acff94777010aa6b796:thinking`
  - `65940acff94777010aa6b796:fast`

### 4.4 Web UI

- `getWebUI()` 方法返回内嵌 HTML
- API Key 通过 `const AK=apiKey` 嵌入前端
- `/v1/models` 端点豁免 API Key 验证

## 5. 版本历史与 Bug 修复记录

| 版本 | 修复内容 | 状态 |
|---|---|---|
| v1.0.63 | 基础功能验证 | ✅ |
| v1.0.64 | 添加 Web UI + 模型管理 | ⚠️ 模型列表空 |
| v1.0.65 | 默认模型 + API Key 豁免 | ❌ 捕获失效 |
| v1.0.66 | chat_mode 提取+映射修复 | ⚠️ 捕获仍失效 |
| v1.0.67 | **修复 hookRequestBodyCreate 过滤条件** | 待验证 |

### v1.0.66 修复的 4 个 Bug (正确但被根因遮蔽)

1. **extractModelFromJson 找不到 chat_mode** — chat_mode 嵌套在 meta_data 里，新增 `extractChatModeFromMeta()`
2. **chat_mode 值映射错误** — thinking 模式对应 `chat_mode='zero'`，不是 `'thinking'`
3. **捕获模型格式不匹配** — 统一为 `assistant_id:suffix` 格式
4. **handleListModels 静默吞噬异常** — `catch(Exception ignored)` 改为记录日志

### v1.0.67 修复 (根因)

**问题**: `hookRequestBodyCreate` 中过滤条件为 `body.contains("\"model\"")`，
但 GLM API 请求体使用 `assistant_id` 而非 `"model"` 字段，导致 hook 永远不会调用 `extractModelFromJson`。

**修复**: 将过滤条件改为 `body.contains("\"model\"") || body.contains("\"assistant_id\"") || body.contains("\"meta_data\"")`，
并使用 `extractModelFromJson(body)` 替代 `json.optString("model")` 进行模型提取。


## 6. APP 升级流程

当智谱清言 APP 升级后，可能需要更新 Hook 代码。以下是完整升级步骤：

### 6.1 升级前检查

1. **确认目标包名**: `com.zhipuai.qingyan` (通常不变)
2. **确认 OkHttp 混淆类名**: 当前为 `nu.z` (RequestBody), `nu.w` (MediaType), `nu.e` (Request$Builder)
   - 如果 APP 更换混淆方案，需用 `jadx` 反编译新版本查找 OkHttp 类名
   - 搜索特征: 实现 `create(MediaType, String)` 方法的类
3. **确认 API 端点**: `https://chatglm.cn/chatglm/backend-api/assistant/stream`
   - 如果域名或路径变化，更新 `GlmBackend.java` 中的 URL

### 6.2 升级步骤

1. **反编译新版本 APP** (可选，仅在 Hook 失效时需要)
   ```bash
   jadx -d output/ qingyan.apk
   grep -r "RequestBody" output/sources/ | grep "create"
   ```

2. **更新混淆类名** (如需要)
   - `Main.java` 中 `hookRequestBodyCreate`: `cl.loadClass("nu.z")` → 新类名
   - `Main.java` 中 `hookRequestBuilder`: `cl.loadClass("nu.e")` → 新类名

3. **更新版本号** (三处)
   - `module/module.prop`: `version=v1.0.XX` + `versionCode=XX`
   - `scripts/build.sh`: `APK_NAME="glmkit-v1.0.XX.apk"`
   - `module/AndroidManifest.xml`: `android:versionCode="XX"` + `android:versionName="1.0.XX"`

4. **编译**
   ```bash
   cd /root/glm-mod
   ANDROID_HOME=/opt/android-sdk ./scripts/build.sh
   ```

5. **推送 Gitee**
   ```bash
   cd /root/glm-mod
   git add -A && git commit -m "v1.0.XX: 修复描述"
   git push origin master
   # 创建 Release 并上传 APK
   ```

6. **用户验证**
   - 安装新 APK (LSPosed 中启用模块)
   - **强制停止智谱清言** (am force-stop com.zhipuai.qingyan)
   - 重新打开智谱清言
   - 发送一条消息
   - 打开 Web UI (http://localhost:15236) 确认捕获模型出现在列表中

### 6.3 验证清单

- [ ] LSPosed 中模块已启用，作用域包含智谱清言
- [ ] 强制停止并重新打开智谱清言 (必须！)
- [ ] 发送一条消息触发网络请求
- [ ] LSPosed 日志中出现 `★★★ 捕获模型 ID`
- [ ] Web UI (localhost:15236) 可访问
- [ ] Web UI 模型列表非空 (至少两个默认模型)
- [ ] Web UI 聊天功能正常 (发送消息有响应)
- [ ] 点击"捕获模型"按钮后，当前模型出现在列表中

## 7. 常见问题与修复

### 7.1 捕获模型不工作

**症状**: 发送消息后 Web UI 模型列表只有默认模型，点击"捕获"无反应。

**排查**:
1. 查看 LSPosed 日志，搜索 `[DIAG] RequestBody.create`
2. 如果 `hasModel=false`，说明请求体不含 `"model"` 字段 → 正常 (GLM 用 `assistant_id`)
3. 搜索 `★★★ 捕获模型` — 如果没有，说明过滤条件未匹配
4. **根因**: `hookRequestBodyCreate` 中 `body.contains("\"model\"")` 过滤条件过严
5. **修复**: 添加 `|| body.contains("\"assistant_id\"") || body.contains("\"meta_data\"")`

### 7.2 聊天功能不工作

**症状**: Web UI 发送消息无响应或报错。

**排查**:
1. 确认 authToken 已捕获 (LSPosed 日志搜索 `捕获 Authorization`)
2. 确认 apiUrl 已捕获 (搜索 `捕获 API URL`)
3. 确认 API URL 为 `https://chatglm.cn/chatglm/backend-api/assistant/stream`
4. 如果 URL 不同，APP 可能更新了 API 路径 → 更新 `GlmBackend.java`

### 7.3 Web UI 模型列表空

**症状**: 打开 Web UI，模型列表为空。

**排查**:
1. 确认 `getPersistentModels()` 空列表时返回默认模型
2. 确认 `/v1/models` 端点豁免 API Key 验证
3. 确认 Web UI fetch 请求携带 `Authorization: Bearer` 头

### 7.4 LSPosed 模块更新后不生效

**症状**: 更新模块 APK 后功能无变化。

**修复**: 必须强制停止目标 APP 再重新打开：
```bash
adb shell am force-stop com.zhipuai.qingyan
```
或在手机上：设置 → 应用 → 智谱清言 → 强制停止 → 重新打开


## 8. 编译环境

### 8.1 依赖

- **Android SDK**: android-34 (android.jar) + build-tools 34.0.0/35.0.0
- **Java**: JDK 8+ (javac -source 8 -target 8)
- **工具链**: aapt2, d8, zipalign, apksigner

### 8.2 编译命令

```bash
cd /root/glm-mod
ANDROID_HOME=/opt/android-sdk ./scripts/build.sh
```

输出: `/root/glm-mod/build/glmkit-v1.0.XX.apk`

### 8.3 签名

APK 使用 debug 签名 (apksigner)。LSPosed 不要求 release 签名。

## 9. Gitee 仓库

- **远程**: `https://gitee.com/feiwenvip/GLMKit`
- **分支**: master
- **Release 命名**: `v1.0.XX`
- **APK 文件名**: `glmkit-v1.0.XX.apk`

### 推送流程

```bash
# 1. 推送源码
cd /root/glm-mod
git add -A
git commit -m "v1.0.XX: 修复描述"
git push origin master

# 2. 创建 Release
# 使用 Gitee API 创建 Release 并上传 APK
```

## 10. 关键代码片段索引

### Main.java 关键方法

| 方法名 | 行号(约) | 功能 |
|---|---|---|
| `handleLoadPackage` | 100 | Xposed 入口 |
| `hookRequestBuilder` | 200 | 捕获 authToken |
| `hookRequestUrl` | 400 | 捕获 apiUrl |
| `hookRequestBodyCreate` | 1278 | **捕获 capturedModel (v1.0.67 修复)** |
| `extractModelFromJson` | 1711 | 从请求体提取模型 |
| `extractChatModeFromMeta` | 1734 | 从 meta_data 提取 chat_mode |
| `chatModeToSuffix` | 1784 | chat_mode → 后缀映射 |
| `isGlmHost` | 1398 | 判断是否 GLM 域名 |

### GlmBackend.java 关键方法

| 方法名 | 功能 |
|---|---|
| `buildGlmRequestBody` | 构建 GLM API 请求体 (assistant_id + meta_data) |
| `suffixToChatMode` | 后缀 → chat_mode 反向映射 |

### LocalApiGateway.java 关键方法

| 方法名 | 功能 |
|---|---|
| `getWebUI` | 返回 Web UI HTML |
| `handleListModels` | 处理 /v1/models |
| `handleCaptureModel` | 处理 /v1/models/capture |
| `getPersistentModels` | 获取持久化模型列表 |

---

> **文档版本**: v1.0.67 | **最后更新**: 2025-01-20 | **维护者**: GLMKit
