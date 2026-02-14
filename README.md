# Fishbattery Cape Bridge Mod

Standalone repo scaffold for the launcher cape bridge mod.

## Targets
- Fabric and Quilt
- Minecraft versions: 1.21.1, 1.20.1, 1.19.2, 1.18.2, 1.17.1, 1.16.5

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
- Manual dispatch supports release tag input.
- Builds every MC + loader target in `config/release-matrix.json`.
- Uploads all jars to a GitHub Release.

## Notes
- Source currently uses modern client skin APIs from the existing bridge implementation.
- If specific older targets fail due upstream mapping/runtime API differences, pin/add compatibility source in future commits.
