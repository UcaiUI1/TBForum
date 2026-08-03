// 帖子右键上下文菜单（精华/锁帖/删除）
import type { Post } from '../types';

type Props = {
  ctx: { x: number; y: number; p: Post } | null;
  isModerator: boolean;
  canDelete: (p: Post) => boolean;
  onClose: () => void;
  onToggleFeature: (p: Post) => void;
  onToggleLock: (p: Post) => void;
  onDelete: (p: Post) => void;
};

export function ContextMenu({ ctx, isModerator, canDelete, onClose, onToggleFeature, onToggleLock, onDelete }: Props) {
  if (!ctx) return null;
  return (
    <div className="ctx-menu" style={{ left: Math.min(ctx.x, window.innerWidth - 180), top: Math.min(ctx.y, window.innerHeight - 160) }} onMouseDown={e => e.stopPropagation()}>
      {isModerator && <button onClick={() => { onToggleFeature(ctx.p); onClose(); }}>{ctx.p.featured ? '★ 取消精华' : '★ 设为精华'}</button>}
      {isModerator && <button onClick={() => { onToggleLock(ctx.p); onClose(); }}>{ctx.p.locked ? '🔒 解锁帖子' : '🔒 锁定帖子'}</button>}
      {canDelete(ctx.p) && <button className="ctx-danger" onClick={() => { const p = ctx.p; onClose(); onDelete(p); }}>✕ 删除帖子</button>}
    </div>
  );
}
