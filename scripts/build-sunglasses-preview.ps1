param(
    [string]$Output = 'assets/previews/standalone-sunglasses.png',
    [string]$CaseDirectory = 'scratch/sunglasses-preview'
)
$ErrorActionPreference = 'Stop'
# Render actual production geometry with synthetic poses, without Android or a headset.
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
$taskClasses = 'scratch/sunglasses-preview-classes'
New-Item -ItemType Directory -Force -Path $taskClasses | Out-Null
$taskSources = @(
    'app/src/main/java/com/oculus/camerademo/model3d/PortraitMesh.kt',
    'app/src/main/java/com/oculus/camerademo/model3d/CartoonCreatureMesh.kt',
    'app/src/main/java/com/oculus/camerademo/model3d/PortraitHairMesh.kt',
    'app/src/main/java/com/oculus/camerademo/model3d/AuthoredPortraitStore.kt',
    'app/src/main/java/com/oculus/camerademo/model3d/PortraitSurface.kt',
    'app/src/main/java/com/oculus/camerademo/model3d/PortraitSunglasses.kt',
    'app/src/main/java/com/oculus/camerademo/ml/FaceEmotionState.kt',
    'app/src/main/java/com/oculus/camerademo/renderer/PortraitDepthBuffer.kt'
)
foreach ($taskSource in $taskSources) {
    if (-not (Test-Path -LiteralPath $taskSource)) { throw "Production preview source is not ready: $taskSource" }
}
& (Join-Path $taskJavaDirectory 'java.exe') '-Xmx1g' '-cp' $taskCompilerClasspath `
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' '-no-stdlib' '-no-reflect' `
    '-classpath' $taskStdlib '-jvm-target' '17' '-d' $taskClasses @taskSources
if ($LASTEXITCODE -ne 0) { throw 'Actual sunglasses Kotlin compilation failed.' }
$taskRuntimeClasspath = @($taskClasses,$taskStdlib) -join [IO.Path]::PathSeparator
& (Join-Path $taskJavaDirectory 'javac.exe') '-encoding' 'UTF-8' '-cp' $taskRuntimeClasspath '-d' $taskClasses 'scripts/SunglassesPreview.java'
if ($LASTEXITCODE -ne 0) { throw 'Sunglasses preview harness compilation failed.' }
& (Join-Path $taskJavaDirectory 'java.exe') '-Xmx1g' '-Djava.awt.headless=true' '-Dfile.encoding=UTF-8' '-cp' $taskRuntimeClasspath `
    'SunglassesPreview' $Output $CaseDirectory
if ($LASTEXITCODE -ne 0) { throw 'Production sunglasses preview rendering failed.' }
