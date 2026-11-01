param([string]$CompilerRoot='C:\Program Files\Microsoft Visual Studio\2022\Preview\VC\Tools\MSVC\14.44.35109', [string]$SdkVersion='10.0.26100.0')
$ErrorActionPreference='Stop'
$taskSdk='C:\Program Files (x86)\Windows Kits\10'
$taskOutput=Join-Path $PSScriptRoot 'dist'
$taskArguments=@('/nologo','/std:c++17','/EHsc','/MT','/O2',"/I$CompilerRoot\include")
foreach($taskHeader in @('ucrt','shared','um','winrt','cppwinrt')) { $taskArguments+="/I$taskSdk\Include\$SdkVersion\$taskHeader" }
$taskArguments+=@((Join-Path $PSScriptRoot 'MediaCaptureProbe.cpp'),"/Fo$taskOutput\MediaCaptureProbe.obj","/Fe$taskOutput\MediaCaptureProbe.exe",'/link',"/LIBPATH:$CompilerRoot\lib\x64","/LIBPATH:$taskSdk\Lib\$SdkVersion\um\x64","/LIBPATH:$taskSdk\Lib\$SdkVersion\ucrt\x64",'windowsapp.lib','runtimeobject.lib','ole32.lib')
& (Join-Path $CompilerRoot 'bin\Hostx64\x64\cl.exe') @taskArguments
if($LASTEXITCODE -ne 0){throw 'MediaCapture compatibility probe did not build'}
