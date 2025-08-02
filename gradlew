#!/usr/bin/env sh
### Gradle start up script for UN*X ###

DEFAULT_JVM_OPTS=""
APP_NAME="Gradle"
THIS="$0"
# resolve symbolic links
while [ -h "$THIS" ] ; do
  ls=`ls -ld "$THIS"`
  link=`expr "$ls" : '.*-> \(.*\)$'`
  if expr "$link" : '/.*' > /dev/null; then
    THIS="$link"
  else
    THIS=`dirname "$THIS"`/"$link"
  fi
done
BASE_DIR=`dirname "$THIS"`
if [ -n "$JAVA_HOME" ] ; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD="java"
fi
if [ ! -x "$JAVACMD" ] ; then
  echo "ERROR: JAVA_HOME is not set correctly." 1>&2
  echo "  Cannot run program '$JAVACMD'." 1>&2
  exit 1
fi
CLASSPATH="$BASE_DIR/gradle/wrapper/gradle-wrapper.jar"
exec "$JAVACMD" $DEFAULT_JVM_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
