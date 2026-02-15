# Fishbattery Cape Bridge Mod

Standalone repo scaffold for the launcher cape bridge mod.

## Targets
- Fabric and Quilt
- Minecraft versions: 1.21.11 down to 1.16 (matrix in `config/release-matrix.json`)

## Build one target
```powershell
./gradlew.bat clean build -Ptarget=mc1211 -Ploader=fabric
./gradlew.bat clean build -Ptarget=mc1211 -Ploader=quilt
```

## Build all targets (local)
```powershell
./scripts/build-matrix.ps1
```

## Release automation
GitHub Actions workflow: `.github/workflows/release.yml`
- Builds every MC + loader target in `config/release-matrix.json`.
- Publishes grouped releases by loader:
  - `v<modVersion>-fabric`
  - `v<modVersion>-quilt`
- Each grouped release contains all target jars named:
  - `fishbattery-cape-bridge-<modVersion>-<minecraft>-<loader>.jar`

## Migrate old releases
To migrate legacy tags (`v<minecraft>-<modVersion>-<loader>`) to grouped tags:
```powershell
node ./scripts/migrate-release-layout.mjs --delete-old
```

## Notes
- Source currently uses modern client skin APIs from the existing bridge implementation.
- If specific older targets fail due upstream mapping/runtime API differences, pin/add compatibility source in future commits.
