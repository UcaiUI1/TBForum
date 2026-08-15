// 帖子列表区：hero、排序工具栏、帖子卡片、分页。支持搜索态与已点赞高亮。
import type { Post, Forum } from '../types';
import { time } from '../utils/format';

type Props = {
  posts: Post[];
  active: Forum | null;
  globalMode: boolean;
  activeTag: string | null;
  order: string;
  page: number;
  totalPages: number;
  mods: string[];
  user: string;
  token: string;
  isModerator: boolean;
  setOrder: (o: string) => void;
  setSeed: (s: string) => void;
  setPage: (p: number) => void;
  onOpenPost: (p: Post) => void;
  onVote: (p: Post) => void;
  onEnterTag: (tag: string) => void;
  onToggleFeature: (p: Post) => void;
  onPostContext: (e: React.MouseEvent, p: Post) => void;
  onOpenUser: (username: string) => void;
  onNewPost: () => void;
  searching: boolean;
  query: string;
  onExitSearch: () => void;
  loading: boolean;
};

export function PostList({ posts, active, globalMode, activeTag, order, page, totalPages, mods, user, token, isModerator, setOrder, setSeed, setPage, onOpenPost, onVote, onEnterTag, onToggleFeature, onPostContext, onOpenUser, onNewPost, searching, query, onExitSearch, loading }: Props) {
  return (
    <>
      <section className="hero">
        <div>
          <p className="eyebrow">{searching ? 'SEARCH / RESULTS' : globalMode ? 'COMMUNITY / GLOBAL FEED' : activeTag ? `COMMUNITY / TAG #${activeTag.toUpperCase()}` : 'COMMUNITY / ' + (active?.slug?.toUpperCase() || 'LOADING')}</p>
          <h1>{searching ? `搜索「${query}」` : globalMode ? '全站动态' : activeTag ? `#${activeTag}` : active?.name || '发现正在发生的讨论'}</h1>
          <p>{searching ? '为你找到的相关讨论。' : globalMode ? '汇集所有版块的最新讨论，发现值得参与的话题。' : activeTag ? `带有 #${activeTag} 标签的帖子集合。` : active?.description || '每个想法都值得一处安静的空间。'}</p>
          {token && active && !globalMode && !activeTag && <span className="role-badge">{active.owner.username === user ? '版主' : mods.includes(user) ? '小版主' : '成员'}</span>}
        </div>
        <div className="hero-mark">{searching ? '⌕' : globalMode ? '◉' : activeTag ? '#' : '◉'}</div>
      </section>
      <section className="toolbar">
        <div className="tabs">
          {([['latest', '最新'], ['hot', '热门'], ['featured', '精华'], ['fate', '随缘 ✦']] as const).map(([key, label]) => (
            <button key={key} className={order === key ? 'selected' : ''} onClick={() => { if (key === 'fate') setSeed(String(Math.floor(Math.random() * (2 ** 31 - 1)))); setPage(0); setOrder(key); }}>{label}</button>
          ))}
        </div>
        <span>{posts.length} {searching ? '条结果' : '篇讨论'}</span>
      </section>
      <section className="feed">
        {loading && posts.length === 0 && (
          <>
            {[0, 1, 2, 3, 4].map(i => (
              <div className="post skeleton-card" key={i}>
                <div className="vote"><div className="sk sk-arrow" /><div className="sk sk-num" /></div>
                <div className="post-body">
                  <div className="sk sk-line w20" />
                  <div className="sk sk-line w80" />
                  <div className="sk sk-line w60" />
                  <div className="sk sk-line w40" />
                </div>
              </div>
            ))}
          </>
        )}
        {posts.map(p => (
          <article className="post" key={p.id} onContextMenu={e => onPostContext(e, p)}>
            <div className="vote">
              <button className={p.upvoted ? 'voted' : ''} onClick={() => onVote(p)}>▲</button>
              <b>{p.upvotes}</b>
            </div>
            <div className="post-body">
              <div className="meta">
                <span className="forum-pill">#{p.forum}</span>
                <button className="author-link" onClick={() => onOpenUser(p.author)}>
                  <span className="avatar avatar-sm">{p.authorAvatar ? <img src={p.authorAvatar} alt={p.author} /> : <span className="avatar-fallback">{p.author.charAt(0).toUpperCase()}</span>}</span>
                  {p.author}
                </button>
                <span>·</span>
                <time>{time(p.createdAt)}</time>
                {p.featured && <span className="featured">精选</span>}
                {p.locked && <span className="locked-pill">🔒 已锁</span>}
              </div>
              <h2 className="post-title-link" onClick={() => onOpenPost(p)}>{p.title}</h2>
              <p className="post-excerpt" onClick={() => onOpenPost(p)}>{p.content}</p>
              {p.tags && p.tags.length > 0 && (
                <div className="post-tags">
                  {p.tags.map(t => <button key={t} className="post-tag" onClick={e => { e.stopPropagation(); onEnterTag(t); }}>#{t}</button>)}
                </div>
              )}
              <div className="post-foot">
                <span>◉ {p.views} 浏览</span>
                <span className="comment-link" onClick={() => onOpenPost(p)}>◉ {p.comments} 评论</span>
                <button className={p.upvoted ? 'voted' : ''} onClick={() => onVote(p)}>{p.upvoted ? '✓ 已支持' : '支持这篇讨论'}</button>
                {token && isModerator && <button className="feature-btn-sm" onClick={() => onToggleFeature(p)}>{p.featured ? '取消精华' : '设为精华'}</button>}
              </div>
            </div>
          </article>
        ))}
        {!posts.length && (
          <div className="empty">
            <div>{searching ? '⌕' : '◉'}</div>
            <h2>{searching ? '没有找到相关内容' : '这里还很安静'}</h2>
            <p>{searching ? '换个关键词试试，或者浏览全站动态。' : '成为第一个留下想法的人吧。'}</p>
            {searching ? <button className="primary" onClick={onExitSearch}>返回列表</button> : <button className="primary" onClick={onNewPost}>发布帖子</button>}
          </div>
        )}
      </section>
      {totalPages > 1 && (
        <div className="pagination">
          <button className="page-btn" disabled={page === 0} onClick={() => { window.scrollTo({ top: 0, behavior: 'smooth' }); setPage(page - 1); }}>← 上一页</button>
          <span className="page-info">第 {page + 1} / {totalPages} 页</span>
          <button className="page-btn" disabled={page >= totalPages - 1} onClick={() => { window.scrollTo({ top: 0, behavior: 'smooth' }); setPage(page + 1); }}>下一页 →</button>
        </div>
      )}
    </>
  );
}
