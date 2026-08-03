// 管理员举报后台：举报列表 + 筛选 + 分页
import type { ReportView, ReportFilter } from '../types';
import { time } from '../utils/format';

type Props = {
  reports: ReportView[];
  filter: ReportFilter;
  page: number;
  totalPages: number;
  counts: { total: number; pending: number };
  setFilter: (f: ReportFilter) => void;
  setPage: (p: number) => void;
  onClose: () => void;
  onHandle: (id: number, status: 'RESOLVED' | 'DISMISSED') => void;
};

export function AdminPanel({ reports, filter, page, totalPages, counts, setFilter, setPage, onClose, onHandle }: Props) {
  return (
    <div className="backdrop" onMouseDown={onClose}>
      <div className="modal admin-modal" role="dialog" aria-modal="true" onMouseDown={e => e.stopPropagation()}>
        <button type="button" className="close" onClick={onClose}>×</button>
        <p className="eyebrow">TBFORUM / ADMIN</p>
        <h2>举报后台</h2>
        <div className="admin-stats">
          <span>待处理 <b>{counts.pending}</b></span>
          <span>总计 <b>{counts.total}</b></span>
        </div>
        <div className="admin-tabs">
          {(['PENDING', 'RESOLVED', 'DISMISSED', 'ALL'] as const).map(f => (
            <button key={f} className={filter === f ? 'selected' : ''} onClick={() => setFilter(f)}>{f === 'PENDING' ? '待处理' : f === 'RESOLVED' ? '已处理' : f === 'DISMISSED' ? '已驳回' : '全部'}</button>
          ))}
        </div>
        <div className="admin-report-list">
          {reports.length ? reports.map(r => (
            <div key={r.id} className={'admin-report-item status-' + r.status.toLowerCase()}>
              <div className="admin-report-head">
                <span className={'status-pill status-' + r.status.toLowerCase()}>{r.status === 'PENDING' ? '待处理' : r.status === 'RESOLVED' ? '已处理' : '已驳回'}</span>
                <span className="admin-report-target">{r.targetType === 'POST' ? '帖子' : '评论'} #{r.targetId}</span>
                <time>{time(r.createdAt)}</time>
              </div>
              <div className="admin-report-body"><b>{r.reporter}</b> 举报：<span className="admin-report-reason">{r.reason}</span></div>
              {r.handledBy && <div className="admin-report-handled">由 {r.handledBy} 处理</div>}
              {r.status === 'PENDING' && (
                <div className="admin-report-actions">
                  <button className="feature-btn" onClick={() => onHandle(r.id, 'RESOLVED')}>标记已处理</button>
                  <button className="danger-btn" onClick={() => onHandle(r.id, 'DISMISSED')}>驳回</button>
                </div>
              )}
            </div>
          )) : <p className="mod-empty">暂无举报记录</p>}
        </div>
        {totalPages > 1 && (
          <div className="pagination">
            <button className="page-btn" disabled={page === 0} onClick={() => setPage(page - 1)}>← 上一页</button>
            <span className="page-info">第 {page + 1} / {totalPages} 页</span>
            <button className="page-btn" disabled={page >= totalPages - 1} onClick={() => setPage(page + 1)}>下一页 →</button>
          </div>
        )}
      </div>
    </div>
  );
}
