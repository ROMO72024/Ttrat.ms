param(
    [string]$OutputDirectory = (Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'GharsSigning')
)

$ErrorActionPreference = 'Stop'
$keytoolCommand = Get-Command keytool -ErrorAction SilentlyContinue
$keytoolPath = if ($keytoolCommand) { $keytoolCommand.Source } else { $null }
if (-not $keytoolPath -and $env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    if (Test-Path -LiteralPath $candidate) { $keytoolPath = $candidate }
}
if (-not $keytoolPath) { throw 'JDK 17 is required. Install it or use your existing signing key; see START-HERE-AR.md.' }

$keyPath = Join-Path $OutputDirectory 'ghars-release.jks'
$secretPath = Join-Path $OutputDirectory 'github-secrets.txt'
if ((Test-Path -LiteralPath $keyPath) -or (Test-Path -LiteralPath $secretPath)) {
    throw 'Signing files already exist. Reuse them; this script will not overwrite a permanent key.'
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
$bytes = New-Object byte[] 24
$random.GetBytes($bytes)
$password = [Convert]::ToBase64String($bytes)
$env:GHARS_SETUP_KEY_PASSWORD = $password
try {
    & $keytoolPath -genkeypair -keystore $keyPath -storetype JKS -alias ghars -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Ghars Specialist, OU=School, O=Ghars, C=PS' -storepass:env GHARS_SETUP_KEY_PASSWORD -keypass:env GHARS_SETUP_KEY_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed. No APK has been built.' }
    $encoded = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keyPath))
    $secrets = @(
        'GHARS_KEYSTORE_BASE64', $encoded, '',
        'GHARS_STORE_PASSWORD', $password, '',
        'GHARS_KEY_ALIAS', 'ghars', '',
        'GHARS_KEY_PASSWORD', $password
    ) -join [Environment]::NewLine
    [IO.File]::WriteAllText($secretPath, $secrets, (New-Object Text.UTF8Encoding($false)))
    Write-Host "Created permanent signing key: $keyPath"
    Write-Host "Copy the four values into GitHub Actions secrets from: $secretPath"
    Write-Host 'Keep both files outside the repository. Reuse the same key for every future update.'
} finally {
    Remove-Item Env:\GHARS_SETUP_KEY_PASSWORD -ErrorAction SilentlyContinue
    $password = $null
    $random.Dispose()
}
