param([Parameter(Mandatory=$true)][string]$Apk)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskArchive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Apk))
try {
    foreach ($taskName in @('rain','snow')) {
        $taskEntry = $taskArchive.GetEntry("assets/portraits/$taskName.aeph")
        if ($null -eq $taskEntry) { throw "APK is missing the $taskName portrait at its runtime asset path." }
        $taskStream = $taskEntry.Open()
        try {
            $taskGzip = [IO.Compression.GZipStream]::new($taskStream,[IO.Compression.CompressionMode]::Decompress)
            try {
                $taskMagic = New-Object byte[] 8
                $taskRead = 0
                while ($taskRead -lt 8) {
                    $taskCount = $taskGzip.Read($taskMagic,$taskRead,8-$taskRead)
                    if ($taskCount -eq 0) { throw "Truncated $taskName portrait in APK." }
                    $taskRead += $taskCount
                }
                if ([Text.Encoding]::ASCII.GetString($taskMagic) -ne 'AEPH0001') { throw "Invalid $taskName portrait in APK." }
            } finally { $taskGzip.Dispose() }
        } finally { $taskStream.Dispose() }
    }
    foreach ($taskName in @('ATTRIBUTION.txt','CC-BY-4.0.txt')) {
        if ($null -eq $taskArchive.GetEntry("assets/portraits/$taskName")) { throw "APK is missing $taskName." }
    }
    Write-Output 'APK contains both compressed authored portraits at their runtime paths and the required credits.'
} finally { $taskArchive.Dispose() }
