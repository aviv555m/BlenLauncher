@echo off
rem Gradle wrapper script for Windows

setlocal enabledelayedexpansion

set APP_HOME=%~dp0
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

java -Xmx64M -Xms64M ^
    -Dorg.gradle.appname=gradlew ^
    -classpath "%CLASSPATH%" ^
    org.gradle.wrapper.GradleWrapperMain %*

pause