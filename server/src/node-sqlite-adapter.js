/**
 * Adapter that presents Node's built-in `node:sqlite` (Node 22+) with the small
 * slice of the better-sqlite3 API that SessionStore uses:
 * `prepare(sql).run/get/all`, `exec`, `pragma`, `close`.
 *
 * Why this exists: better-sqlite3 is a native module and building it requires
 * downloading Node headers from nodejs.org, which is not reachable from every
 * environment. Rather than silently downgrading to the JSONL store (and losing
 * indexed history), we prefer the built-in SQLite and only fall back to JSONL
 * when neither is available.
 */

export async function loadNodeSqlite() {
  const mod = await import('node:sqlite');
  const DatabaseSync = mod.DatabaseSync || mod.default?.DatabaseSync;
  if (!DatabaseSync) throw new Error('node:sqlite tidak menyediakan DatabaseSync');

  return function open(file) {
    const db = new DatabaseSync(file);
    return {
      driver: 'node:sqlite',
      exec: (sql) => db.exec(sql),
      pragma: () => undefined,
      close: () => db.close(),
      prepare(sql) {
        const statement = db.prepare(sql);
        // better-sqlite3 accepts either positional args or a single object of
        // named params. node:sqlite requires named params as an object and
        // positional params as a spread — passing an array is read as named
        // parameters and fails with "Unknown named parameter '0'".
        const bind = (args) => (args.length === 1 && isPlainObject(args[0]) ? [args[0]] : args);
        return {
          run(...args) {
            const info = statement.run(...bind(args));
            return { changes: Number(info.changes), lastInsertRowid: Number(info.lastInsertRowid) };
          },
          get(...args) {
            const row = statement.get(...bind(args));
            return row === undefined ? undefined : toPlainObject(row);
          },
          all(...args) {
            return statement.all(...bind(args)).map(toPlainObject);
          },
        };
      },
    };
  };
}

function isPlainObject(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** node:sqlite returns null-prototype rows; normalise so callers can spread them. */
function toPlainObject(row) {
  return { ...row };
}
