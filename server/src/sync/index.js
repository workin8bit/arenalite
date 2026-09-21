import fs from 'fs';
import fsp from 'fs/promises';
import path from 'path';
import crypto from 'crypto';
import { sigV4Headers } from './sigv4.js';

/**
 * Unlimited cloud sync.
 *
 * A workspace is synced by manifest diff: hash every file locally, hash the
 * remote copy, move only what changed. There is no per-workspace or per-account
 * size cap in this code path — the only practical ceiling is the bucket you
 * point it at.
 *
 * Two backends ship:
 *   - "local": a directory that stands in for cloud storage (default, so sync is
 *     testable and demoable with zero credentials)
 *   - "s3":    any S3-compatible endpoint (AWS, Cloudflare R2, MinIO, Wasabi…)
 */

export function createRemoteBackend(config = {}) {
  if (!config.backend || config.backend === 'local') return createLocalBackend(config);
  if (config.backend === 's3') return createS3Backend(config);
  throw new Error(`Backend sync tidak dikenal: ${config.backend}`);
}

function createLocalBackend({ root }) {
  if (!root) throw new Error('Backend local butuh `root`');
  const base = path.resolve(root);
  fs.mkdirSync(base, { recursive: true });
  return {
    id: 'local',
    async ensure(remoteId) {
      await fsp.mkdir(path.join(base, remoteId), { recursive: true });
    },
    async put(remoteId, key, buf) {
      const target = path.join(base, remoteId, key);
      await fsp.mkdir(path.dirname(target), { recursive: true });
      await fsp.writeFile(target, buf);
    },
    async get(remoteId, key) {
      const target = path.join(base, remoteId, key);
      if (!fs.existsSync(target)) return null;
      return fsp.readFile(target);
    },
    async del(remoteId, key) {
      await fsp.rm(path.join(base, remoteId, key), { force: true });
    },
    describe() {
      return { backend: 'local', root: base };
    },
  };
}

function createS3Backend({ endpoint, region = 'auto', bucket, accessKeyId, secretAccessKey }) {
  if (!bucket || !accessKeyId || !secretAccessKey) {
    throw new Error('Backend s3 butuh bucket, accessKeyId, secretAccessKey');
  }
  const base = (endpoint || `https://s3.${region}.amazonaws.com`).replace(/\/$/, '');
  const urlFor = (remoteId, key) => `${base}/${bucket}/${remoteId}/${key.split('/').map(encodeURIComponent).join('/')}`;

  return {
    id: 's3',
    async ensure() {
      /* buckets are pre-created; nothing to do */
    },
    async put(remoteId, key, buf) {
      const res = await fetch(urlFor(remoteId, key), {
        method: 'PUT',
        headers: sigV4Headers({
          method: 'PUT',
          url: urlFor(remoteId, key),
          region,
          accessKeyId,
          secretAccessKey,
          service: 's3',
          body: buf,
        }),
        body: buf,
      });
      if (!res.ok) throw new Error(`S3 PUT ${res.status}: ${(await res.text()).slice(0, 200)}`);
    },
    async get(remoteId, key) {
      const res = await fetch(urlFor(remoteId, key), {
        headers: sigV4Headers({
          method: 'GET',
          url: urlFor(remoteId, key),
          region,
          accessKeyId,
          secretAccessKey,
          service: 's3',
        }),
      });
      if (res.status === 404) return null;
      if (!res.ok) throw new Error(`S3 GET ${res.status}: ${(await res.text()).slice(0, 200)}`);
      return Buffer.from(await res.arrayBuffer());
    },
    async del(remoteId, key) {
      await fetch(urlFor(remoteId, key), {
        method: 'DELETE',
        headers: sigV4Headers({
          method: 'DELETE',
          url: urlFor(remoteId, key),
          region,
          accessKeyId,
          secretAccessKey,
          service: 's3',
        }),
      });
    },
    describe() {
      return { backend: 's3', endpoint: base, bucket, region };
    },
  };
}

const MANIFEST_KEY = 'arealite/manifest.json';

export class SyncEngine {
  constructor({ engine, store, backend }) {
    this.engine = engine;
    this.store = store;
    this.backend = backend;
  }

  remoteIdFor(sessionId) {
    const state = this.store.getSyncState(sessionId);
    return state.remoteId || `${sessionId}`;
  }

