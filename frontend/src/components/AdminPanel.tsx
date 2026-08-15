// 管理员后台：举报处理 + 用户治理（封禁/禁言）+ 审计日志
import { useState } from 'react';
import type { ReportView, ReportFilter, UserView, AuditLogView } from '../types';
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
  onBan: (username: string, days: number) => void;
  onMute: (username: string, days: number) => void;
  onSearchUsers: (q: string) => Promise<UserView[]>;
  audit: AuditLogView[];
  auditPage: number;
  auditTotalPages: number;
  setAuditPage: (p: number) => void;
};

type Tab = 'reports' | 'users' | 'audit';

export function AdminPanel({ reports, filter, page, totalPages, counts, setFilter, setPage, onClose, onHandle, onBan, onMute, onSearchUsers, audit, auditPage, auditTotalPages, setAuditPage }: Props) {
  const [tab, setTab] = useState<Tab>('reports');
  const [userQuery, setUserQuery] = useState('');
  const [users, setUsers] = useState<UserView[]>([]);
  const [searching, setSearching] = useState(false);

  async function searchUsers() {
    if (!userQuery.trim()) return;
    setSearching(true);
    try {
      setUsers(await onSearchUsers(userQuery.trim()));
    } finally { setSearching(false); }
  }

  return (
    <div className="backdrop" onMouseDown={onClose}>
      <div className="modal admin-modal" role="dialog" aria-modal="true" onMouseDown={e => e.stopPropagation()}>
        <button type="button" className="close" onClick={onClose}>×</button>
        <p className="eyebrow">TBFORUM / ADMIN</p>
        <h2>管理后台</h2>
        <div className="admin-tabs">
          <button className={tab === 'reports' ? 'selected' : ''} onClick={() => setTab('reports')}>举报{counts.pending > 0 ? `（${counts.pending}）` : ''}</button>
          <button className={tab === 'users' ? 'selected' : ''} onClick={() => setTab('users')}>用户</button>
          <button className={tab === 'audit' ? 'selected' : ''} onClick={() => setTab('audit')}>审计</button>
        </div>

        {tab === 'reports' && (
          <>
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
          </>
        )}

        {tab === 'users' && (
          <div className="mod-section">
            <div className="admin-user-search">
              <input value={userQuery} onChange={e => setUserQuery(e.target.value)} placeholder="输入用户名搜索" onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); searchUsers(); } }} />
              <button className="primary compact" onClick={searchUsers}>{searching ? '搜索中…' : '搜索'}</button>
            </div>
            {users.length ? users.map(u => (
              <div key={u.id} className="admin-user-row">
                <span className="avatar avatar-sm">{u.avatarUrl ? <img src={u.avatarUrl} alt={u.username} /> : <span className="avatar-fallback">{u.username.charAt(0).toUpperCase()}</span>}</span>
                <b>{u.username}</b>
                {u.verified && <span className="verified-badge">✓</span>}
                <span className="admin-user-role">{u.role || 'USER'}</span>
                <span className="admin-user-actions">
                  <button className="feature-btn" onClick={() => onBan(u.username, 7)}>封禁 7 天</button>
                  <button className="plain" onClick={() => onBan(u.username, 0)}>解封</button>
                  <button className="lock-btn" onClick={() => onMute(u.username, 1)}>禁言 1 天</button>
                  <button className="plain" onClick={() => onMute(u.username, 0)}>解除禁言</button>
                </span>
              </div>
            )) : <p className="mod-empty">{userQuery ? '没有找到该用户' : '输入用户名搜索用户'}</p>}
          </div>
        )}

        {tab === 'audit' && (
          <div className="admin-report-list">
            {audit.length ? audit.map(a => (
              <div key={a.id} className="admin-report-item">
                <div className="admin-report-head">
                  <span className="status-pill status-resolved">{a.action}</span>
                  <span className="admin-report-target">{a.targetType || ''}{a.targetId ? ` #${a.targetId}` : ''}</span>
                  <time>{time(a.createdAt)}</time>
                </div>
                <div className="admin-report-body"><b>{a.actor || '系统'}</b> {a.detail || ''}</div>
              </div>
            )) : <p className="mod-empty">暂无审计记录</p>}
            {auditTotalPages > 1 && (
              <div className="pagination">
                <button className="page-btn" disabled={auditPage === 0} onClick={() => setAuditPage(auditPage - 1)}>← 上一页</button>
                <span className="page-info">第 {auditPage + 1} / {auditTotalPages} 页</span>
                <button className="page-btn" disabled={auditPage >= auditTotalPages - 1} onClick={() => setAuditPage(auditPage + 1)}>下一页 →</button>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
