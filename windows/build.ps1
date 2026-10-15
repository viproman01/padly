param([string]$Runtime = 'win-x64', [string]$OutputDirectory = "$PSScriptRoot/build/$Runtime")
$ErrorActionPreference = 'Stop'
$projectPath = Join-Path $PSScriptRoot 'Padly.Windows/Padly.Windows.csproj'
dotnet publish $projectPath -c Release -r $Runtime --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -o $OutputDirectory
if ($LASTEXITCODE -ne 0) { throw 'Padly publish failed.' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'README.md') -Destination (Join-Path $OutputDirectory 'README.md')
$bundlePath = Join-Path (Split-Path $OutputDirectory -Parent) "Padly-Windows-$Runtime.zip"
Compress-Archive -Path (Join-Path $OutputDirectory 'Padly.Windows.exe'), (Join-Path $OutputDirectory 'README.md') -DestinationPath $bundlePath -Force
Write-Output $bundlePath
