[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$url = 'https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jdk/hotspot/normal/eclipse'
$runtimeDir = Join-Path $env:USERPROFILE '.blen-launcher\runtime'
$zipPath = Join-Path $env:TEMP 'jdk25.zip'

if (-not (Test-Path $runtimeDir)) {
    New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
}

Invoke-WebRequest -Uri $url -OutFile $zipPath
Expand-Archive -Path $zipPath -DestinationPath $runtimeDir -Force
Remove-Item $zipPath -Force
