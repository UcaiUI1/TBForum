// 顶栏：品牌、搜索、用户菜单、通知铃铛、发布按钮
import React, { FormEvent, useEffect, useMemo, useRef } from 'react';
import { useAuth } from '../contexts/AuthContext';
import { useNotifications, notifIcon } from '../contexts/NotificationContext';
import { time } from '../utils/format';
import { reportApi } from '../services/api';
import type { Notification } from '../types';
import { useState } from 'react';
import { IconBell, IconChevron, IconMenu, IconMoon, IconSearch, IconSun, NotifIcon } from './Icons';

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

  // 同类通知合并分组（同一帖子同一类型）
  const groups = useMemo(() => {
    const map = new Map<string, { n: Notification; count: number; unread: number; actors: Set<string> }>();
    for (const n of notifications) {
      const key = `${n.kind}:${n.postId ?? ''}`;
      const g = map.get(key) ?? { n, count: 0, unread: 0, actors: new Set<string>() };
      g.count += 1;
      if (!n.read) g.unread += 1;
      if (n.actor) g.actors.add(n.actor);
      map.set(key, g);
    }
    return [...map.values()];
  }, [notifications]);

  function doLogout() {
    logout();
    setMenu(false);
  }

  return (
    <header>
      <button className="nav-toggle" aria-label="打开导航" onClick={onOpenNav}><IconMenu size={20} /></button>
      <button className="brand" onClick={onEnterGlobal}><span>TB</span><b>FORUM</b></button>
      <form className="search" onSubmit={onSearch}>
        <IconSearch size={15} />
        <input value={query} onChange={e => setQuery(e.target.value)} placeholder="搜索值得一读的讨论" />
        <kbd>Enter</kbd>
      </form>
      <div className="head-actions">
        <button className="theme-toggle" aria-label="切换主题" onClick={onToggleTheme}>{theme === 'dark' ? <IconSun size={16} /> : <IconMoon size={16} />}</button>
        {user ? (
          <div className="user-menu" ref={menuRef}>
            <button className="user" onClick={() => setMenu(m => !m)}>◉ {user}<i className="caret"><IconChevron size={12} /></i></button>
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
            <button className="notif-bell" aria-label="通知" onClick={() => setShowNotif(!showNotif)}><IconBell size={18} />{unreadCount > 0 && <span className="notif-badge">{unreadCount > 99 ? '99+' : unreadCount}</span>}</button>
            {showNotif && (
              <div className="notif-panel">
                {groups.length === 0 ? <div className="notif-empty">暂无通知</div> : (
                  <>
                    {groups.slice(0, 20).map(g => (
                      <div key={g.n.id} className={'notif-item' + (g.unread ? ' unread' : '')} onClick={() => onNotifClick(g.n)}>
                        <span className="notif-icon"><NotifIcon kind={g.n.kind} size={13} /></span>
                        <div className="notif-body">
                          <b>{g.count > 1 ? `${[...g.actors].slice(0, 3).join('、')} 等 ${g.count} 条` : (g.n.actor ?? '')}</b>
                          <span>{g.n.text}{g.unread > 0 && `（${g.unread} 条未读）`}</span>
                          <time>{time(g.n.createdAt)}</time>
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
