import { useEffect, useRef } from 'react';
import { normaliseMessage } from '../util/format.js';
import ToolCard from './ToolCard.jsx';

/**
 * Pairs tool_use blocks (assistant message) with their tool_result blocks
 * (following user message) so each tool renders as a single card.
 */
export function buildItems(messages) {
  const items = [];
  const results = new Map();
  for (const message of messages) {
    for (const block of message.content || []) {
      if (Array.isArray(message.content) && block?.type === 'tool_result') {
        results.set(block.tool_use_id, block);
      }
    }
  }

  for (const message of messages) {
    const parsed = normaliseMessage(message);
    if (message.role === 'user' && parsed.toolResults.length && !parsed.text) continue; // tool result carrier
    if (parsed.text) items.push({ kind: 'text', role: message.role, text: parsed.text });
    for (const call of parsed.toolUses) {
      const result = results.get(call.id);
      items.push({
        kind: 'tool',
        call,
        result: result ? { ok: !result.is_error, result: result.content, error: result.is_error ? result.content : undefined } : null,
      });
    }
  }
  return items;
}

function Message({ role, children }) {
  return (
    <div className={`message ${role}`}>
      <div className="avatar">{role === 'assistant' ? 'A' : 'U'}</div>
      <div className="message-body">{children}</div>
    </div>
  );
}

export default function Chat({ messages, streaming, running, session }) {
  const ref = useRef(null);
  const items = buildItems(messages);

  useEffect(() => {
    const node = ref.current;
    if (!node) return;
    node.scrollTop = node.scrollHeight;
  }, [items.length, streaming?.text]);

  if (!session) return null;

  const hasContent = items.length > 0 || streaming;

  return (
    <div className="chat" ref={ref}>
      <div className="chat-inner">
        {!hasContent && (
          <div className="empty-state">
            <h3>Workspace ini belum punya riwayat</h3>
            <p>
              Minta agent membuat file, menjalankan perintah, atau menyusun proyek.
              <br />
              Tidak ada batas kuota — file sebesar apa pun boleh disimpan di sini.
            </p>
          </div>
        )}

        {renderGrouped(items)}

        {streaming && (
          <Message role="assistant">
            {streaming.text && <div className="message-text">{streaming.text}</div>}
            {streaming.tools.map((tool) => (
              <ToolCard key={tool.call.id} call={tool.call} result={tool.result} defaultOpen />
            ))}
            {running && !streaming.text && streaming.tools.length === 0 && (
              <div className="message-text" style={{ color: 'var(--text-faint)' }}>
                sedang berpikir…
              </div>
            )}
          </Message>
        )}
      </div>
    </div>
  );
}

/** Consecutive text items from the same role collapse into one bubble. */
function renderGrouped(items) {
  const out = [];
  let buffer = null;
  const flush = () => {
    if (buffer) {
      out.push(
        <Message key={`m${out.length}`} role={buffer.role}>
          <div className="message-text">{buffer.text}</div>
        </Message>,
      );
      buffer = null;
    }
  };

  for (const item of items) {
    if (item.kind === 'text') {
      if (buffer && buffer.role === item.role) buffer.text += `\n${item.text}`;
      else {
        flush();
        buffer = { role: item.role, text: item.text };
      }
    } else {
      flush();
      out.push(
        <Message key={`t${out.length}`} role="assistant">
          <ToolCard call={item.call} result={item.result} />
        </Message>,
      );
    }
  }
  flush();
  return out;
}
