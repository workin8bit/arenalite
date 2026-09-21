/**
 * Mock provider.
 *
 * Deterministic, offline, and deliberately tool-hungry: it exercises the exact
 * same code path as a real provider (streamed text deltas, tool_use blocks,
 * tool_result handling, final stop) so the whole agent loop is testable without
 * any network access or API key.
 */

export function delay(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

export function detectIntent(userText) {
  const t = (userText || '').toLowerCase();
  if (/fibonacci/.test(t)) return 'fibonacci';
  if (/hapus|delete|remove/.test(t)) return 'delete';
  if (/daftar file|list|ls\b|isi workspace|isi folder/.test(t)) return 'list';
  if (/usage|kuota|penyimpanan|storage|berapa.*byte/.test(t)) return 'usage';
  if (/jalankan|run|eksekusi|exec/.test(t)) return 'exec';
  if (/file|buat|write|script|kode|code/.test(t)) return 'write';
  return 'chat';
}

export function planFor(intent, userText) {
  switch (intent) {
    case 'fibonacci':
      return {
        preamble: 'Saya buatkan script fibonacci di workspace, lalu langsung saya jalankan.',
        calls: [
          {
            name: 'write_file',
            input: {
              path: 'fib.py',
              content: [
                'import sys',
                '',
                'def fib(n):',
                '    a, b = 0, 1',
                '    out = []',
                '    for _ in range(n):',
                '        out.append(a)',
                '        a, b = b, a + b',
                '    return out',
                '',
                'if __name__ == "__main__":',
                '    n = int(sys.argv[1]) if len(sys.argv) > 1 else 10',
                '    print(", ".join(map(str, fib(n))))',
                '',
              ].join('\n'),
            },
          },
          { name: 'exec', input: { command: 'python3 fib.py 15' } },
        ],
        closing: (results) =>
          `Selesai. \`fib.py\` tersimpan di workspace dan outputnya: \`${lastOutput(results)}\`.`,
      };
    case 'write': {
      const name = /(\S+\.(py|js|ts|json|md|txt|sh|html|css))/.exec(userText || '')?.[1] || 'catatan.md';
      return {
        preamble: `Saya tulis \`${name}\` ke workspace.`,
        calls: [
          {
            name: 'write_file',
            input: {
              path: name,
              content: `# ${name}\n\nDibuat oleh agent Arealite.\nPermintaan: ${userText}\n`,
            },
          },
        ],
        closing: () => `\`${name}\` sudah tersimpan. Tidak ada batas kuota, jadi lanjutkan saja.`,
      };
    }
    case 'list':
      return {
        preamble: 'Saya cek isi workspace.',
        calls: [{ name: 'list_files', input: { path: '.' } }],
        closing: (results) => `Isi workspace saat ini:\n${formatEntries(results)}`,
      };
    case 'usage':
      return {
        preamble: 'Saya hitung pemakaian workspace.',
        calls: [{ name: 'workspace_usage', input: {} }],
        closing: (results) => `Pemakaian: ${formatUsage(results)}. Batas kuota: ∞ (unlimited).`,
      };
    case 'delete': {
      const name = /(\S+\.\w+)/.exec(userText || '')?.[1];
      return {
        preamble: name ? `Saya hapus \`${name}\`.` : 'Sebutkan nama file yang mau dihapus.',
        calls: name ? [{ name: 'delete_file', input: { path: name } }] : [],
        closing: () => (name ? `\`${name}\` dihapus dari workspace.` : 'Tidak ada yang dihapus.'),
      };
    }
    case 'exec': {
      const cmd = /`([^`]+)`/.exec(userText || '')?.[1] || 'echo halo dari workspace && pwd';
      return {
        preamble: 'Menjalankan perintah di workspace.',
        calls: [{ name: 'exec', input: { command: cmd } }],
        closing: (results) => `Output:\n\`\`\`\n${lastOutput(results)}\n\`\`\``,
      };
    }
    default:
      return {
        preamble: '',
        calls: [],
        closing: () =>
          'Saya agent Arealite. Workspace ini unlimited — minta saya menulis file, menjalankan perintah, atau menyusun proyek apa pun.',
      };
  }
}

