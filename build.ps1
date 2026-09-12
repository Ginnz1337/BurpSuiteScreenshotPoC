$ErrorActionPreference = "Stop"

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host "  Building Burp Suite PoC Screenshot Extension    " -ForegroundColor Green
Write-Host "==================================================" -ForegroundColor Cyan

# 1. Detect the newest installed JDK.
#    The previous list named jdk-25.0.4.1, "latest" and jdk-17, none of which exist on a
#    normal machine, so the build silently fell back to whatever javac was on PATH. Scan
#    the install root and pick the highest version instead of guessing at names.
$javaHome = $env:JAVA_HOME
if ($javaHome -and -not (Test-Path "$javaHome\bin\javac.exe")) { $javaHome = $null }

if (-not $javaHome) {
    $candidates = Get-ChildItem -Path "C:\Program Files\Java" -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Path "$($_.FullName)\bin\javac.exe" } |
        Sort-Object {
            $v = $_.Name -replace '^[^0-9]*', ''
            try { [version]($v -replace '[^0-9.].*$', '') } catch { [version]'0.0' }
        } -Descending

    if ($candidates) { $javaHome = $candidates[0].FullName }
}

if ($javaHome) {
    $javac = "$javaHome\bin\javac.exe"
    $jar = "$javaHome\bin\jar.exe"
    $java = "$javaHome\bin\java.exe"
} else {
    Write-Host "[!] No JDK found under C:\Program Files\Java; falling back to PATH." -ForegroundColor Yellow
    $javac = "javac"; $jar = "jar"; $java = "java"
}

Write-Host "[+] Using compiler: $javac"
& $javac -version

# 2. Prepare directories
$buildDir = "build"
$classesDir = "$buildDir\classes"
$libsDir = "$buildDir\libs"

if (Test-Path $classesDir) { Remove-Item -Recurse -Force $classesDir }
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null
New-Item -ItemType Directory -Force -Path $libsDir | Out-Null

# 3. Extract bundled libraries (Gson) into the classes directory for a fat JAR
Write-Host "[+] Unpacking dependencies for Fat JAR..."
Push-Location $classesDir
& $jar -xf "..\..\lib\gson-2.10.1.jar"
if (Test-Path "META-INF\MANIFEST.MF") { Remove-Item -Force "META-INF\MANIFEST.MF" }
Pop-Location

# 4. Find all java source files
$sources = Get-ChildItem -Recurse -Filter "*.java" -Path "src\main\java" |
    Select-Object -ExpandProperty FullName
$sourcesFile = "$buildDir\sources.txt"
$sources | Out-File -FilePath $sourcesFile -Encoding ascii

# 5. Compile with --release 17 for the widest Burp compatibility
Write-Host "[+] Compiling Java source files..."
$classpath = "lib\montoya-api-2023.12.1.jar;lib\gson-2.10.1.jar"
& $javac --release 17 -encoding UTF-8 -Xlint:-options -cp $classpath -d $classesDir "@$sourcesFile"

if ($LASTEXITCODE -ne 0) {
    Write-Host "[-] Compilation failed!" -ForegroundColor Red
    exit 1
}

# 6. Package the JAR
$outputJar = "$libsDir\burp-screenshot-poc.jar"
Write-Host "[+] Packaging into $outputJar..."
& $jar -cf $outputJar -C $classesDir .

if ($LASTEXITCODE -ne 0) {
    Write-Host "[-] Failed to package JAR." -ForegroundColor Red
    exit 1
}

# 7. Run the verification tests.
#    -ea is not optional: every check in the suite is an assert, and the JVM skips them
#    silently without it, so a green run would prove nothing.
Write-Host "[+] Running verification tests..."
$testOut = "$buildDir\test-classes"
if (Test-Path $testOut) { Remove-Item -Recurse -Force $testOut }
New-Item -ItemType Directory -Force -Path $testOut | Out-Null

& $javac --release 17 -encoding UTF-8 -Xlint:-options `
    -cp "$outputJar;lib\montoya-api-2023.12.1.jar" `
    -d $testOut src\test\java\burp\screenshot\ScreenshotVerificationTest.java

if ($LASTEXITCODE -ne 0) {
    Write-Host "[-] Test compilation failed!" -ForegroundColor Red
    exit 1
}

# Every argument is quoted: PowerShell 5.1 splits an unquoted -Dproperty=value at the dot
# and hands java "-Djava" plus a main class of ".awt.headless=true".
$testClasspath = "$testOut;$outputJar;lib\montoya-api-2023.12.1.jar"
& $java "-ea" "-Djava.awt.headless=true" "-cp" $testClasspath `
    "burp.screenshot.ScreenshotVerificationTest"

if ($LASTEXITCODE -ne 0) {
    Write-Host "[-] Verification tests failed!" -ForegroundColor Red
    exit 1
}

$size = (Get-Item $outputJar).Length / 1KB
Write-Host "==================================================" -ForegroundColor Green
Write-Host " [SUCCESS] Built and verified" -ForegroundColor Green
Write-Host " Output: $outputJar ($([Math]::Round($size, 1)) KB)" -ForegroundColor Cyan
Write-Host " Preview images: build\test_poc_dark.png, build\test_poc_light.png" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Green
