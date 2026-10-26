param(
    [Parameter(Mandatory = $true)][string]$HostRuntime,
    [Parameter(Mandatory = $true)][string]$HostArchive,
    [Parameter(Mandatory = $true)][string]$JdkHome,
    [switch]$Online
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskRuntimeSource = (Resolve-Path -LiteralPath $HostRuntime).Path
$taskArchive = (Resolve-Path -LiteralPath $HostArchive).Path
$taskJdk = (Resolve-Path -LiteralPath $JdkHome).Path
$taskRuntimeVersion = Get-Content -LiteralPath (Join-Path $taskRuntimeSource 'release') | Where-Object { $_ -match '^JAVA_VERSION=' }
$taskJdkVersion = Get-Content -LiteralPath (Join-Path $taskJdk 'release') | Where-Object { $_ -match '^JAVA_VERSION=' }
if ($taskRuntimeVersion -ne $taskJdkVersion) { throw 'Use a JDK matching the player runtime version for its diagnostic launcher.' }
$taskModuleHash = (Get-FileHash -LiteralPath (Join-Path $taskRuntimeSource 'lib\modules') -Algorithm SHA256).Hash.Substring(0, 12)
$taskRuntimeCopy = Join-Path $taskRoot ('build\runtime-check\jre-' + $taskModuleHash)
New-Item -ItemType Directory -Force -Path (Join-Path $taskRoot 'build\runtime-check') | Out-Null
if (-not (Test-Path -LiteralPath $taskRuntimeCopy)) {
    Copy-Item -LiteralPath $taskRuntimeSource -Destination $taskRuntimeCopy -Recurse
}
# The shipped runtime omits a launcher. Add it only to the workspace copy.
Copy-Item -LiteralPath (Join-Path $taskJdk 'bin\java.exe') -Destination (Join-Path $taskRuntimeCopy 'bin\java.exe') -Force
$taskRuntimeJava = Join-Path $taskRuntimeCopy 'bin\java.exe'
$taskOldJavaHome = $env:JAVA_HOME
Push-Location -LiteralPath $taskRoot
try {
    $env:JAVA_HOME = $taskJdk
    & .\gradlew.bat --offline --no-daemon test plugin runtimeRegression "-PhostRuntimeJava=$taskRuntimeJava"
    if ($LASTEXITCODE -ne 0) { throw 'Unit or trimmed-runtime regression failed.' }
    $taskBuildText = Get-Content -LiteralPath build.gradle.kts -Raw
    $taskVersion = [regex]::Match($taskBuildText, 'version = "([^"]+)"').Groups[1].Value
    $taskPackage = Join-Path $taskRoot "build\libs\plugin-dev.saltmotion-$taskVersion.spmod"
    python scripts\verify-package.py $taskPackage
    if ($LASTEXITCODE -ne 0) { throw 'Package validation failed.' }
    $taskPackageHash = (Get-FileHash -LiteralPath $taskPackage -Algorithm SHA256).Hash.Substring(0, 12)
    $taskExtracted = Join-Path $taskRoot ('build\runtime-check\package-' + $taskPackageHash)
    if (-not (Test-Path -LiteralPath $taskExtracted)) {
        [IO.Compression.ZipFile]::ExtractToDirectory($taskPackage, $taskExtracted)
    }
    $taskProbes = Join-Path $taskRoot ('build\runtime-check\probes-' + $taskPackageHash)
    New-Item -ItemType Directory -Force -Path $taskProbes | Out-Null
    $taskCompileClasspath = "$taskExtracted\classes;$taskExtracted\lib\*;$taskArchive"
    & (Join-Path $taskJdk 'bin\javac.exe') -encoding UTF-8 -cp $taskCompileClasspath -d $taskProbes scripts\NetworkRuntimeProbe.java scripts\SmokeProbe.java scripts\CoverRuntimeProbe.java scripts\FrameRateProbe.java scripts\RenderLifecycleProbe.java scripts\CacheImageProbe.java scripts\CoverEditorProbe.java
    if ($LASTEXITCODE -ne 0) { throw 'Runtime probe compilation failed.' }
    $taskOutput = Join-Path $taskRoot 'build\reports\runtime-regression'
    New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null
    $taskProbeArgs = @(
        '--enable-native-access=ALL-UNNAMED',
        '-Xmx512M',
        '-Dstdout.encoding=UTF-8',
        ('-Dskiko.library.path=' + [IO.Path]::GetDirectoryName($taskArchive)),
        '-cp', "$taskProbes;$taskArchive",
        'NetworkRuntimeProbe', $taskExtracted, $taskProbes
    )
    if ($Online) { $taskProbeArgs += @('online', $taskOutput) }
    & $taskRuntimeJava @taskProbeArgs 2>&1 | Tee-Object -FilePath (Join-Path $taskOutput 'packaged-runtime.log')
    if ($LASTEXITCODE -ne 0) { throw 'Packaged PF4J runtime probe failed.' }
    if ($Online) {
        $taskCacheOutput = Join-Path $taskRoot 'build\reports\cache-image'
        New-Item -ItemType Directory -Force -Path $taskCacheOutput | Out-Null
        & $taskRuntimeJava '--enable-native-access=ALL-UNNAMED' '-Xmx512M' '-Dstdout.encoding=UTF-8' ('-Dskiko.library.path=' + [IO.Path]::GetDirectoryName($taskArchive)) -cp "$taskProbes;$taskCompileClasspath" CacheImageProbe $taskCacheOutput 2>&1 | Tee-Object -FilePath (Join-Path $taskCacheOutput 'probe.log')
        if ($LASTEXITCODE -ne 0) { throw 'Cache/offline/owned-image runtime probe failed.' }
    }
} finally {
    Pop-Location
    $env:JAVA_HOME = $taskOldJavaHome
}
