param(
    [Parameter(Mandatory=$true)][string]$HostArchive,
    [Parameter(Mandatory=$true)][string]$JdkHome,
    [Parameter(Mandatory=$true)][string]$RuntimeJava,
    [switch]$Online,
    [string[]]$AudioFiles=@(),
    [string]$CachedAlbumDirectory,
    [string]$MotionPoster
)
$ErrorActionPreference='Stop'
$taskRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskArchive=(Resolve-Path -LiteralPath $HostArchive).Path
$taskJdk=(Resolve-Path -LiteralPath $JdkHome).Path
$taskJava=(Resolve-Path -LiteralPath $RuntimeJava).Path
$taskVersion=[regex]::Match([IO.File]::ReadAllText((Join-Path $taskRoot 'build.gradle.kts')),'version = "([^"]+)"').Groups[1].Value
$taskSpmod=Join-Path $taskRoot "build\libs\plugin-dev.saltmotion-$taskVersion.spmod"
$taskHash=(Get-FileHash -LiteralPath $taskSpmod -Algorithm SHA256).Hash.Substring(0,12)
$taskPackage=Join-Path $taskRoot ('build\runtime-check\package-'+$taskHash)
if(-not(Test-Path -LiteralPath $taskPackage)){[IO.Compression.ZipFile]::ExtractToDirectory($taskSpmod,$taskPackage)}
$taskProbe=Join-Path $taskRoot 'build\editor-probe'
$taskOutput=Join-Path $taskRoot 'build\reports\cover-editor'
$taskUiOutput=Join-Path $taskRoot 'build\reports\cover-studio-ui'
New-Item -ItemType Directory -Force -Path $taskProbe,$taskOutput,$taskUiOutput | Out-Null
$taskCp="$taskPackage\classes;$taskPackage\lib\*;$taskArchive"
$taskHost=[IO.Path]::GetDirectoryName($taskArchive)
$taskCommon=@('--enable-native-access=ALL-UNNAMED','-Xmx512M','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8',"-Dskiko.library.path=$taskHost")
Push-Location -LiteralPath $taskRoot
try{
    & (Join-Path $taskJdk 'bin\javac.exe') -encoding UTF-8 -cp $taskCp -d $taskProbe scripts\CoverEditorProbe.java scripts\CoverTagProbe.java scripts\NativeCoverCall.java scripts\ProbeAgent.java scripts\MenuInsertionProbe.java
    if($LASTEXITCODE -ne 0){throw 'Editor probe compilation failed'}
    $taskArgs=@($taskOutput)
    if($Online){$taskArgs+='online'}
    & $taskJava @taskCommon -cp "$taskProbe;$taskCp" CoverEditorProbe @taskArgs 2>&1 | Tee-Object -FilePath build\editor-online.log
    if($LASTEXITCODE -ne 0){throw 'Catalog or host target verification failed'}
    if($MotionPoster){Copy-Item -LiteralPath (Resolve-Path -LiteralPath $MotionPoster).Path -Destination (Join-Path $taskOutput 'MOTION-24k-magic.png') -Force}
    if($CachedAlbumDirectory){
        $taskCached=(Resolve-Path -LiteralPath $CachedAlbumDirectory).Path
        $taskCacheParent=Join-Path $taskUiOutput 'native-scene\motion-cache'
        $taskCacheAlbum=Join-Path $taskCacheParent '1161503945'
        New-Item -ItemType Directory -Force -Path $taskCacheParent | Out-Null
        if(-not(Test-Path -LiteralPath $taskCacheAlbum)){Copy-Item -LiteralPath $taskCached -Destination $taskCacheAlbum -Recurse}
    }
    $taskManifest="Manifest-Version: 1.0`r`nPremain-Class: ProbeAgent`r`nCan-Redefine-Classes: true`r`nCan-Retransform-Classes: true`r`n`r`n"
    [IO.File]::WriteAllText((Join-Path $taskProbe 'agent.mf'),$taskManifest,[Text.UTF8Encoding]::new($false))
    & (Join-Path $taskJdk 'bin\jar.exe') --create --file (Join-Path $taskProbe 'probe-agent.jar') --manifest (Join-Path $taskProbe 'agent.mf') -C $taskProbe ProbeAgent.class
    if($LASTEXITCODE -ne 0){throw 'Private probe agent packaging failed'}
    # This premain agent affects only the newly launched test JVM, never an existing process.
    & (Join-Path $taskJdk 'bin\java.exe') @taskCommon ("-javaagent:"+(Join-Path $taskProbe 'probe-agent.jar')) -cp "$taskProbe;$taskCp" MenuInsertionProbe (Join-Path $taskRoot 'build\reports\native-menu') 2>&1 | Tee-Object -FilePath build\editor-menu.log
    if($LASTEXITCODE -ne 0){throw 'Native menu insertion/click verification failed'}
    & $taskJava @taskCommon -cp "$taskRoot\build\classes\kotlin\test;$taskProbe;$taskCp" dev.saltmotion.StudioRenderProbe $taskUiOutput $taskOutput 2>&1 | Tee-Object -FilePath build\editor-ui.log
    if($LASTEXITCODE -ne 0){throw 'Compose UI or native cover override verification failed'}
    $taskPoster=Get-ChildItem -LiteralPath $taskOutput -File -Filter 'APPLE-album.*' | Where-Object Extension -in '.jpg','.png' | Select-Object -First 1 -ExpandProperty FullName
    if(-not $taskPoster){throw 'Run with -Online once to obtain the tag-test cover image'}
    & $taskJava @taskCommon -cp "$taskProbe;$taskCp" CoverTagProbe (Join-Path $taskRoot 'build\reports\cover-tag') $taskPoster @AudioFiles 2>&1 | Tee-Object -FilePath build\editor-tag.log
    if($LASTEXITCODE -ne 0){throw 'Workspace audio-copy tag verification failed'}
    Write-Output 'PASS all cover-editor probes; user music and running player untouched'
}finally{Pop-Location}
