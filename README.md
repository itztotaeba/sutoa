# Sutoa

Alt app store MVP berbasis Kotlin + Jetpack Compose dengan arsitektur **plugin-based source**:
data app diambil dari beberapa sumber (F-Droid API & GitHub Releases), digabung, di-dedup,
dan ditampilkan dalam satu UI terpadu. Download APK diverifikasi SHA-256 sebelum install
via `FileProvider` + `ACTION_VIEW`.

## Fitur

- **Home** — daftar aplikasi featured (fdroid index + catalog GitHub)
- **Search** — pencarian realtime (debounce 300ms) ke semua source, cache-first via Room
- **Detail** — info lengkap, screenshot carousel (HorizontalPager), tombol state-driven
  Install / Update / Open
- **Updates** — aplikasi terinstall yang punya versi baru di katalog
- **Download & Verify** — streaming OkHttp → SHA-256 check → package-name check → install
- **Update checker** — `WorkManager` periodic 12 jam (unmetered) + notifikasi
- **Offline cache** — hasil API disimpan di Room (fresh < 24 jam)

## Tech Stack

| Library | Versi |
|---|---|
| Kotlin | 1.9.22 |
| Compose BOM | 2024.09.00 (Material3) |
| Navigation Compose | 2.8.1 |
| Hilt | 2.51.1 |
| Retrofit + Moshi | 2.11.0 / 1.15.1 |
| OkHttp | 4.12.0 |
| Room | 2.6.1 |
| Coil | 2.7.0 |
| WorkManager | 2.9.1 |
| Coroutines | 1.8.1 |

minSdk 26 · targetSdk 35 · compileSdk 35 · Java 17 · `com.kinyo.sutoa`

## Arsitektur

```
ui/  ──(StateFlow)──  ViewModel  ──  AppRepository  ──  Set<AppSource> (plugin)
                                   │                    ├─ FdroidSource
                                   └─ Room cache         └─ GitHubSource
                                                          (+ source baru tanpa ubah UI)
ApkInstaller ── ApkDownloader ── verify(SHA-256, pkg) ── FileProvider + ACTION_VIEW
UpdateCheckWorker (12h, UNMETERED) ── compare longVersionCode vs cache ── notification
```

### Menambah source baru

1. Implementasikan interface `AppSource` (`id`, `search`, `featured`, `get`).
   Sumber wajib menangani error sendiri (return list kosong / null, jangan throw).
2. Daftarkan lewat `@Provides @IntoSet` di `di/AppModule.kt`:

```kotlin
@Provides @IntoSet
fun provideGitLabSource(api: GitLabApi): AppSource = GitLabSource(api)
```

Tidak ada perubahan pada UI, ViewModel, atau Repository. Prioritas dedup ditentukan
oleh `priority` pada `AppSource` (fdroid=0 > github=1).

## Build

```bash
./gradlew assembleDebug
```

Catalog GitHub yang di-track berada di `app/src/main/assets/github_catalog.json`
(field: `owner`, `repo`, `packageName`, `featured`). Tambahkan repo baru sesuai kebutuhan.

## Catatan keamanan

- APK yang hash-nya tidak cocok atau package-name-nya berbeda **ditolak & dihapus**.
- `usesCleartextTraffic=false`; hanya HTTPS.
- Install meminta permission *Install unknown apps* (`ACTION_MANAGE_UNKNOWN_APP_SOURCES`).
