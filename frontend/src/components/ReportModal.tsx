// 举报弹窗：常规原因 + 版块特色原因 + 自定义输入
import type { ReportReasons } from '../types';

type Props = {
  modal: { type: 'POST' | 'COMMENT'; id: number; reasons: ReportReasons } | null;
  reason: string;
  setReason: (v: string) => void;
  submitting: boolean;
  onClose: () => void;
  onSubmit: () => void;
};

export function ReportModal({ modal, reason, setReason, submitting, onClose, onSubmit }: Props) {
  if (!modal) return null;
  return (
    <div className="backdrop" onMouseDown={onClose}>
      <div className="modal report-modal" role="dialog" aria-modal="true" onMouseDown={e => e.stopPropagation()}>
        <button type="button" className="close" onClick={onClose}>×</button>
        <p className="eyebrow">TBFORUM / REPORT</p>
        <h2>举报{modal.type === 'POST' ? '帖子' : '评论'}</h2>
        <p className="report-hint">选择举报原因，管理员将尽快处理</p>
        <div className="report-reasons">
          <p className="report-group-label">常规原因</p>
          {modal.reasons.standard.map(r => (
            <label key={r} className={'report-reason' + (reason === r ? ' selected' : '')}>
              <input type="radio" name="report-reason" value={r} checked={reason === r} onChange={() => setReason(r)} />{r}
            </label>
          ))}
          {modal.reasons.custom.length > 0 && (
            <>
              <p className="report-group-label">版块特色</p>
              {modal.reasons.custom.map(r => (
                <label key={r} className={'report-reason' + (reason === r ? ' selected' : '')}>
                  <input type="radio" name="report-reason" value={r} checked={reason === r} onChange={() => setReason(r)} />{r}
                </label>
              ))}
            </>
          )}
        </div>
        <input className="report-custom-input" value={reason} onChange={e => setReason(e.target.value)} maxLength={80} placeholder="或输入自定义原因" />
        <button className="primary" type="button" onClick={onSubmit} disabled={!reason.trim() || submitting}>{submitting ? '提交中…' : '提交举报'}</button>
      </div>
    </div>
  );
}
