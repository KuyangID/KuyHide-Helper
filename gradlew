#!/bin/sh
# Gradle Wrapper startup script for POSIX

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
MAX_FD="maximum"
warn() { echo "$*"; }
die() { echo "$*"; exit 1; }

DEFAULT_JVM_OPTS="-Xmx64m -Xms64m"

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Determine the Java command to use
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

# Resolve APP_HOME
PRG="$0"
while [ -h "$PRG" ] ; do
    ls=$(ls -ld "$PRG")
    link=$(expr "$ls" : '.*-> \(.*\)$')
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=$(dirname "$PRG")"/$link"
    fi
done
SAVED="$(pwd)"
cd "$(dirname "$PRG")/" >/dev/null
APP_HOME="$(pwd -P)"
cd "$SAVED" >/dev/null

# Download gradle-wrapper.jar if missing
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
    echo "Downloading Gradle Wrapper..."
    WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v8.2.0/gradle/wrapper/gradle-wrapper.jar"
    if command -v curl > /dev/null 2>&1; then
        curl -sL "$WRAPPER_URL" -o "$WRAPPER_JAR"
    elif command -v wget > /dev/null 2>&1; then
        wget -q "$WRAPPER_URL" -O "$WRAPPER_JAR"
    else
        die "ERROR: Cannot download gradle-wrapper.jar. Install curl or wget."
    fi
fi

exec "$JAVACMD" \
    $DEFAULT_JVM_OPTS \
    -classpath "$WRAPPER_JAR" \
    org.gradle.wrapper.GradleWrapperMain "$@"
