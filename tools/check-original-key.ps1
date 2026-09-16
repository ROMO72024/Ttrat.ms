param(
    [string]$KeyStorePath = (Join-Path $env:USERPROFILE '.android\debug.keystore'),
    [string]$KeyAlias = 'androiddebugkey',
    [switch]$ExportSecrets,
    [string]$OutputDirectory = (Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'GharsOriginalSigning')
)

# Read an existing Debug key. Never create, replace or alter a keystore.
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $KeyStorePath -PathType Leaf)) {
    throw 'Original key not found. If GitHub built the APK, the key was on its runner. Do not generate a new key.'
}
$command = Get-Command keytool -ErrorAction SilentlyContinue
$keytoolPath = if ($command) { $command.Source } else { $null }
if (-not $keytoolPath -and $env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    if (Test-Path -LiteralPath $candidate) { $keytoolPath = $candidate }
}
if (-not $keytoolPath) { throw 'JDK is required: keytool was not found.' }
$expectedFile = Join-Path $PSScriptRoot 'legacy-apk.json'
$expected = (Get-Content -LiteralPath $expectedFile -Raw -Encoding UTF8 | ConvertFrom-Json).certificateSha256
$certificatePath = Join-Path ([IO.Path]::GetTempPath()) ('ghars-cert-' + [guid]::NewGuid().ToString('N') + '.der')
$previousPassword = [Environment]::GetEnvironmentVariable('GHARS_ORIGINAL_DEBUG_PASSWORD', 'Process')
$env:GHARS_ORIGINAL_DEBUG_PASSWORD = 'android'
try {
    & $keytoolPath -exportcert -keystore $KeyStorePath -alias $KeyAlias -storepass:env GHARS_ORIGINAL_DEBUG_PASSWORD -file $certificatePath
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read this Debug key. Check its alias/password. No key was modified.' }
    $actual = (Get-FileHash -LiteralPath $certificatePath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Host "Certificate SHA-256: $actual"
    if ($actual -ne $expected) { throw 'This is NOT the key of the attached original APK. Do not use it to build its update.' }
    Write-Host 'MATCH: this certificate belongs to the original app-debug.apk.'
    if ($ExportSecrets) {
        $secretPath = Join-Path $OutputDirectory 'github-secrets.txt'
        if (Test-Path -LiteralPath $secretPath) { throw 'The export already exists. It will not be overwritten.' }
        New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
        $encoded = [Convert]::ToBase64String([IO.File]::ReadAllBytes($KeyStorePath))
        $text = @(
            'GHARS_KEYSTORE_BASE64', $encoded, '',
            'GHARS_STORE_PASSWORD', 'android', '',
            'GHARS_KEY_ALIAS', $KeyAlias, '',
            'GHARS_KEY_PASSWORD', 'android'
        ) -join [Environment]::NewLine
        [IO.File]::WriteAllText($secretPath, $text, (New-Object Text.UTF8Encoding($false)))
        Write-Host "Copy values into GitHub Actions Secrets from: $secretPath"
        Write-Host 'Keep this file and the original keystore outside the repository.'
    }
} finally {
    Remove-Item -LiteralPath $certificatePath -ErrorAction SilentlyContinue
    [Environment]::SetEnvironmentVariable('GHARS_ORIGINAL_DEBUG_PASSWORD', $previousPassword, 'Process')
}