export function lastOutput(results) {
  for (let i = results.length - 1; i >= 0; i -= 1) {
    const r = results[i];
    if (!r?.ok) continue;
    if (typeof r.result?.stdout === 'string' && r.result.stdout) return r.result.stdout.trim();
    if (typeof r.result?.content === 'string') return r.result.content.trim();
  }
  return '(tidak ada output)';
}

export function formatEntries(results) {
  const last = [...results].reverse().find((r) => r?.result?.entries);
  if (!last) return '(kosong)';
  return last.result.entries
    .map((e) => `- ${e.type === 'dir' ? '📁' : '📄'} \`${e.name}\` (${e.size} byte)`)
    .join('\n');
}

export function formatUsage(results) {
  const last = [...results].reverse().find((r) => r?.result?.totalBytes !== undefined);
  if (!last) return '(tidak diketahui)';
  const u = last.result;
  return `${u.files} file, ${u.directories} direktori, ${u.totalBytes} byte`;
}

export function createMockProvider({ latencyMs = 30 } = {}) {
  return {
    id: 'mock',
    async *stream({ messages, tools, signal }) {
      // Intent must come from the last *human* turn. After a tool round the final
      // user message is a tool_result block, so scan back for real text.
      const lastUserText = [...messages]
        .reverse()
        .map((m) => (m.role === 'user' && typeof m.content === 'string' ? m.content : ''))
        .find((t) => t) || '';
      const hadToolResult = messages.some(
        (m) => m.role === 'user' && Array.isArray(m.content) && m.content.some((b) => b.type === 'tool_result'),
      );
      const toolNames = (tools || []).map((t) => t.name);
      const intent = detectIntent(lastUserText);
      const plan = planFor(intent, lastUserText);

      if (!hadToolResult && plan.calls.length) {
        if (plan.preamble) {
          for (const piece of chunkText(plan.preamble)) {
            await tick(latencyMs, signal);
            yield { type: 'text_delta', text: piece };
          }
          yield { type: 'text_stop' };
        }
        for (const call of plan.calls) {
          if (!toolNames.length || toolNames.includes(call.name)) {
            yield { type: 'tool_use', id: `toolu_mock_${Math.random().toString(36).slice(2, 8)}`, name: call.name, input: call.input };
          }
        }
        yield { type: 'stop', stopReason: 'tool_use' };
        return;
      }

      const collected = messages
        .filter((m) => m.role === 'user' && Array.isArray(m.content))
        .flatMap((m) => m.content.filter((b) => b.type === 'tool_result'));
      const closing = plan.closing(collected.map((b) => parseResult(b.content)));

      for (const piece of chunkText(closing)) {
        await tick(latencyMs, signal);
        yield { type: 'text_delta', text: piece };
      }
      yield { type: 'stop', stopReason: 'end_turn' };
    },
  };
}

export function parseResult(content) {
  if (typeof content === 'string') {
    try {
      return JSON.parse(content);
    } catch {
      return { text: content };
    }
  }
  if (Array.isArray(content)) {
    const text = content.find((b) => b.type === 'text')?.text || '';
    try {
      return JSON.parse(text);
    } catch {
      return { text };
    }
  }
  return content || {};
}

export function chunkText(text, size = 6) {
  const out = [];
  for (let i = 0; i < text.length; i += size) out.push(text.slice(i, i + size));
  return out;
}

export function tick(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(Object.assign(new Error('dibatalkan'), { name: 'AbortError' }));
      return;
    }
    const t = setTimeout(resolve, ms);
    signal?.addEventListener?.('abort', () => {
      clearTimeout(t);
      reject(Object.assign(new Error('dibatalkan'), { name: 'AbortError' }));
    });
  });
}

