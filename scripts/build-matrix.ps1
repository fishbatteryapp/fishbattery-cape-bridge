$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$matrix = Get-Content "$root/config/release-matrix.json" | ConvertFrom-Json

foreach ($target in $matrix) {
  foreach ($loader in @('fabric', 'quilt')) {
    Write-Host "==> Building MC $($target.minecraft) ($loader)"
    & "$root/gradlew.bat" clean build "-Ptarget=$($target.id)" "-Ploader=$loader"
    if ($LASTEXITCODE -ne 0) {
      throw "Build failed for MC $($target.minecraft) ($loader)"
    }
  }
}

Write-Host "All matrix builds completed."
