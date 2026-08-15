// @/# 补全输入框：输入 @ 触发用户搜索，输入 # 触发热门标签补全，选中后插入文本
import React, { useEffect, useRef, useState } from 'react';
import type { UserView, TagView } from '../types';
import { userApi, tagApi } from '../services/api';

type Props = {
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  rows?: number;
  token: string;
  onError: (e: unknown) => void;
  disabled?: boolean;
};

type Completion = {
  open: boolean;
  kind: 'user' | 'tag';
  q: string;
  results: (UserView | TagView)[];
  pos: number;
};

const EMPTY: Completion = { open: false, kind: 'user', q: '', results: [], pos: 0 };

export function MentionTextarea({ value, onChange, placeholder, rows, token, onError, disabled }: Props) {
  const taRef = useRef<HTMLTextAreaElement>(null);
  const wrapRef = useRef<HTMLDivElement>(null);
  const [completion, setCompletion] = useState<Completion>(EMPTY);
  const searchTimer = useRef<number | undefined>(undefined);
  const tagsCache = useRef<TagView[] | null>(null);

  function handleInput(e: React.ChangeEvent<HTMLTextAreaElement>) {
    onChange(e.target.value);
    const ta = e.target;
    const pos = ta.selectionStart;
    const before = ta.value.substring(0, pos);
    const atIdx = before.lastIndexOf('@');
    const hashIdx = before.lastIndexOf('#');
    const idx = Math.max(atIdx, hashIdx);
    if (idx === -1 || (idx > 0 && !/\s/.test(ta.value[idx - 1]))) { setCompletion(EMPTY); return; }
    const kind = idx === hashIdx ? 'tag' : 'user';
    const q = ta.value.substring(idx + 1, pos);
    if (/\s/.test(q) || q.length > 40) { setCompletion(EMPTY); return; }
    if (q.length === 0) { setCompletion({ open: false, kind, q: '', results: [], pos }); return; }
    setCompletion({ open: true, kind, q, results: [], pos });
    if (searchTimer.current) clearTimeout(searchTimer.current);
    searchTimer.current = window.setTimeout(() => {
      if (kind === 'user') {
        userApi.search(q, token)
          .then(r => setCompletion(m => (m.kind === 'user' && m.q === q ? { ...m, results: r, open: r.length > 0 } : m)))
          .catch(err => { console.warn('API:', err); onError(err); });
      } else {
        const loadTags = tagsCache.current
          ? Promise.resolve(tagsCache.current)
          : tagApi.popular().then(t => { tagsCache.current = t; return t; });
        loadTags
          .then(all => all.filter(t => t.name.toLowerCase().startsWith(q.toLowerCase())).slice(0, 8))
          .then(r => setCompletion(m => (m.kind === 'tag' && m.q === q ? { ...m, results: r, open: r.length > 0 } : m)))
          .catch(err => { console.warn('API:', err); });
      }
    }, 200);
  }

  function insertCompletion(item: UserView | TagView) {
    const ta = taRef.current;
    if (!ta) return;
    const before = value.substring(0, completion.pos);
    const marker = completion.kind === 'user' ? '@' : '#';
    const idx = before.lastIndexOf(marker);
    if (idx === -1) return;
    const after = value.substring(completion.pos);
    const name = completion.kind === 'user' ? (item as UserView).username : (item as TagView).name;
    const newText = value.substring(0, idx + 1) + name + ' ' + after;
    onChange(newText);
    setCompletion(EMPTY);
    setTimeout(() => {
      const newPos = idx + 1 + name.length + 1;
      ta.focus();
      ta.setSelectionRange(newPos, newPos);
    }, 0);
  }

  useEffect(() => {
    if (!completion.open) return;
    const h = (e: MouseEvent) => { if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setCompletion(EMPTY); };
    document.addEventListener('mousedown', h);
    return () => document.removeEventListener('mousedown', h);
  }, [completion.open]);

  useEffect(() => () => { if (searchTimer.current) clearTimeout(searchTimer.current); }, []);

  return (
    <div className="mention-wrap" ref={wrapRef}>
      <textarea ref={taRef} value={value} onChange={handleInput} placeholder={placeholder} rows={rows} disabled={disabled} />
      {completion.open && completion.results.length > 0 && (
        <div className="mention-dropdown">
          {completion.results.slice(0, 8).map(item => {
            const isUser = completion.kind === 'user';
            const name = isUser ? (item as UserView).username : (item as TagView).name;
            const avatar = isUser ? (item as UserView).avatarUrl : null;
            const verified = isUser && (item as UserView).verified;
            const count = !isUser ? (item as TagView).postCount : null;
            return (
              <button key={name} type="button" className="mention-item" onMouseDown={e => { e.preventDefault(); insertCompletion(item); }}>
                <span className="avatar avatar-sm">
                  {avatar ? <img src={avatar} alt={name} /> : <span className="avatar-fallback">{completion.kind === 'tag' ? '#' : name.charAt(0).toUpperCase()}</span>}
                </span>
                <span className="mention-name">{completion.kind === 'tag' ? `#${name}` : name}</span>
                {verified && <span className="mention-verified">✓</span>}
                {count != null && <span className="tag-count">{count}</span>}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}
