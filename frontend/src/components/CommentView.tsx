// 单条评论视图（递归渲染子评论）
import React, { useState } from 'react';
import type { Comment } from '../types';
import { time } from '../utils/format';
import { renderMd } from '../utils/markdown';
import { commentApi } from '../services/api';
import { MentionTextarea } from './MentionTextarea';

type Props = {
  c: Comment;
  replyTo: number | null;
  setReplyTo: (id: number | null) => void;
  token: string;
  onUser: (username: string) => void;
  user: string;
  postId: number;
  onReload: () => void;
  onError: (e: unknown) => void;
  onPin: (c: Comment) => void;
  onReport: (type: 'POST' | 'COMMENT', id: number) => void;
  onDelete: (c: Comment) => void;
  isModerator: boolean;
};

export function CommentView({ c, replyTo, setReplyTo, token, onUser, user, postId, onReload, onError, onPin, onReport, onDelete, isModerator }: Props) {
  const [editing, setEditing] = useState(false);
  const [editContent, setEditContent] = useState(c.content);

  async function saveEdit() {
    try {
      await commentApi.edit(token, postId, c.id, editContent);
      setEditing(false);
      onReload();
    } catch (err) { onError(err); }
  }

  return (
    <div className={'comment-item' + (c.pinned ? ' comment-pinned' : '')} style={{ marginLeft: c.depth * 24 }} data-comment-id={c.id}>
      {c.pinned && <div className="pinned-tag">📌 已置顶</div>}
      <div className="comment-head">
        <button className="author-link" onClick={() => onUser(c.author)}>
          <span className="avatar avatar-sm">{c.authorAvatar ? <img src={c.authorAvatar} alt={c.author} /> : <span className="avatar-fallback">{c.author.charAt(0).toUpperCase()}</span>}</span>
          <b>{c.author}</b>
        </button>
        <time>{time(c.createdAt)}</time>
        {c.edited && <span className="edited-badge">已编辑</span>}
        {token && c.author === user && !editing && <button className="reply-btn" onClick={() => { setEditContent(c.content); setEditing(true); }}>编辑</button>}
        {token && c.author === user && !editing && <button className="reply-btn del" onClick={() => onDelete(c)}>删除</button>}
        {token && isModerator && <button className={c.pinned ? 'reply-btn active' : 'reply-btn'} onClick={() => onPin(c)}>{c.pinned ? '取消置顶' : '📌 置顶'}</button>}
        {token && <button className={replyTo === c.id ? 'reply-btn active' : 'reply-btn'} onClick={() => setReplyTo(replyTo === c.id ? null : c.id)}>回复</button>}
        {token && c.author !== user && <button className="reply-btn" onClick={() => onReport('COMMENT', c.id)}>🚩</button>}
      </div>
      {editing ? (
        <div className="edit-form">
          <MentionTextarea value={editContent} onChange={setEditContent} rows={3} token={token} onError={onError} />
          <div className="edit-actions">
            <button className="plain" onClick={() => setEditing(false)}>取消</button>
            <button className="primary" onClick={saveEdit}>保存</button>
          </div>
        </div>
      ) : (
        <div className="comment-text markdown-body" dangerouslySetInnerHTML={{ __html: renderMd(c.content) }} />
      )}
      {c.children.length > 0 && c.children.map(ch => (
        <CommentView key={ch.id} c={ch} replyTo={replyTo} setReplyTo={setReplyTo} token={token} onUser={onUser} user={user} postId={postId} onReload={onReload} onError={onError} onPin={onPin} onReport={onReport} onDelete={onDelete} isModerator={isModerator} />
      ))}
    </div>
  );
}
