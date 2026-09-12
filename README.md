# NTV-Cloudstream

CloudStream3 plugin for NTV live content (24/7 channels + events).

## Extensions

| Extension | Package | Source |
|-----------|---------|--------|
| **NTV Live** | `com.ntv` | [ntv.st](https://ntv.st) |

## Structure

```
extensions/
└── ntvlive/                    # NTV Live scraper (ntv.st)
    ├── build.gradle.kts
    ├── src/main/
    │   ├── AndroidManifest.xml
    │   └── kotlin/com/ntv/
    │       ├── NtvLivePlugin.kt
    │       ├── NtvLive.kt
    │       └── NtvEmbedExtractor.kt
```

## Data sources

- **Events**: `GET https://ntv.st/api/get-matches?server=<srv>&type=both` → `{live, all}` with `sources[]`
- **24/7 channels**: `GET https://ntv.st/api/get-channels?limit=100&offset=N` (dlhd entries only)
- **zlive**: `cast.zlive.st/*.json` → `iptv.zlive.st/<slug>` → 302 signed m3u8
- **GOAT slots** (kobra/viper/dlhd): `embed.st/embed/<src>/<id>/<n>` → WebView extractor

## Build

```bash
./gradlew make makePluginsJson
```

## Setup

- `.cs3` artifacts are generated per extension in `extensions/*/build/`
- `plugins.json` and `repo.json` are updated by CI on the `builds` branch
- Import the repo URL in the Cloudstream3 app to install the extension

## License

For personal use only. Respects the source websites' terms of service.
