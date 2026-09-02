#!/usr/bin/env bash
# Plan 04 §5 step 3: prove the packaged fat jar works from a bare Maven
# project that depends on nothing but io.laminardb:laminardb. Exercises
# NativeLoader's bundled-extraction path end to end.
set -euo pipefail

VERSION="$1"
NATIVES_DIR="$2"
REPO_DIR="$(pwd)"
JAR="target/laminardb-${VERSION}.jar"

mvn -q install:install-file -Dfile="$JAR" \
  -DgroupId=io.laminardb -DartifactId=laminardb -Dversion="$VERSION" -Dpackaging=jar

WORKDIR=$(mktemp -d)
trap 'rm -rf "$WORKDIR"' EXIT
cd "$WORKDIR"
mvn -q archetype:generate -DgroupId=demo -DartifactId=quickstart \
  -DarchetypeArtifactId=maven-archetype-quickstart -DarchetypeVersion=1.5 \
  -DinteractiveMode=false
cd quickstart

python3 - "$VERSION" <<'PY'
import sys
version = sys.argv[1]
pom = open('pom.xml').read()
pom = pom.replace('<maven.compiler.source>1.7</maven.compiler.source>',
                  '<maven.compiler.source>17</maven.compiler.source>')
pom = pom.replace('<maven.compiler.target>1.7</maven.compiler.target>',
                  '<maven.compiler.target>17</maven.compiler.target>')
deps = (
    '<dependency><groupId>io.laminardb</groupId><artifactId>laminardb</artifactId>'
    f'<version>{version}</version></dependency>'
    '<dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>'
    '<version>5.13.4</version><scope>test</scope></dependency>'
    '<dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId>'
    '<version>3.27.6</version><scope>test</scope></dependency>'
)
pom = pom.replace('</dependencies>', deps + '</dependencies>')
pom = pom.replace('</plugins>',
    '</plugins>')
open('pom.xml', 'w').write(pom)
PY

# The quickstart test is the repo's own QuickstartTest, exercising exactly what
# the README promises.
rm -rf src/main/java src/test/java
mkdir -p src/test/java
sed -e 's/^package io.laminardb;/package quickstart;/' \
    -e 's/^import static org.assertj.core.api.Assertions.assertThat;/import static org.assertj.core.api.Assertions.assertThat;\nimport io.laminardb.LaminarConnection;\nimport io.laminardb.LaminarDB;\nimport io.laminardb.QueryResult;\nimport io.laminardb.Writer;\nimport io.laminardb.ExecuteResult;/' \
    "$REPO_DIR/src/test/java/io/laminardb/QuickstartTest.java" \
    > src/test/java/QuickstartTest.java

# The surefire fork must see the arrow add-opens, same as in-repo builds.
mvn -q -DargLine="--add-opens java.base/java.nio=ALL-UNNAMED" test
echo "bare-project quickstart green"
