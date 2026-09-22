param([Parameter(Mandatory=$true)][string]$Jar)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$planRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$baseline = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'migration-source-sha256.json') | ConvertFrom-Json
$archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Jar).Path)
try {
    $migrations = @($archive.Entries | Where-Object { $_.FullName -match '^BOOT-INF/classes/db/migration/V\d+__.*\.sql$' })
    if ($migrations.Count -ne 18) { throw 'Expected exactly 18 packaged migrations' }
    foreach ($entry in $baseline.migrations) {
        $name = [System.IO.Path]::GetFileName($entry.path)
        $found = @($migrations | Where-Object { $_.Name -eq $name })
        if ($found.Count -ne 1) { throw "Missing/duplicate migration: $name" }
        $stream = $found[0].Open()
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try { $actual = ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
        finally { $stream.Dispose(); $sha.Dispose() }
        if ($actual -ne $entry.sha256) { throw "Changed packaged migration: $name" }
        if ((Get-FileHash -LiteralPath (Join-Path $planRoot $entry.path) -Algorithm SHA256).Hash -ne $entry.sha256) { throw "Changed source migration: $name" }
    }
    $forbidden = @($archive.Entries | Where-Object { $_.FullName -match 'category-transition\.properties$|compatibility/.*/(LegacyReplayService|LegacyReplayAdapter|LegacyReplayStore|LegacyCreateRequest|LegacyCreateParser|LegacyFingerprint|CreationResource)(\$.*)?\.class$' })
    if ($forbidden.Count) { throw ('Retired runtime content remains: ' + ($forbidden.FullName -join ', ')) }
    [pscustomobject]@{Jar=[System.IO.Path]::GetFileName($Jar); Sha256=(Get-FileHash -LiteralPath $Jar -Algorithm SHA256).Hash; Migrations=18; UnchangedChecksums=$true; RetiredContentAbsent=$true} | ConvertTo-Json
}
finally { $archive.Dispose() }
