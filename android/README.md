# Arealite — Android

Aplikasi Android untuk Arealite: agent yang bekerja di dalam workspace
perangkat, tanpa kuota.

## Struktur source set

```
app/src/core/kotlin    ← logika murni Kotlin (TANPA import android.*)
app/src/main/kotlin    ← lapisan Android: Compose, Room, OkHttp, WorkManager
app/src/test/kotlin    ← harness JVM untuk core
```

`app/src/core` **dimasukkan ke APK** lewat
`sourceSets.main.java.srcDirs("src/main/kotlin", "src/core/kotlin")`, dan juga
dikompilasi oleh `tools/run-android-core-tests.sh`. Jadi yang diuji di JVM adalah
kelas yang persis sama dengan yang jalan di HP — bukan tiruan.

Alasan pemisahan ini: logika workspace/agent/sync bisa diverifikasi tanpa
emulator dan tanpa Android SDK.

## Isi core

| Berkas | Tanggung jawab |
|---|---|
| `json/Json.kt` | parser + writer JSON tanpa dependensi (core tak boleh bergantung `org.json`/Gson) |
| `model/Models.kt` | `QuotaPolicy`, `WorkspaceSession`, `ChatMessage`, `ContentBlock`, manifest |
| `workspace/WorkspaceEngine.kt` | buat/baca/tulis/hapus file, oplog, usage, fork, snapshot, manifest |
| `agent/ToolRegistry.kt` | 8 tool + schema yang diiklankan ke model |
| `agent/AgentLoop.kt` | loop agent: stream → tool call → tool result → ulangi |
| `provider/` | Anthropic, OpenAI-compatible, dan MockProvider (offline) |
| `sync/SyncEngine.kt` | sinkronisasi berbasis manifest-diff |
| `util/FileSystem.kt` | abstraksi FS + helper (`sha256Hex`, `normalizeRelative`, formatter) |

## Lapisan Android

| Berkas | Tanggung jawab |
|---|---|
| `ArealiteApplication.kt` | composition root + jadwal sync 6 jam |
| `data/AndroidFileSystem.kt` | `FileSystem` di atas `filesDir/workspaces` |
| `data/OkHttpArealiteClient.kt` | `HttpClient` OkHttp, read-timeout dimatikan untuk SSE |
| `data/AppDatabase.kt` | Room: `sessions`, `messages`, `sync_state` |
| `data/SettingsStore.kt` | DataStore: gateway, model, remote root |
| `data/WorkspaceRepository.kt` | jembatan core ↔ Android |
| `agent/AndroidExecutor.kt` | `exec` di Android: interpreter perintah terbatas (jujur soal batasannya) |
| `ui/` | Compose: drawer workspace, chat + tool card, browser file, panel pemakaian |

## Build

```bash
cd android
./gradlew assembleDebug
```

Butuh JDK 17 dan Android SDK dengan API 35. Versi plugin dikunci di
`build.gradle.kts`: AGP 8.5.2, Kotlin 2.0.21, KSP 2.0.21-1.0.25.

## Catatan soal `exec` di Android

Android tidak punya `fork()`, jadi tidak ada shell sungguhan untuk agent.
`AndroidExecutor` menginterpretasikan subset perintah (`echo`, `ls`, `cat`,
`mkdir`, `rm`, `head`, `tail`, `wc`, `grep`, `pwd`) dan menolak pipeline/subshell
dengan pesan jelas, bukan berpura-pura berhasil. Untuk menjalankan script
sungguhan, workspace yang sama bisa dibuka di web/server yang punya shell penuh.

## Provider

Sesuai gaya arena.ai, pengguna tidak mengisi API key. Aplikasi menunjuk ke
gateway operator (`SettingsStore.gatewayUrl()`); gateway itu yang memegang key
upstream. Bila kosong, aplikasi memakai `MockProvider` sehingga tetap bisa
didemokan offline.
