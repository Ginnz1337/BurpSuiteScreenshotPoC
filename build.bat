@echo off
setlocal enabledelayedexpansion

echo ==================================================
echo   Building Burp Suite PoC Screenshot Extension
echo ==================================================

rem 1. Detect the newest installed JDK.
rem    The previous list named jdk-25.0.4.1 and jdk-17, neither of which exists on this
rem    machine, so the build silently fell back to whatever javac was on PATH. Scan the
rem    install root instead and take the first that actually has a compiler.
rem    Caveat: "dir /o-n" sorts names as text, so jdk-9 would beat jdk-10. Every JDK this
rem    project has seen is two digits, which sorts correctly.
set "JH="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JH=%JAVA_HOME%"

if not defined JH (
    pushd "C:\Program Files\Java" 2>nul
    if not errorlevel 1 (
        for /f "delims=" %%D in ('dir /b /ad /o-n "jdk-*" 2^>nul') do (
            if not defined JH if exist "%%D\bin\javac.exe" set "JH=C:\Program Files\Java\%%D"
        )
        popd
    )
)

if defined JH (
    set "JAVAC=%JH%\bin\javac.exe"
    set "JAR=%JH%\bin\jar.exe"
    set "JAVA=%JH%\bin\java.exe"
) else (
    echo [!] No JDK found under C:\Program Files\Java; falling back to PATH.
    set "JAVAC=javac"
    set "JAR=jar"
    set "JAVA=java"
)

echo [+] Using compiler: %JAVAC%
"%JAVAC%" -version

rem 2. Fetch the Montoya API jar when it is not already there.
rem    It is deliberately not committed. The jar is covered by the Burp Suite Professional
rem    licence, which grants no right to redistribute it, so it is pulled from Maven Central
rem    on the first build instead. It is compile-only and never enters the extension JAR.
set "MONTOYA=lib\montoya-api-2023.12.1.jar"
set "MONTOYA_URL=https://repo1.maven.org/maven2/net/portswigger/burp/extensions/montoya-api/2023.12.1/montoya-api-2023.12.1.jar"
if not exist "%MONTOYA%" (
    echo [+] %MONTOYA% is missing. Downloading from Maven Central...
    if not exist lib mkdir lib
    curl.exe -fsSL -o "%MONTOYA%" "%MONTOYA_URL%"
    if errorlevel 1 (
        echo [-] Download failed. Fetch it by hand from:
        echo     %MONTOYA_URL%
        echo     and save it as %MONTOYA%.
        if exist "%MONTOYA%" del /f /q "%MONTOYA%"
        goto fail
    )
)

rem 3. Prepare directories
if exist build\classes rmdir /s /q build\classes
if not exist build\classes mkdir build\classes
if not exist build\libs mkdir build\libs

rem 4. Extract bundled libraries (Gson) into the classes directory for a fat JAR
echo [+] Extracting gson dependency...
pushd build\classes
"%JAR%" -xf ..\..\lib\gson-2.10.1.jar
if exist META-INF\MANIFEST.MF del /f /q META-INF\MANIFEST.MF
popd

rem 5. Compile with --release 17 for the widest Burp compatibility
echo [+] Finding source files...
dir /s /b src\main\java\*.java > build\sources.txt

echo [+] Compiling sources (--release 17)...
"%JAVAC%" --release 17 -encoding UTF-8 -Xlint:-options -cp "lib\montoya-api-2023.12.1.jar;lib\gson-2.10.1.jar" -d build\classes @build\sources.txt
if errorlevel 1 goto fail

rem 6. Package the JAR
set "OUTJAR=build\libs\burp-screenshot-poc.jar"
echo [+] Packaging into %OUTJAR%...
"%JAR%" -cf "%OUTJAR%" -C build\classes .
if errorlevel 1 goto fail

rem 7. Run the verification tests.
rem    -ea is not optional: every check in the suite is an assert, and the JVM skips them
rem    silently without it, so a green run would prove nothing. The test classes go in their
rem    own directory rather than into build\classes, which is the content of the JAR.
echo [+] Running verification tests...
if exist build\test-classes rmdir /s /q build\test-classes
mkdir build\test-classes

"%JAVAC%" --release 17 -encoding UTF-8 -Xlint:-options -cp "%OUTJAR%;lib\montoya-api-2023.12.1.jar" -d build\test-classes src\test\java\burp\screenshot\ScreenshotVerificationTest.java
if errorlevel 1 goto fail

rem Every argument is quoted: cmd splits an unquoted -Dproperty=value at the dot and hands
rem java "-Djava" plus a main class of ".awt.headless=true".
"%JAVA%" "-ea" "-Djava.awt.headless=true" -cp "build\test-classes;%OUTJAR%;lib\montoya-api-2023.12.1.jar" "burp.screenshot.ScreenshotVerificationTest"
if errorlevel 1 goto fail

echo ==================================================
echo  [SUCCESS] Built and verified
echo  Output: %OUTJAR%
echo  Captures: build\test_styled_dark.png, build\test_styled_light.png
echo  Wrap captures: build\test_wrap_on.png, build\test_wrap_off.png
echo  Palette reference: build\palette_dark.png, build\palette_light.png
echo ==================================================
exit /b 0

:fail
echo [-] BUILD FAILED
exit /b 1
