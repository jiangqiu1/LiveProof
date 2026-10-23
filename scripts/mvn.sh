#!/usr/bin/env bash
# Maven 启动包装：绕过 git-bash 下 bin/mvn 的 CYGWIN 判定缺陷（见 docs/07 第 6.2 节）
# 用法：scripts/mvn.sh -q compile
set -euo pipefail

JAVA_HOME="${JAVA_HOME:-D:/Java/jdk-21}"
MVN_HOME="${MVN_HOME:-D:\\apache-maven-3.9.16-bin\\apache-maven-3.9.16}"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# git-bash 下 Maven 的 ANSI 着色会触发 jansi 异常并污染输出，统一关闭
export MAVEN_OPTS="${MAVEN_OPTS:-} -Djansi.force=false -Djansi.strip=true -Dstyle.color=never -Dfile.encoding=UTF-8"

exec "$JAVA_HOME/bin/java.exe" \
  -classpath "$MVN_HOME\\boot\\plexus-classworlds-2.11.0.jar" \
  "-Dclassworlds.conf=$MVN_HOME\\bin\\m2.conf" \
  "-Dmaven.home=$MVN_HOME" \
  "-Dmaven.multiModuleProjectDirectory=$PROJECT_DIR" \
  org.codehaus.plexus.classworlds.launcher.Launcher "$@"
