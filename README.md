# Arealite

Aplikasi agent-workspace ala **arena.ai**, dengan satu perubahan inti:
**workspace-nya unlimited** — tidak ada kuota file, tidak ada batas jumlah
workspace/session, tidak ada riwayat yang dihapus otomatis.

Repo ini berisi tiga bagian yang saling melengkapi:

| Direktori | Isi | Cara verifikasi |
|---|---|---|
| `android/` | Aplikasi Android (Kotlin + Jetpack Compose + Room) | `tools/run-android-core-tests.sh` |
| `server/` | Server agent (Node.js) — workspace, tools, provider gateway, sync | `npm test` di `server/` |
| `web/` | Klien web (React + Vite) untuk mencoba produknya di browser | `npm run dev` di `web/` |

---

## Apa arti "workspace unlimited" di kode ini

Bukan sekadar teks di UI. Ada tiga keputusan desain yang bisa diaudit:

1. **Kuota diukur, tidak pernah ditegakkan.**
   `server/src/quota.js` dan `core/model/Models.kt` (`QuotaPolicy`) punya flag
   `enforce`. Untuk setiap paket yang dikirim, nilainya `false`. Angka kuota tetap
   dihitung supaya UI bisa menampilkan `1.4 GB / ∞`, tapi tidak ada satu pun jalur
   kode yang menolak penulisan karena ukuran.

2. **Tidak ada batas jumlah workspace/session.**
   `WorkspaceEngine.listSessions()` membaca direktori apa adanya. Test
   membuktikan 200 workspace bisa hidup bersamaan (server) dan 100 di Android.

3. **Riwayat chat tidak dibatasi.**
   Tabel `messages` tidak punya kolom kuota, tidak ada job pembersih, tidak ada
   TTL. Sinkronisasi memakai *manifest diff*, jadi workspace sebesar apa pun tetap
   terunggah — hanya file yang berubah yang dikirim.

---

## Mulai cepat

### 1. Server + web (bisa langsung dijalankan di sini)

```bash
cd server && npm install && npm start      # API di :3001
cd web    && npm install && npm run dev    # UI  di :5173 (proxy /api ke :3001)
```

Tanpa API key apa pun, server memakai **MockProvider** yang deterministik: ia
benar-benar memanggil tools (menulis file, menjalankan `python3`), jadi seluruh
alur agent — streaming, tool call, hasil tool, persistensi — bisa dicoba offline.

Untuk memakai model sungguhan, set salah satu env var ini sebelum `npm start`:

```bash
ANTHROPIC_API_KEY=...     # atau OPENAI_API_KEY / GEMINI_API_KEY / OPENROUTER_API_KEY
```

Model yang dilihat pengguna tetap katalog Arealite (`arena-agent-1`, dst);
`server/src/providers/models.js` yang memetakan ke upstream.

### 2. Android

```bash
cd android
./gradlew assembleDebug      # butuh JDK 17 + Android SDK (API 35)
```

Buka di Android Studio (Ladybug atau lebih baru) bila lebih suka GUI.

### 3. Uji logika inti Android **tanpa** Android SDK

```bash
TOOLCHAIN_ROOT=$HOME/.toolchain ./tools/run-android-core-tests.sh
```

Skrip ini mengompilasi `android/app/src/core` — source set yang **sama** dengan
yang dibundel ke APK (`sourceSets.main.java.srcDirs` di `app/build.gradle.kts`) —
lalu menjalankan 29 test: workspace engine, tool registry, agent loop, kuota,
sinkronisasi, dan codec JSON.

---

## Arsitektur

```
                ┌──────────────────────────────┐
   Android app  │  core/ (Kotlin murni)        │  ← sama-sama dikompilasi ke APK
   Compose UI ──┤  WorkspaceEngine             │    dan ke test JVM
   Room        │  ToolRegistry · AgentLoop    │
   OkHttp      │  SyncEngine · QuotaPolicy    │
                └───────────────┬──────────────┘
                                │  HTTP/SSE
                ┌───────────────▼──────────────┐
   Web (React)──┤  server/  (Node.js)          │
                │  workspace-engine · tools    │
                │  agent/loop · providers      │
                │  sync (S3/mirror) · sqlite   │
                └──────────────────────────────┘
```

