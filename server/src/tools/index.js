import { spawn } from 'child_process';
import { MAX_EXEC_OUTPUT } from '../workspace-engine.js';

export const DEFAULT_TIMEOUT_MS = 20000;

/**
 * The tool surface handed to the model. Every tool is a thin, auditable wrapper
 * over WorkspaceEngine so that the oplog stays the single source of truth.
 */
export function buildTools(engine, { sessionId, execRoot, net, log = () => {} }) {
  const run = (name, fn) => async (args) => {
    try {
      const result = await fn(args || {});
      log({ type: 'tool.result', tool: name, ok: true, preview: previewOf(result) });
      return { ok: true, result };
    } catch (err) {
      log({ type: 'tool.result', tool: name, ok: false, error: err.message, code: err.code });
      return { ok: false, error: err.message, code: err.code };
    }
  };

  return {
    list_files: run('list_files', async ({ path = '.' }) => ({
      path,
      entries: await engine.listFiles(sessionId, path),
    })),

    read_file: run('read_file', async ({ path }) => {
      if (!path) throw new Error('path wajib diisi');
      const file = await engine.readFile(sessionId, path);
      return {
        path: file.path,
        size: file.size,
        truncated: file.truncated,
        content: file.text ?? `[biner ${file.size} byte]`,
      };
    }),

    write_file: run('write_file', async ({ path, content }) => {
      if (!path) throw new Error('path wajib diisi');
      if (typeof content !== 'string') throw new Error('content harus string');
      return engine.writeFile(sessionId, path, content, { source: 'agent' });
    }),

    edit_file: run('edit_file', async ({ path, old_string, new_string }) => {
      if (!path) throw new Error('path wajib diisi');
      if (typeof old_string !== 'string' || typeof new_string !== 'string') {
        throw new Error('old_string dan new_string wajib string');
      }
      const file = await engine.readFile(sessionId, path);
      if (file.text === null) throw new Error('edit_file hanya untuk file teks');
      const count = countOccurrences(file.text, old_string);
      if (count === 0) throw new Error(`old_string tidak ditemukan di ${path}`);
      if (count > 1) throw new Error(`old_string muncul ${count}x di ${path}; buat lebih spesifik`);
      const next = file.text.replace(old_string, new_string);
      const written = await engine.writeFile(sessionId, path, next, { source: 'agent' });
      return { ...written, replaced: 1 };
    }),

    delete_file: run('delete_file', async ({ path }) => {
      if (!path) throw new Error('path wajib diisi');
      return engine.deletePath(sessionId, path, { source: 'agent' });
    }),

    make_dir: run('make_dir', async ({ path }) => {
      if (!path) throw new Error('path wajib diisi');
      return engine.mkdir(sessionId, path, { source: 'agent' });
    }),

    exec: run('exec', async ({ command, cwd = '.', timeout_ms = DEFAULT_TIMEOUT_MS }) => {
      if (!command) throw new Error('command wajib diisi');
      return execInWorkspace({ engine, sessionId, command, cwd, execRoot, timeout_ms });
    }),

    fetch_url: run('fetch_url', async ({ url, method = 'GET' }) => {
      if (!url) throw new Error('url wajib diisi');
      if (!net) return { blocked: true, reason: 'Akses jaringan dimatikan untuk workspace ini' };
      return net.fetch(url, method);
    }),

    workspace_usage: run('workspace_usage', async () => engine.usage(sessionId)),
  };
}

export function countOccurrences(haystack, needle) {
  if (needle === '') return 0;
  let n = 0;
  let i = haystack.indexOf(needle);
  while (i !== -1) {
    n += 1;
    i = haystack.indexOf(needle, i + needle.length);
  }
  return n;
}

export function previewOf(result) {
  const s = typeof result === 'string' ? result : JSON.stringify(result);
  return s.length > 240 ? s.slice(0, 240) + '…' : s;
}

/**
 * Commands run with the workspace as their working directory and PATH limited
 * to the usual toolchain. Not a security boundary (that would need a container);
 * it keeps the agent from wandering into unrelated directories by accident.
 */
