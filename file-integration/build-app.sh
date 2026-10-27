#!/usr/bin/env bash
# 打包成免安装的 exe（jpackage app-image）
#
# 用法：
#     cd file-integration
#     bash build-app.sh
#
# 产物：dist/综测整理/综测整理.exe + runtime/
#      整个目录压缩成 zip 发给别人，对方解压双击就能跑，不需要装 Java。
#
# 说明：
#   - 用 app-image 而不是 installer，所以不需要 WiX Toolset
#   - 不加 --win-console，否则会弹黑窗，用户会以为中了病毒
#   - 启动日志写到 %LOCALAPPDATA%\Assessment\logs\app.log

set -euo pipefail
cd "$(dirname "$0")"

APP_NAME="综测整理"
APP_VERSION="0.1.0"
JAR_NAME="assessment-0.1.0-SNAPSHOT.jar"
MAIN_CLASS="org.springframework.boot.loader.launch.JarLauncher"   # 从 MANIFEST 抄的，别凭记忆写
ICON="src/main/resources/app.ico"

# 本机 Maven 默认环境是坏的，用可用的那个。换机器时改这一行即可
MVN="bash C:/Users/25090/.workbuddy/tools/mvnw.sh"

echo "==> 1/3 编译"
$MVN clean package -DskipTests

echo "==> 2/3 准备 staging（jpackage 的 --input 只放 jar，别把 classes/ 混进去）"
rm -rf dist/staging
mkdir -p dist/staging
cp "target/${JAR_NAME}" dist/staging/

echo "==> 3/3 jpackage"
ARGS=(
  --type app-image
  --name "${APP_NAME}"
  --app-version "${APP_VERSION}"
  --input dist/staging
  --main-jar "${JAR_NAME}"
  --main-class "${MAIN_CLASS}"
  --dest dist
  --java-options "-Dfile.encoding=UTF-8"
)
# 有图标才加，没有也能打包（只是用默认图标）
if [ -f "${ICON}" ]; then
  ARGS+=(--icon "${ICON}")
else
  echo "    （未找到 ${ICON}，用默认图标）"
fi

# jpackage 不会覆盖已存在的目标目录，且 JRE 有 300+ 文件，
# 某些环境（或安全软件）会拦截批量删除。所以清理失败要明确提示，别让 jpackage 报个看不懂的错。
if [ -d "dist/${APP_NAME}" ]; then
  echo "==> 清理旧产物"
  rm -rf "dist/${APP_NAME}" 2>/dev/null || true
  if [ -d "dist/${APP_NAME}" ]; then
    echo "    !! 无法自动清理 dist/${APP_NAME}（被安全策略拦截）"
    echo "    请手动执行下面这行，然后重跑本脚本："
    echo "        rm -rf \"dist/${APP_NAME}\""
    exit 1
  fi
fi

jpackage "${ARGS[@]}"

echo "==> 复制 configs/ 到 exe 旁边（配置必须在包外，否则改了配置要重新打包）"
if [ -d "../configs" ]; then
  cp -r "../configs" "dist/${APP_NAME}/configs"
else
  echo "    （未找到 ../configs，exe 启动后会提示「未加载」）"
fi

echo
echo "==> 完成：dist/${APP_NAME}/"
du -sh "dist/${APP_NAME}" 2>/dev/null || true
echo "    双击 dist/${APP_NAME}/${APP_NAME}.exe 即可运行"
