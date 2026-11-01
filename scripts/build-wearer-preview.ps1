param(
    [int]$Detail = 14,
    [string]$Output = 'assets/previews/upper-body-avatar-v1.png',
    [string]$CaseDirectory = 'scratch/upper-body-preview'
)
$ErrorActionPreference = 'Stop'
# Compile and render actual production geometry on the JVM. Input joint poses
# are synthetic; this does not acquire or simulate physical headset tracking.
$taskJavaDirectory = 'C:/Program Files/Microsoft/jdk-17.0.20.101-hotspot/bin'
$taskGradleModules = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
function Find-CachedJar([string]$relative) {
    $taskJars = @(Get-ChildItem -LiteralPath (Join-Path $taskGradleModules $relative) -Filter '*.jar' -Recurse)
    if ($taskJars.Count -ne 1) { throw "Expected one cached JAR: $relative" }
    return $taskJars[0].FullName
}
$taskStdlib = Find-CachedJar 'org.jetbrains.kotlin/kotlin-stdlib/1.9.0'
$taskCompilerClasspath = @(
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/1.9.0'),
    $taskStdlib,
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-script-runtime/1.9.0'),
    (Find-CachedJar 'org.jetbrains.kotlin/kotlin-reflect/1.6.10'),
    (Find-CachedJar 'org.jetbrains.intellij.deps/trove4j/1.0.20200330'),
    (Find-CachedJar 'org.jetbrains/annotations/13.0')
) -join [IO.Path]::PathSeparator
$taskClasses = 'scratch/wearer-preview-classes'
New-Item -ItemType Directory -Force -Path $taskClasses | Out-Null
$taskSdk = Join-Path $taskClasses 'meta-spatial-sdk.jar'
# Use the same cached production SDK dependency as the app; do not require
# manually copied diagnostic JARs or modify the Gradle cache.
$taskSdkAars = @(Get-ChildItem -LiteralPath (Join-Path $taskGradleModules 'com.meta.spatial/meta-spatial-sdk/0.14.0') -Filter '*.aar' -Recurse)
if ($taskSdkAars.Count -ne 1) { throw 'Expected one cached Meta Spatial SDK 0.14.0 AAR. Build the app first.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskSdkArchive = [IO.Compression.ZipFile]::OpenRead($taskSdkAars[0].FullName)
try {
    $taskSdkEntry = $taskSdkArchive.GetEntry('classes.jar')
    if ($null -eq $taskSdkEntry) { throw 'The cached production SDK has no classes.jar.' }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($taskSdkEntry, (Join-Path $PWD $taskSdk), $true)
} finally { $taskSdkArchive.Dispose() }
$taskAndroidStubs = Join-Path $env:USERPROFILE 'AppData/Local/Android/Sdk/platforms/android-34/android.jar'
foreach ($taskDependency in @($taskSdk,$taskAndroidStubs)) {
    if (-not (Test-Path -LiteralPath $taskDependency)) { throw "Missing cached compile dependency: $taskDependency" }
}
$taskClasspath = @($taskStdlib,$taskSdk,$taskAndroidStubs) -join [IO.Path]::PathSeparator
$taskSources = @(
    'app/src/main/java/com/oculus/camerademo/model3d/PortraitMesh.kt',
    'app/src/main/java/com/oculus/camerademo/model3d/CartoonCreatureMesh.kt',
    'app/src/main/java/com/oculus/camerademo/model3d/PortraitHairMesh.kt',
    'app/src/main/java/com/oculus/camerademo/model3d/AuthoredPortraitStore.kt',
    'app/src/main/java/com/oculus/camerademo/model3d/PortraitSurface.kt',
    'app/src/main/java/com/oculus/camerademo/model3d/PortraitSunglasses.kt',
    'app/src/main/java/com/oculus/camerademo/model3d/UpperBodyMesh.kt',
    'app/src/main/java/com/oculus/camerademo/ml/FaceEmotionState.kt',
    'app/src/main/java/com/oculus/camerademo/renderer/PortraitDepthBuffer.kt',
    'app/src/main/java/com/oculus/camerademo/mirror/WearerAvatarMesh.kt',
    'app/src/main/java/com/oculus/camerademo/mirror/AvatarMirrorFraming.kt'
)
foreach ($taskSource in $taskSources) {
    if (-not (Test-Path -LiteralPath $taskSource)) { throw "Production preview source is not ready: $taskSource" }
}
& (Join-Path $taskJavaDirectory 'java.exe') '-Xmx1g' '-cp' $taskCompilerClasspath `
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' '-no-stdlib' '-no-reflect' '-Xskip-metadata-version-check' `
    '-classpath' $taskClasspath '-jvm-target' '17' '-d' $taskClasses @taskSources
if ($LASTEXITCODE -ne 0) { throw 'Actual upper-body Kotlin compilation failed.' }
$taskRuntimeClasspath = @($taskClasses,$taskStdlib,$taskSdk,$taskAndroidStubs) -join [IO.Path]::PathSeparator
& (Join-Path $taskJavaDirectory 'javac.exe') '-encoding' 'UTF-8' '-cp' $taskRuntimeClasspath '-d' $taskClasses 'scripts/WearerAvatarPreview.java'
if ($LASTEXITCODE -ne 0) { throw 'Upper-body preview harness compilation failed.' }
& (Join-Path $taskJavaDirectory 'java.exe') '-Xmx1g' '-Djava.awt.headless=true' '-Dfile.encoding=UTF-8' '-cp' $taskRuntimeClasspath `
    'WearerAvatarPreview' $Output $CaseDirectory $Detail
if ($LASTEXITCODE -ne 0) { throw 'Production upper-body preview rendering failed.' }
