// 帖子详情视图：正文、操作栏、评论区
import React, { FormEvent, useMemo, useState } from 'react';
import type { Post, Comment } from '../types';
import { time } from '../utils/format';
import { renderMd } from '../utils/markdown';
import { MentionTextarea } from './MentionTextarea';
import { CommentView } from './CommentView';
import { IconComment, IconLock, IconReport, IconStar, IconUp, IconView } from './Icons';

type Props = {
  viewPost: Post;
  comments: Comment[];
  replyTo: number | null;
  setReplyTo: (id: number | null) => void;
  commentContent: string;
  setCommentContent: (v: string) => void;
  token: string;
  user: string;
  isModerator: boolean;
  onUser: (username: string) => void;
  onClose: () => void;
  onVote: (p: Post) => void;
  onToggleFavorite: (p: Post) => void;
  onToggleFeature: (p: Post) => void;
  onToggleLock: (p: Post) => void;
  onDelete: (p: Post) => void;
  onOpenReport: (type: 'POST' | 'COMMENT', id: number) => void;
  onSubmitComment: (e: FormEvent<HTMLFormElement>) => void;
  onReloadComments: () => void;
  onPinComment: (c: Comment) => void;
  onDeleteComment: (c: Comment) => void;
  commentsHasMore: boolean;
  commentsLoadingMore: boolean;
  onLoadMoreComments: () => void;
  onSaveEdit: () => void;
  onCancelEdit: () => void;
  onStartEdit: () => void;
  editPost: boolean;
  editPostTitle: string;
  setEditPostTitle: (v: string) => void;
  editPostContent: string;
  setEditPostContent: (v: string) => void;
  onError: (e: unknown) => void;
};

export function PostDetail(props: Props) {
  const { viewPost, comments, replyTo, setReplyTo, commentContent, setCommentContent, token, user, isModerator, onUser, onClose, onVote, onToggleFavorite, onToggleFeature, onToggleLock, onDelete, onDeleteComment, commentsHasMore, commentsLoadingMore, onLoadMoreComments, onOpenReport, onSubmitComment, onReloadComments, onPinComment, editPost, editPostTitle, setEditPostTitle, editPostContent, setEditPostContent, onStartEdit, onSaveEdit, onCancelEdit, onError } = props;
  const renderedPostHtml = useMemo(() => renderMd(viewPost.content), [viewPost.content]);

  return (
    <section className="post-detail-view">
      <button className="back-btn" onClick={onClose}>← 返回列表</button>
      <article className="post-detail-card">
        <div className="meta">
          <span className="forum-pill">#{viewPost.forum}</span>
          <button className="author-link" onClick={() => onUser(viewPost.author)}>
            <span className="avatar avatar-sm">{viewPost.authorAvatar ? <img src={viewPost.authorAvatar} alt={viewPost.author} /> : <span className="avatar-fallback">{viewPost.author.charAt(0).toUpperCase()}</span>}</span>
            {viewPost.author}
          </button>
          <span>·</span>
          <time>{time(viewPost.createdAt)}</time>
          {viewPost.featured && <span className="featured">精选</span>}
          {viewPost.locked && <span className="locked-pill">🔒 已锁</span>}
        </div>
        {editPost ? (
          <div className="edit-form">
            <input value={editPostTitle} onChange={e => setEditPostTitle(e.target.value)} maxLength={180} placeholder="标题" />
            <MentionTextarea value={editPostContent} onChange={setEditPostContent} rows={10} token={token} onError={onError} placeholder="正文（支持 Markdown 格式）" />
            <div className="edit-actions">
              <button className="plain" onClick={onCancelEdit}>取消</button>
              <button className="primary" onClick={onSaveEdit}>保存</button>
            </div>
          </div>
        ) : (
          <>
            <h1>{viewPost.title}{viewPost.edited && <span className="edited-badge">已编辑</span>}</h1>
            <div className="post-detail-content markdown-body" dangerouslySetInnerHTML={{ __html: renderedPostHtml }} />
          </>
        )}
        <div className="post-detail-foot">
          <span className="icon-label"><IconView size={14} />{viewPost.views} 浏览</span>
          <span className="icon-label"><IconComment size={14} />{viewPost.comments} 评论</span>
          <span className="icon-label"><IconUp size={14} />{viewPost.upvotes} 支持</span>
          <button className={viewPost.upvoted ? 'primary compact voted' : 'primary compact'} onClick={() => onVote(viewPost)}><IconUp size={14} />{viewPost.upvoted ? '已支持' : '支持这篇讨论'}</button>
          {token && <button className={viewPost.favorited ? 'fav-btn favorited' : 'fav-btn'} onClick={() => onToggleFavorite(viewPost)}><IconStar size={13} />{viewPost.favorited ? '已收藏' : '收藏'}</button>}
          {token && viewPost.author === user && <button className="plain" onClick={onStartEdit}>编辑</button>}
          {token && (viewPost.author === user || isModerator) && <button className="danger-btn" onClick={() => onDelete(viewPost)}>删除</button>}
          {token && isModerator && <button className="feature-btn" onClick={() => onToggleFeature(viewPost)}>{viewPost.featured ? '取消精华' : '设为精华'}</button>}
          {token && isModerator && <button className="lock-btn" onClick={() => onToggleLock(viewPost)}><IconLock size={13} />{viewPost.locked ? '解锁' : '锁帖'}</button>}
          {token && viewPost.author !== user && <button className="report-btn" onClick={() => onOpenReport('POST', viewPost.id)}><IconReport size={13} />举报</button>}
        </div>
      </article>
      <section className="comments-section">
        <h3>评论{comments.length > 0 && <span className="comment-count">（{comments.length}）</span>}</h3>
        {viewPost.locked ? (
          <div className="locked-notice">🔒 该帖子已被锁定，无法发表评论。</div>
        ) : (
          <form className="comment-form" onSubmit={onSubmitComment}>
            {replyTo != null && <div className="reply-hint">回复 #{replyTo} <button type="button" className="text-link" onClick={() => setReplyTo(null)}>取消</button></div>}
            <MentionTextarea value={commentContent} onChange={setCommentContent} rows={3} token={token} onError={onError} placeholder={token ? '写下你的想法…（输入 @ 提及用户）' : '登录后参与讨论'} disabled={!token} />
            {token && <button className="primary" type="submit">发表评论</button>}
          </form>
        )}
        <div className="comment-tree">
          {comments.map(c => (
            <CommentView key={c.id} c={c} replyTo={replyTo} setReplyTo={setReplyTo} token={token} onUser={onUser} user={user} postId={viewPost.id} onReload={onReloadComments} onError={onError} onPin={onPinComment} onReport={onOpenReport} onDelete={onDeleteComment} isModerator={isModerator} />
          ))}
          {!comments.length && <p className="no-comments">还没有评论，成为第一个发言的人。</p>}
        </div>
        {commentsHasMore && (
          <div className="comments-more">
            <button className="page-btn" disabled={commentsLoadingMore} onClick={onLoadMoreComments}>
              {commentsLoadingMore ? '加载中…' : '加载更多评论'}
            </button>
          </div>
        )}
      </section>
    </section>
  );
}
