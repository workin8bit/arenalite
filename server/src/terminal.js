import { spawn } from 'child_process';

/**
 * Terminal sessions over WebSocket.
 *
 * Uses node-pty when available (real PTY: vim, top, colors all work) and falls
 * back to a piped bash process so the feature still ships on platforms where
 * the native module cannot be built.
 */

let pty = null;
try {
  // optional dependency
  // eslint-disable-next-line import/no-unresolved
  pty = (await import('node-pty')).default || (await import('node-pty'));
} catch {
  pty = null;
}

export function createTerminalServer({ wss, engine }) {
  const sessions = new Map();

  wss.on('connection', (ws, req) => {
    const url = new URL(req.url, 'http://localhost');
    const sessionId = url.searchParams.get('sessionId');
    const command = url.searchParams.get('shell') || '/bin/bash';
    if (!sessionId) {
      ws.close(4400, 'sessionId wajib diisi');
      return;
    }

    let cwd;
    try {
      cwd = engine.rootFor(sessionId);
    } catch {
      ws.close(4404, 'workspace tidak ditemukan');
      return;
    }

    const term = startTerminal({ cwd, command, sessionId });
    sessions.set(ws, term);

    term.onData((data) => {
      if (ws.readyState === ws.OPEN) ws.send(JSON.stringify({ type: 'output', data }));
    });
    term.onExit(({ exitCode }) => {
      if (ws.readyState === ws.OPEN) {
        ws.send(JSON.stringify({ type: 'exit', exitCode }));
        ws.close();
      }
    });

    ws.on('message', (raw) => {
      let msg;
      try {
        msg = JSON.parse(raw.toString());
      } catch {
        return;
      }
      if (msg.type === 'input') term.write(msg.data);
      if (msg.type === 'resize' && term.resize) term.resize(msg.cols, msg.rows);
    });

    ws.on('close', () => {
      sessions.get(ws)?.kill();
      sessions.delete(ws);
    });

    ws.send(JSON.stringify({ type: 'ready', driver: term.driver, cwd }));
  });

  return {
    count: () => sessions.size,
    closeAll: () => [...sessions.keys()].forEach((ws) => ws.close()),
  };
}

function startTerminal({ cwd, command, sessionId }) {
  if (pty?.spawn) {
    const proc = pty.spawn(command, ['-i'], {
      name: 'xterm-256color',
      cols: 100,
      rows: 30,
      cwd,
      env: { ...process.env, ARENALITE_SESSION: sessionId, TERM: 'xterm-256color' },
    });
    return {
      driver: 'pty',
      onData: (cb) => proc.onData(cb),
      onExit: (cb) => proc.onExit(cb),
      write: (data) => proc.write(data),
      resize: (cols, rows) => proc.resize(Math.max(2, cols | 0), Math.max(2, rows | 0)),
      kill: () => proc.kill(),
    };
  }

  const child = spawn(command, ['-i'], {
    cwd,
    env: { ...process.env, ARENALITE_SESSION: sessionId, PS1: 'arealite$ ', TERM: 'dumb' },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  const dataHandlers = [];
  const exitHandlers = [];
  child.stdout.on('data', (d) => dataHandlers.forEach((cb) => cb(d.toString())));
  child.stderr.on('data', (d) => dataHandlers.forEach((cb) => cb(d.toString())));
  child.on('close', (code) => exitHandlers.forEach((cb) => cb({ exitCode: code ?? 0 })));
  return {
    driver: 'pipe',
    onData: (cb) => dataHandlers.push(cb),
    onExit: (cb) => exitHandlers.push(cb),
    write: (data) => child.stdin.write(data.endsWith('\n') ? data : `${data}\n`),
    resize: null,
    kill: () => child.kill('SIGKILL'),
  };
}

export function terminalDriverName() {
  return pty?.spawn ? 'pty' : 'pipe';
}
