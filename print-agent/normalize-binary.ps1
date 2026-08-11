param(
    [Parameter(Mandatory = $true)]
    [string] $ExecutablePath,
    [Parameter(Mandatory = $true)]
    [string] $SourcePath
)

$ErrorActionPreference = 'Stop'

function Find-ByteSequence([byte[]] $Haystack, [byte[]] $Needle) {
    $positions = New-Object 'System.Collections.Generic.List[int]'
    for ($index = 0; $index -le $Haystack.Length - $Needle.Length; $index++) {
        $matches = $true
        for ($offset = 0; $offset -lt $Needle.Length; $offset++) {
            if ($Haystack[$index + $offset] -ne $Needle[$offset]) {
                $matches = $false
                break
            }
        }
        if ($matches) {
            $positions.Add($index)
        }
    }
    return $positions.ToArray()
}

$executable = [System.IO.Path]::GetFullPath($ExecutablePath)
$source = [System.IO.Path]::GetFullPath($SourcePath)
if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) {
    throw "Executable not found: $executable"
}
if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
    throw "Source not found: $source"
}

$bytes = [System.IO.File]::ReadAllBytes($executable)
if ($bytes.Length -lt 256) {
    throw 'Executable is too small to be a valid PE file.'
}

$peOffset = [BitConverter]::ToInt32($bytes, 0x3c)
if ($peOffset -lt 0 -or $peOffset + 12 -ge $bytes.Length) {
    throw 'Invalid PE header offset.'
}
if ($bytes[$peOffset] -ne 0x50 -or $bytes[$peOffset + 1] -ne 0x45) {
    throw 'PE signature not found.'
}

$ascii = [System.Text.Encoding]::ASCII.GetString($bytes)
$guidPattern = [regex]'<PrivateImplementationDetails>\{(?<guid>[0-9A-Fa-f-]{36})\}'
$guidMatches = $guidPattern.Matches($ascii)
if ($guidMatches.Count -ne 1) {
    throw "Expected one compiler-generated GUID, found $($guidMatches.Count)."
}

$oldGuidText = $guidMatches[0].Groups['guid'].Value
$oldGuid = [Guid]::Parse($oldGuidText)

$sourceText = [System.IO.File]::ReadAllText($source, [System.Text.Encoding]::UTF8)
$canonicalSource = $sourceText.Replace("`r`n", "`n").Replace("`r", "`n")
$canonicalSourceBytes = (New-Object System.Text.UTF8Encoding($false)).GetBytes($canonicalSource)
$sha256 = [System.Security.Cryptography.SHA256]::Create()
try {
    $sourceHash = $sha256.ComputeHash($canonicalSourceBytes)
}
finally {
    $sha256.Dispose()
}
$hex = -join ($sourceHash[0..15] | ForEach-Object { $_.ToString('x2') })
$newGuidText = '{0}-{1}-{2}-{3}-{4}' -f `
    $hex.Substring(0, 8), `
    $hex.Substring(8, 4), `
    $hex.Substring(12, 4), `
    $hex.Substring(16, 4), `
    $hex.Substring(20, 12)
$newGuid = [Guid]::Parse($newGuidText)

$guidTextOffset = $guidMatches[0].Groups['guid'].Index
$newGuidAscii = [System.Text.Encoding]::ASCII.GetBytes($newGuid.ToString().ToUpperInvariant())
[Array]::Copy($newGuidAscii, 0, $bytes, $guidTextOffset, $newGuidAscii.Length)

$oldGuidBytes = $oldGuid.ToByteArray()
$guidBytePositions = @(Find-ByteSequence $bytes $oldGuidBytes)
if ($guidBytePositions.Count -ne 1) {
    throw "Expected one binary MVID, found $($guidBytePositions.Count)."
}
$newGuidBytes = $newGuid.ToByteArray()
[Array]::Copy($newGuidBytes, 0, $bytes, $guidBytePositions[0], $newGuidBytes.Length)

# The legacy .NET Framework compiler writes wall-clock time into the COFF header.
# Zeroing it plus normalizing MVID/private implementation GUID makes identical
# source and compiler inputs byte-for-byte reproducible.
$timestampOffset = $peOffset + 8
for ($index = 0; $index -lt 4; $index++) {
    $bytes[$timestampOffset + $index] = 0
}

[System.IO.File]::WriteAllBytes($executable, $bytes)
Write-Host "Normalized PE timestamp and MVID: $executable"
