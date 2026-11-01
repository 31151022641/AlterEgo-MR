param(
    [switch]$Quick,
    [switch]$ArtPortraits,
    [string]$Styles = 'all',
    [int]$Detail = 24,
    [string]$RasterDirectory = '',
    [string]$Output = 'scratch/portrait-mesh-v2.json',
    [string]$SourceDirectory = 'app/src/main/java/com/oculus/camerademo/model3d'
)
$ErrorActionPreference = 'Stop'
# Compile actual JVM mesh sources without the Android framework or network access.
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
$taskClasses = 'scratch/portrait-preview-v2-classes'
New-Item -ItemType Directory -Force -Path $taskClasses | Out-Null
$taskSources = @(
    (Join-Path $SourceDirectory 'PortraitMesh.kt'),
    'app/src/main/java/com/oculus/camerademo/ml/FaceEmotionState.kt'
)
$taskCreatures = Join-Path $SourceDirectory 'CartoonCreatureMesh.kt'
if (Test-Path -LiteralPath $taskCreatures) { $taskSources += $taskCreatures }
$taskHair = 'app/src/main/java/com/oculus/camerademo/model3d/PortraitHairMesh.kt'
if (Test-Path -LiteralPath $taskHair) { $taskSources += $taskHair }
$taskAuthored = 'app/src/main/java/com/oculus/camerademo/model3d/AuthoredPortraitStore.kt'
if (Test-Path -LiteralPath $taskAuthored) { $taskSources += $taskAuthored }
$taskSurface = 'app/src/main/java/com/oculus/camerademo/model3d/PortraitSurface.kt'
if (Test-Path -LiteralPath $taskSurface) { $taskSources += $taskSurface }
$taskSunglasses = 'app/src/main/java/com/oculus/camerademo/model3d/PortraitSunglasses.kt'
if (Test-Path -LiteralPath $taskSunglasses) { $taskSources += $taskSunglasses }
$taskRaster = 'app/src/main/java/com/oculus/camerademo/renderer/PortraitDepthBuffer.kt'
if (Test-Path -LiteralPath $taskRaster) { $taskSources += $taskRaster }
& (Join-Path $taskJavaDirectory 'java.exe') '-Xmx1g' '-cp' $taskCompilerClasspath `
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' '-no-stdlib' '-no-reflect' `
    '-classpath' $taskStdlib '-jvm-target' '17' '-d' $taskClasses @taskSources
if ($LASTEXITCODE -ne 0) { throw 'Actual mesh Kotlin compilation failed.' }
& (Join-Path $taskJavaDirectory 'javac.exe') '-d' $taskClasses 'scripts/PortraitMeshPreview.java'
if ($LASTEXITCODE -ne 0) { throw 'Mesh preview harness compilation failed.' }
$taskArguments = @('-cp', ($taskClasses + [IO.Path]::PathSeparator + $taskStdlib), 'PortraitMeshPreview', $Output, $Detail)
$taskArguments += $(if ($ArtPortraits) { '--art' } elseif ($Quick) { '--quick' } else { '--full' })
$taskArguments += $Styles
if ($RasterDirectory) { $taskArguments += $RasterDirectory }
& (Join-Path $taskJavaDirectory 'java.exe') @taskArguments
if ($LASTEXITCODE -ne 0) { throw 'Mesh preview export failed.' }