Kedua sisi memakai **bentuk protokol yang sama**: blok `text` / `tool_use` /
`tool_result`, daftar tool yang identik, dan oplog ber-`seq` yang jadi dasar
sinkronisasi. Session yang dimulai di HP bisa dilanjutkan di web.

### Tools yang tersedia bagi agent

`list_files`, `read_file`, `write_file`, `edit_file`, `delete_file`, `make_dir`,
`exec`, `workspace_usage` (+ `fetch_url` di server bila jaringan diizinkan).

`edit_file` menolak `old_string` yang tidak unik — kesalahan yang paling sering
membuat agent merusak file.

### Keamanan

`resolve()` menolak path yang keluar dari workspace (`../../etc/passwd` →
`EPATHOUTSIDE`), bukan memotongnya diam-diam, supaya agent dapat error yang
jelas. Ini pagar kesalahan, bukan pagar keamanan: untuk multi-tenant, jalankan
tiap workspace di dalam container.

---

## API server

| Method | Path | Fungsi |
|---|---|---|
| GET | `/api/health` | versi, driver store, provider aktif, backend sync |
| GET/POST | `/api/sessions` | daftar / buat workspace |
| GET/PATCH/DELETE | `/api/sessions/:id` | detail / ubah / hapus |
| POST | `/api/sessions/:id/fork` | duplikat workspace |
| POST | `/api/sessions/:id/chat` | jalankan agent, **SSE** |
| POST | `/api/sessions/:id/cancel` | hentikan agent |
| GET/PUT/DELETE | `/api/sessions/:id/files/*` | jelajah / tulis / hapus file |
| GET | `/api/sessions/:id/file/*` | baca satu file (`?raw=1` untuk biner) |
| POST | `/api/sessions/:id/exec` | jalankan perintah shell |
| GET | `/api/sessions/:id/usage` | pemakaian + batas (∞) |
| GET | `/api/sessions/:id/ops?cursor=N` | delta oplog untuk sync/UI |
| POST | `/api/sessions/:id/snapshot` | salinan tree |
| GET/POST | `/api/sessions/:id/sync[/push\|/pull]` | status / push / pull |
| GET | `/api/sessions/:id/events` | SSE perubahan workspace |
| WS | `/api/terminal?sessionId=…` | terminal (PTY bila `node-pty` terpasang) |

### Sinkronisasi cloud

Default memakai direktori lokal sebagai pengganti cloud (bisa dites tanpa
kredensial). Untuk S3-compatible (AWS, R2, MinIO, Wasabi):

```bash
SYNC_BACKEND=s3 S3_BUCKET=my-bucket S3_REGION=auto \
S3_ENDPOINT=https://... S3_ACCESS_KEY_ID=... S3_SECRET_ACCESS_KEY=... npm start
```

Signing SigV4 diimplementasikan langsung di `server/src/sync/sigv4.js`
(tanpa SDK) dan ada test yang memverifikasi header yang dihasilkan.

---

## Status verifikasi

Dijalankan di sandbox ini:

- **`npm test` di `server/`** → 44/44 lulus (workspace, tools, agent loop,
  quota, session store, sync, dan API end-to-end lewat HTTP sungguhan).
- **`tools/run-android-core-tests.sh`** → 29/29 lulus (`CORE_OK`).
- **`npm run build` di `web/`** → build produksi sukses.
- **End-to-end lewat proxy Vite** → `POST /chat` mengirim 25 `text_delta`,
  2 tool call (`write_file`, `exec`), file `fib.py` benar-benar terbentuk.

Yang **tidak** bisa diverifikasi di sini: `./gradlew assembleDebug`, karena
sandbox tidak punya Android SDK dan `dl.google.com` / `repo1.maven.org`
diblokir. Kode Compose/Room ditulis lengkap tapi belum dikompilasi Gradle.
