#!/usr/bin/env sh
##############################################################################
##
##  Gradle start up script for UN*X
##
##############################################################################

# Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
DEFAULT_JVM_OPTS=""

APP_NAME="Gradle"
SELF_NAME=$(basename "$0")

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        # IBM's JDK on AIX uses strange location for the executables
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2
        echo "       Please set the JAVA_HOME variable in your environment to match the" >&2
        echo "       location of your Java installation." >&2
        exit 1
    fi
else
    JAVACMD=$(command -v java)
    if [ -z "$JAVACMD" ] ; then
        echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH." >&2
        echo "       Please set the JAVA_HOME variable in your environment to match the" >&2
        echo "       location of your Java installation." >&2
        exit 1
    fi
fi

# Determine the location of the Gradle home directory
PRG="$0"

# Need this for relative symlinks.
while [ -h "$PRG" ] ; do
    ls=$(ls -ld "$PRG")
    link=$(expr "$ls" : '.*-> \(.*\)$')
    if expr "$link" : '/.*' > /dev/null; then
        PRG="$link"
    else
        PRG=$(dirname "$PRG")/"$link"
    fi
done

SCRIPT_DIR=$(dirname "$PRG")
GRADLE_WRAPPER_DIR="$SCRIPT_DIR/gradle/wrapper"

# Load the gradle-wrapper properties
if [ -f "$GRADLE_WRAPPER_DIR/gradle-wrapper.properties" ] ; then
    . "$GRADLE_WRAPPER_DIR/gradle-wrapper.properties"
else
    echo "ERROR: Could not find gradle wrapper properties at $GRADLE_WRAPPER_DIR/gradle-wrapper.properties" >&2
    exit 1
fi

# Build up command line
CLASSPATH=$GRADLE_WRAPPER_DIR/gradle-wrapper.jar

# Download distribution if necessary
DOWNLOAD_URL=${distributionUrl}
if [ -z "$DOWNLOAD_URL" ] ; then
    echo "ERROR: Distribution URL is not specified in gradle-wrapper.properties." >&2
    exit 1
fi

# Resolve distribution zip path
GRADLE_USER_HOME=${GRADLE_USER_HOME:-"$HOME/.gradle"}
ZIP_STORE_BASE=${zipStoreBase:-GRADLE_USER_HOME}
ZIP_STORE_PATH=${zipStorePath:-wrapper/dists}
ZIP_STORE="$GRADLE_USER_HOME/${ZIP_STORE_PATH}"
GRADLE_DIST=${DOWNLOAD_URL##*/}
GRADLE_DIR_NAME=$(basename "$GRADLE_DIST" .zip)
GRADLE_HOME="$ZIP_STORE/$GRADLE_DIR_NAME"

# Ensure wrapper jar exists
if [ ! -f "$GRADLE_WRAPPER_DIR/gradle-wrapper.jar" ] ; then
    echo "ERROR: gradle-wrapper.jar is missing in $GRADLE_WRAPPER_DIR" >&2
    exit 1
fi

# If distribution is not unpacked, download and unpack
if [ ! -d "$GRADLE_HOME" ] ; then
    echo "Downloading Gradle distribution $DOWNLOAD_URL"
    TMP_ZIP="$ZIP_STORE/$GRADLE_DIST"
    mkdir -p "$(dirname "$TMP_ZIP")"
    if [ ! -f "$TMP_ZIP" ] ; then
        curl -f -L -o "$TMP_ZIP" "$DOWNLOAD_URL" || {
            echo "ERROR: Could not download Gradle distribution from $DOWNLOAD_URL" >&2
            exit 1
        }
    fi
    mkdir -p "$GRADLE_HOME"
    unzip -q -o "$TMP_ZIP" -d "$ZIP_STORE"
fi

exec "$GRADLE_HOME/bin/gradle" "$@"
