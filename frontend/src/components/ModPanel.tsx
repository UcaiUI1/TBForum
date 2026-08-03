// 版主管理面板：小版主列表 + 版块特色举报原因设置
import type { Forum, UserView } from '../types';

type Props = {
  active: Forum;
  modList: UserView[];
  reason1: string;
  reason2: string;
  user: string;
  setReason1: (v: string) => void;
  setReason2: (v: string) => void;
  onClose: () => void;
  onRemoveModerator: (username: string) => void;
  onSaveReportReasons: () => void;
};

export function ModPanel({ active, modList, reason1, reason2, user, setReason1, setReason2, onClose, onRemoveModerator, onSaveReportReasons }: Props) {
  return (
    <div className="backdrop" onMouseDown={onClose}>
      <div className="modal mod-modal" role="dialog" aria-modal="true" onMouseDown={e => e.stopPropagation()}>
        <button type="button" className="close" onClick={onClose}>×</button>
        <p className="eyebrow">TBFORUM / MODERATION</p>
        <h2>「{active.name}」管理面板</h2>
        <div className="mod-section">
          <h3>小版主（{modList.length}/2）</h3>
          {modList.length ? modList.map(m => (
            <div key={m.id} className="mod-row">
              <span className="avatar avatar-sm">{m.avatarUrl ? <img src={m.avatarUrl} alt={m.username} /> : <span className="avatar-fallback">{m.username.charAt(0).toUpperCase()}</span>}</span>
              <b>{m.username}</b>
              {m.verified && <span className="verified-badge">✓</span>}
              {active.owner.username === user && <button className="danger-btn" onClick={() => onRemoveModerator(m.username)}>撤任</button>}
            </div>
          )) : <p className="mod-empty">还没有小版主，最多可任命 2 名</p>}
        </div>
        <div className="mod-section">
          <h3>版块特色举报原因</h3>
          <p className="mod-hint">最多 2 个，留空表示不添加。可以是趣味性、无害的选项。</p>
          <input value={reason1} onChange={e => setReason1(e.target.value)} maxLength={60} placeholder="特色原因 1（如：没什么，单纯急了）" />
          <input value={reason2} onChange={e => setReason2(e.target.value)} maxLength={60} placeholder="特色原因 2（可留空）" />
          <button className="primary" type="button" onClick={onSaveReportReasons}>保存特色原因</button>
        </div>
      </div>
    </div>
  );
}