  /** Upload only what changed since the last successful push. */
  async push(sessionId, { onProgress = () => {} } = {}) {
    const remoteId = this.remoteIdFor(sessionId);
    await this.backend.ensure(remoteId);

    const local = await this.engine.manifest(sessionId);
    const remoteBuf = await this.backend.get(remoteId, MANIFEST_KEY);
    const remote = remoteBuf ? JSON.parse(remoteBuf.toString('utf8')) : { files: [] };
    const remoteByPath = new Map(remote.files.map((f) => [f.path, f]));

    const toUpload = local.files.filter((f) => !remoteByPath.has(f.path) || remoteByPath.get(f.path).hash !== f.hash);
    const toDelete = remote.files.filter((f) => !local.files.some((l) => l.path === f.path));

    let uploadedBytes = 0;
    for (const file of toUpload) {
      const abs = this.engine.resolve(sessionId, file.path);
      const buf = await fsp.readFile(abs);
      await this.backend.put(remoteId, `files/${file.path}`, buf);
      uploadedBytes += buf.length;
      onProgress({ phase: 'upload', path: file.path, bytes: buf.length });
    }
    for (const file of toDelete) {
      await this.backend.del(remoteId, `files/${file.path}`);
      onProgress({ phase: 'delete', path: file.path });
    }

    const ops = await this.engine.opsSince(sessionId, 0);
    await this.backend.put(remoteId, MANIFEST_KEY, Buffer.from(JSON.stringify({ ...local, cursor: ops.cursor }, null, 2)));

    const { cursor: lastCursor } = await this.engine.opsSince(sessionId, 0);
    await this.engine.appendOp(sessionId, {
      type: 'sync.push',
      path: null,
      source: 'sync',
      detail: { uploaded: toUpload.length, deleted: toDelete.length, uploadedBytes, remoteId },
    });

    return this.store.setSyncState(sessionId, {
      remoteId,
      backend: this.backend.id,
      cursor: lastCursor,
      lastPushedAt: new Date().toISOString(),
      pushedFiles: toUpload.length,
    });
  }

  /**
   * Pull remote changes down. Files changed on both sides are reported as
   * conflicts and left to the caller's strategy (default: remote wins, and the
   * local version is preserved as `<path>.local-<ts>`).
   */
  async pull(sessionId, { strategy = 'remote-wins' } = {}) {
    const remoteId = this.remoteIdFor(sessionId);
    const remoteBuf = await this.backend.get(remoteId, MANIFEST_KEY);
    if (!remoteBuf) return { state: this.store.getSyncState(sessionId), downloaded: 0, conflicts: [] };
    const remote = JSON.parse(remoteBuf.toString('utf8'));

    const local = await this.engine.manifest(sessionId);
    const localByPath = new Map(local.files.map((f) => [f.path, f]));
    const conflicts = [];
    let downloaded = 0;

    for (const file of remote.files) {
      const localFile = localByPath.get(file.path);
      if (localFile && localFile.hash === file.hash) continue;
      if (localFile && strategy === 'remote-wins') {
        const backup = `${file.path}.local-${Date.now()}`;
        const abs = this.engine.resolve(sessionId, file.path);
        await this.engine.writeFile(sessionId, backup, await fsp.readFile(abs), { source: 'sync' });
        conflicts.push({ path: file.path, backup });
      }
      const buf = await this.backend.get(remoteId, `files/${file.path}`);
      if (!buf) continue;
      await this.engine.writeFile(sessionId, file.path, buf, { source: 'sync' });
      downloaded += 1;
    }

    for (const file of local.files) {
      if (!remote.files.some((r) => r.path === file.path)) {
        await this.engine.deletePath(sessionId, file.path, { source: 'sync' });
      }
    }

    await this.engine.appendOp(sessionId, {
      type: 'sync.pull',
      path: null,
      source: 'sync',
      detail: { downloaded, conflicts: conflicts.length, remoteId },
    });

    const state = this.store.setSyncState(sessionId, {
      remoteId,
      backend: this.backend.id,
      cursor: remote.cursor || 0,
      lastPulledAt: new Date().toISOString(),
      pulledFiles: downloaded,
    });
    return { state, downloaded, conflicts };
  }

  async status(sessionId) {
    const local = await this.engine.manifest(sessionId);
    const remoteId = this.remoteIdFor(sessionId);
    let remote = null;
    try {
      const buf = await this.backend.get(remoteId, MANIFEST_KEY);
      remote = buf ? JSON.parse(buf.toString('utf8')) : null;
    } catch (err) {
      return { state: this.store.getSyncState(sessionId), local, remote: null, error: err.message };
    }
    const remoteByPath = new Map((remote?.files || []).map((f) => [f.path, f]));
    const pendingUpload = local.files.filter((f) => !remoteByPath.has(f.path) || remoteByPath.get(f.path).hash !== f.hash);
    return {
      state: this.store.getSyncState(sessionId),
      backend: this.backend.describe(),
      localFiles: local.files.length,
      remoteFiles: remote?.files.length || 0,
      pendingUpload: pendingUpload.length,
      cursor: remote?.cursor || 0,
    };
  }
}

export function hashBuffer(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex');
}
