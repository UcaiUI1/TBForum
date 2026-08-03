// @提及输入框：输入 @ 触发用户搜索下拉，选中后插入用户名
import React, { useEffect, useRef, useState } from 'react';
import type { UserView } from '../types';
import { userApi } from '../services/api';

type Props = {
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  rows?: number;
  token: string;
  onError: (e: unknown) => void;
  disabled?: boolean;
};

export function MentionTextarea({ value, onChange, placeholder, rows, token, onError, disabled }: Props) {
  const taRef = useRef<HTMLTextAreaElement>(null);
  const wrapRef = useRef<HTMLDivElement>(null);
  const [mention, setMention] = useState<{ open: boolean; q: string; results: UserView[]; pos: number }>({ open: false, q: '', results: [], pos: 0 });
  const searchTimer = useRef<number | undefined>(undefined);

  function handleInput(e: React.ChangeEvent<HTMLTextAreaElement>) {
    onChange(e.target.value);
    const ta = e.target; const pos = ta.selectionStart; const before = ta.value.substring(0, pos);
    const atIdx = before.lastIndexOf('@');
    if (atIdx === -1 || atIdx > 0 && !/\s/.test(ta.value[atIdx - 1])) { setMention(m => ({ ...m, open: false })); return; }
    const q = ta.value.substring(atIdx + 1, pos);
    if (/\s/.test(q) || q.length > 40) { setMention(m => ({ ...m, open: false })); return; }
    setMention({ open: q.length >= 1, q, results: [], pos });
    if (searchTimer.current) clearTimeout(searchTimer.current);
    if (q.length >= 1) {
      searchTimer.current = window.setTimeout(() => {
        userApi.search(q, token).then(r => setMention(m => ({ ...m, results: r, open: r.length > 0 }))).catch(e => console.warn('API:', e));
      }, 200);
    }
  }

  function insertMention(u: UserView) {
    const ta = taRef.current; if (!ta) return;
    const before = value.substring(0, mention.pos); const atIdx = before.lastIndexOf('@'); if (atIdx === -1) return;
    const after = value.substring(mention.pos);
    const newText = value.substring(0, atIdx + 1) + u.username + ' ' + after;
    onChange(newText);
    setMention(m => ({ ...m, open: false }));
    setTimeout(() => { const newPos = atIdx + 1 + u.username.length + 1; ta.focus(); ta.setSelectionRange(newPos, newPos); }, 0);
  }

  useEffect(() => {
    if (!mention.open) return;
    const h = (e: MouseEvent) => { if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setMention(m => ({ ...m, open: false })); };
    document.addEventListener('mousedown', h);
    return () => document.removeEventListener('mousedown', h);
  }, [mention.open]);

  useEffect(() => () => { if (searchTimer.current) clearTimeout(searchTimer.current); }, []);

  return (
    <div className="mention-wrap" ref={wrapRef}>
      <textarea ref={taRef} value={value} onChange={handleInput} placeholder={placeholder} rows={rows} disabled={disabled} />
      {mention.open && mention.results.length > 0 && (
        <div className="mention-dropdown">
          {mention.results.slice(0, 8).map(u => (
            <button key={u.id} type="button" className="mention-item" onMouseDown={e => { e.preventDefault(); insertMention(u); }}>
              <span className="avatar avatar-sm">{u.avatarUrl ? <img src={u.avatarUrl} alt={u.username} /> : <span className="avatar-fallback">{u.username.charAt(0).toUpperCase()}</span>}</span>
              <span className="mention-name">{u.username}</span>
              {u.verified && <span className="mention-verified">✓</span>}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