export function execInWorkspace({ engine, sessionId, command, cwd, execRoot, timeout_ms }) {
  return new Promise((resolve, reject) => {
    let workdir;
    try {
      workdir = engine.resolve(sessionId, cwd);
    } catch (err) {
      reject(err);
      return;
    }
    const child = spawn('/bin/bash', ['-lc', command], {
      cwd: workdir,
      env: {
        ...process.env,
        ARENALITE_SESSION: sessionId,
        ARENALITE_ROOT: engine.rootFor(sessionId),
        ARENALITE_EXEC_ROOT: String(execRoot || ''),
        HOME: workdir,
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    });

    let out = '';
    let errOut = '';
    let killed = false;
    const cap = (chunk, isErr) => {
      const text = chunk.toString();
      if (isErr) errOut = (errOut + text).slice(-MAX_EXEC_OUTPUT);
      else out = (out + text).slice(-MAX_EXEC_OUTPUT);
    };
    child.stdout.on('data', (c) => cap(c, false));
    child.stderr.on('data', (c) => cap(c, true));

    const timer = setTimeout(() => {
      killed = true;
      child.kill('SIGKILL');
    }, Math.min(timeout_ms, 120000));

    child.on('error', (err) => {
      clearTimeout(timer);
      reject(err);
    });

    child.on('close', (code) => {
      clearTimeout(timer);
      const payload = {
        command,
        cwd,
        exitCode: code,
        timedOut: killed,
        stdout: out,
        stderr: errOut,
      };
      engine
        .appendOp(sessionId, {
          type: 'exec',
          path: null,
          source: 'agent',
          detail: { command, cwd, exitCode: code, timedOut: killed, stdoutBytes: out.length },
        })
        .then(() => resolve(payload))
        .catch(() => resolve(payload));
    });
  });
}

export const TOOL_SCHEMAS = [
  {
    name: 'list_files',
    description: 'List isi sebuah direktori di workspace.',
    input_schema: {
      type: 'object',
      properties: { path: { type: 'string', description: 'Path relatif, default "."' } },
    },
  },
  {
    name: 'read_file',
    description: 'Baca isi file teks (maks 1 MB).',
    input_schema: { type: 'object', properties: { path: { type: 'string' } }, required: ['path'] },
  },
  {
    name: 'write_file',
    description: 'Tulis/timpa file. Direktori dibuat otomatis. Tidak ada batas kuota.',
    input_schema: {
      type: 'object',
      properties: { path: { type: 'string' }, content: { type: 'string' } },
      required: ['path', 'content'],
    },
  },
  {
    name: 'edit_file',
    description: 'Ganti satu kemunculan old_string dengan new_string. Gagal bila tidak unik.',
    input_schema: {
      type: 'object',
      properties: { path: { type: 'string' }, old_string: { type: 'string' }, new_string: { type: 'string' } },
      required: ['path', 'old_string', 'new_string'],
    },
  },
  {
    name: 'delete_file',
    description: 'Hapus file atau direktori.',
    input_schema: { type: 'object', properties: { path: { type: 'string' } }, required: ['path'] },
  },
  {
    name: 'make_dir',
    description: 'Buat direktori (rekursif).',
    input_schema: { type: 'object', properties: { path: { type: 'string' } }, required: ['path'] },
  },
  {
    name: 'exec',
    description: 'Jalankan perintah shell di dalam workspace. Output dipotong ke 64 KB.',
    input_schema: {
      type: 'object',
      properties: {
        command: { type: 'string' },
        cwd: { type: 'string' },
        timeout_ms: { type: 'number' },
      },
      required: ['command'],
    },
  },
  {
    name: 'fetch_url',
    description: 'Ambil konten sebuah URL (GET) bila jaringan diizinkan.',
    input_schema: {
      type: 'object',
      properties: { url: { type: 'string' }, method: { type: 'string' } },
      required: ['url'],
    },
  },
  {
    name: 'workspace_usage',
    description: 'Laporkan pemakaian workspace: jumlah file, total byte, dan batas (∞).',
    input_schema: { type: 'object', properties: {} },
  },
];

