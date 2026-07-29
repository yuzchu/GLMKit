#!/usr/bin/env bash
#
# build.sh — GLMKit Xposed 模块构建脚本
# 依赖：Android SDK (android.jar), dx/d8, aapt2, zip
#
# 用法：
#   ANDROID_HOME=/path/to/sdk ./build.sh
#   或设置 ANDROID_JAR=/path/to/android.jar
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
MODULE_DIR="${PROJECT_DIR}/module"
BUILD_DIR="${PROJECT_DIR}/build"
APK_NAME="glmkit-v1.0.0.apk"

# ── Android SDK 路径 ──────────────────────────────────────────
ANDROID_JAR="${ANDROID_JAR:-}"
if [[ -z "${ANDROID_JAR}" ]]; then
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    ANDROID_JAR="${ANDROID_HOME}/platforms/android-34/android.jar"
  fi
fi
if [[ ! -f "${ANDROID_JAR}" ]]; then
  echo "ERROR: android.jar not found."
  echo "Set ANDROID_HOME or ANDROID_JAR environment variable."
  exit 1
fi

# ── 构建工具 ──────────────────────────────────────────────────
AAPT2="${AAPT2:-${ANDROID_HOME:-/opt/android-sdk}/build-tools/34.0.0/aapt2}"
D8="${D8:-${ANDROID_HOME:-/opt/android-sdk}/build-tools/35.0.0/d8}"
DX="${DX:-${ANDROID_HOME:-/opt/android-sdk}/build-tools/34.0.0/dx}"
ZIPALIGN="${ZIPALIGN:-${ANDROID_HOME:-/opt/android-sdk}/build-tools/34.0.0/zipalign}"
APKSIGNER="${APKSIGNER:-${ANDROID_HOME:-/opt/android-sdk}/build-tools/34.0.0/apksigner}"

echo "── GLMKit Build ──"
echo "ANDROID_JAR : ${ANDROID_JAR}"
echo "MODULE_DIR  : ${MODULE_DIR}"
echo "BUILD_DIR   : ${BUILD_DIR}"

# ── 清理 ──────────────────────────────────────────────────────
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}/gen" "${BUILD_DIR}/obj" "${BUILD_DIR}/dex"

# ── 1. 编译资源 (aapt2) ───────────────────────────────────────
echo "[1/5] Compiling resources..."
"${AAPT2}" compile --dir "${MODULE_DIR}/res" -o "${BUILD_DIR}/res.zip"
"${AAPT2}" link \
  -I "${ANDROID_JAR}" \
  --manifest "${MODULE_DIR}/AndroidManifest.xml" \
  -o "${BUILD_DIR}/base.apk" \
  --java "${BUILD_DIR}/gen" \
  "${BUILD_DIR}/res.zip"

# ── 2. 收集 Java 源文件 ───────────────────────────────────────
echo "[2/5] Collecting sources..."
JAVA_SOURCES=$(find "${MODULE_DIR}/src" -name '*.java' | sort)
GEN_SOURCES=$(find "${BUILD_DIR}/gen" -name '*.java' 2>/dev/null | sort)
ALL_SOURCES="${JAVA_SOURCES} ${GEN_SOURCES}"

# ── 3. 编译 Java (javac) ──────────────────────────────────────
echo "[3/5] Compiling Java..."
javac -source 8 -target 8 \
  -cp "${ANDROID_JAR}:${MODULE_DIR}/libs/*" \
  -d "${BUILD_DIR}/obj" \
  ${ALL_SOURCES}

# ── 4. 转换为 DEX (d8) ────────────────────────────────────────
echo "[4/5] Converting to DEX..."
if command -v "${D8}" &>/dev/null; then
  "${D8}" \
    --lib "${ANDROID_JAR}" \
    --output "${BUILD_DIR}/dex" \
    $(find "${BUILD_DIR}/obj" -name '*.class')
else
  "${DX}" --dex --output="${BUILD_DIR}/dex/classes.dex" \
    $(find "${BUILD_DIR}/obj" -name '*.class')
fi

# ── 5. 打包 APK ───────────────────────────────────────────────
echo "[5/5] Packaging APK..."
cp "${BUILD_DIR}/base.apk" "${BUILD_DIR}/${APK_NAME}"
cd "${BUILD_DIR}/dex"
zip -j "${BUILD_DIR}/${APK_NAME}" classes.dex 2>/dev/null || \
  zip -j "${BUILD_DIR}/${APK_NAME}" *.dex
cd "${SCRIPT_DIR}"

# 添加 xposed 配置
cd "${MODULE_DIR}"
zip -r "${BUILD_DIR}/${APK_NAME}" xposed/ module.prop
cd "${SCRIPT_DIR}"

# 添加 libs (排除 XposedBridgeApi.jar — 运行时由框架提供)
if ls "${MODULE_DIR}/libs"/*.jar 1>/dev/null 2>&1; then
  cd "${MODULE_DIR}/libs"
  for jar in *.jar; do
    if [[ "$jar" != "XposedBridgeApi.jar" ]]; then
      zip -j "${BUILD_DIR}/${APK_NAME}" "$jar"
    fi
  done
  cd "${SCRIPT_DIR}"
fi

echo ""
echo "── Build Complete ──"
echo "APK (unsigned): ${BUILD_DIR}/${APK_NAME}"

# ── 6. 签名 APK ───────────────────────────────────────────────
echo "[6/6] Signing APK..."
KEYSTORE="${PROJECT_DIR}/debug.keystore"
if [[ ! -f "${KEYSTORE}" ]]; then
  keytool -genkeypair -keystore "${KEYSTORE}" -storepass android -keypass android \
    -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA -keysize 2048 -validity 10000 2>/dev/null
fi

SIGNED_APK="${BUILD_DIR}/glmkit-v1.0.0-signed.apk"
if [[ -x "${APKSIGNER}" ]]; then
  "${APKSIGNER}" sign --ks "${KEYSTORE}" --ks-pass pass:android --key-pass pass:android \
    --out "${SIGNED_APK}" "${BUILD_DIR}/${APK_NAME}" 2>/dev/null
  if [[ -f "${SIGNED_APK}" ]]; then
    mv "${SIGNED_APK}" "${BUILD_DIR}/${APK_NAME}"
    echo "APK (signed):   ${BUILD_DIR}/${APK_NAME}"
  fi
fi

ls -lh "${BUILD_DIR}/${APK_NAME}" 2>/dev/null || true
