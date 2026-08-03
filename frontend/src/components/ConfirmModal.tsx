// 删除确认弹窗
import type { Post } from '../types';

type Props = {
  post: Post | null;
  onClose: () => void;
  onConfirm: () => void;
};

export function ConfirmModal({ post, onClose, onConfirm }: Props) {
  if (!post) return null;
  return (
    <div className="backdrop" onMouseDown={onClose}>
      <div className="confirm-modal" role="dialog" aria-modal="true" onMouseDown={e => e.stopPropagation()}>
        <div className="confirm-icon">⚠</div>
        <h3>删除这篇帖子？</h3>
        <p>「{post.title}」将被删除，此操作无法撤销。</p>
        <div className="confirm-actions">
          <button className="plain" onClick={onClose}>取消</button>
          <button className="danger-btn" onClick={onConfirm}>确认删除</button>
        </div>
      </div>
    </div>
  );
}
