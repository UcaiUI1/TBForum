// 顶栏：品牌、搜索、用户菜单、通知铃铛、发布按钮
import React, { FormEvent, useEffect, useRef } from 'react';
import { useAuth } from '../contexts/AuthContext';
import { useNotifications, notifIcon } from '../contexts/NotificationContext';
import { time } from '../utils/format';
import { reportApi } from '../services/api';
import { useState } from 'react';

type Props = {
  query: string;
  setQuery: (v: string) => void;
  onSearch: (e: FormEvent) => void;
  onEnterGlobal: () => void;
  onOpenProfile: () => void;
  onOpenAdminPanel: () => void;
  onRequireLogin: () => void;
  onOpenPost: () => void;
  onCreateForum: () => void;
  onVerify: () => void;
  onOpenNav: () => void;
  theme: 'dark' | 'light';
  onToggleTheme: () => void;
};

export function Header({ query, setQuery, onSearch, onEnterGlobal, onOpenProfile, onOpenAdminPanel, onRequireLogin, onOpenPost, onCreateForum, onVerify, onOpenNav, theme, onToggleTheme }: Props) {
  const { user, verified, isAdmin, logout } = useAuth();
  const { notifications, unreadCount, showNotif, setShowNotif, markAllRead, onNotifClick } = useNotifications();
  const [menu, setMenu] = useState(false);
  const [reportPending, setReportPending] = useState(0);
  const menuRef = useRef<HTMLDivElement>(null);
  const notifRef = useRef<HTMLDivElement>(null);

  // 管理员举报待处理数
  useEffect(() => {
    if (!isAdmin) return;
    const { token } = { token: localStorage.getItem('tb-token') || '' };
    if (!token) return;
    reportApi.counts(token).then(d => setReportPending(d.pending)).catch(e => console.warn('API:', e));
  }, [isAdmin]);

  // 点击外部关闭菜单
  useEffect(() => {
    if (!menu) return;
    const h = (e: MouseEvent) => { if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenu(false); };
    document.addEventListener('mousedown', h);
    return () => document.removeEventListener('mousedown', h);
  }, [menu]);

  useEffect(() => {
    if (!showNotif) return;
    const h = (e: MouseEvent) => { if (notifRef.current && !notifRef.current.contains(e.target as Node)) setShowNotif(false); };
    document.addEventListener('mousedown', h);
    return () => document.removeEventListener('mousedown', h);
  }, [showNotif, setShowNotif]);

  function doLogout() {
    logout();
    setMenu(false);
  }

  return (
    <header>
      <button className="nav-toggle" aria-label="打开导航" onClick={onOpenNav}>☰</button>
      <button className="brand" onClick={onEnterGlobal}><span>TB</span><b>FORUM</b></button>
      <form className="search" onSubmit={onSearch}>
        <span>⌕</span>
        <input value={query} onChange={e => setQuery(e.target.value)} placeholder="搜索值得一读的讨论" />
        <kbd>Enter</kbd>
      </form>
      <div className="head-actions">
        <button className="theme-toggle" aria-label="切换主题" onClick={onToggleTheme}>{theme === 'dark' ? '☀' : '☾'}</button>
        {user ? (
          <div className="user-menu" ref={menuRef}>
            <button className="user" onClick={() => setMenu(m => !m)}>◉ {user}<i className="caret">▾</i></button>
            {menu && (
              <div className="menu-pop">
                {!verified && <button onClick={onVerify}>实名认证</button>}
                <button onClick={onOpenProfile}>个人中心</button>
                {isAdmin && <button onClick={onOpenAdminPanel}>举报后台{reportPending > 0 && <span className="menu-badge">{reportPending}</span>}</button>}
                <button onClick={doLogout}>退出账户</button>
                <button onClick={() => { logout(); setMenu(false); onRequireLogin(); }}>更改账户</button>
              </div>
            )}
          </div>
        ) : (
          <button className="plain" onClick={onRequireLogin}>登录</button>
        )}
        {localStorage.getItem('tb-token') && (
          <div className="notif-wrap" ref={notifRef}>
            <button className="notif-bell" aria-label="通知" onClick={() => setShowNotif(!showNotif)}>🔔{unreadCount > 0 && <span className="notif-badge">{unreadCount > 99 ? '99+' : unreadCount}</span>}</button>
            {showNotif && (
              <div className="notif-panel">
                {notifications.length === 0 ? <div className="notif-empty">暂无通知</div> : (
                  <>
                    {notifications.slice(0, 20).map(n => (
                      <div key={n.id} className={'notif-item' + (n.read ? '' : ' unread')} onClick={() => onNotifClick(n)}>
                        <span className="notif-icon">{notifIcon(n.kind)}</span>
                        <div className="notif-body">
                          <b>{n.actor}</b>
                          <span>{n.text}</span>
                          <time>{time(n.createdAt)}</time>
                        </div>
                      </div>
                    ))}
                    <button className="notif-readall" onClick={markAllRead}>全部已读</button>
                  </>
                )}
              </div>
            )}
          </div>
        )}
        <button className="primary compact" onClick={onOpenPost}>＋ 发布</button>
      </div>
    </header>
  );
}
