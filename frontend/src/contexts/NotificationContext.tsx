// 通知上下文：集中管理通知列表、未读数与 WebSocket 连接（含指数退避重连）
import React, { createContext, useContext, useEffect, useRef, useState, useCallback } from 'react';
import type { Notification } from '../types';
import { notifApi } from '../services/api';
import { useAuth } from './AuthContext';

type NotifState = {
  notifications: Notification[];
  unreadCount: number;
  showNotif: boolean;
  setShowNotif: (v: boolean) => void;
  markRead: (id: number) => Promise<void>;
  markAllRead: () => Promise<void>;
  /** 供外部跳转通知对应帖子使用 */
  onNotifClick: (n: Notification) => Promise<void>;
  setOnNotifClick: (cb: (n: Notification) => Promise<void>) => void;
};

const NotificationContext = createContext<NotifState | null>(null);

export function NotificationProvider({ children }: { children: React.ReactNode }) {
  const { token, logout } = useAuth();
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const [showNotif, setShowNotif] = useState(false);
  // 已见过的通知 id，用于 WS 消息去重
  const seenIds = useRef<Set<number>>(new Set());
  // 点击通知的回调（由 App 注入，用于跳转到对应帖子）
  const [clickCb, setClickCb] = useState<(n: Notification) => Promise<void>>(async () => {});

  // token 变化时拉取通知
  useEffect(() => {
    if (!token) return;
    notifApi.list(token).then(list => {
      setNotifications(list);
      seenIds.current = new Set(list.map(n => n.id));
    }).catch(e => console.warn('API:', e));
    notifApi.unreadCount(token).then(d => setUnreadCount(d.count)).catch(e => console.warn('API:', e));
  }, [token]);

  // 拉取快照（重连后用于补齐断线期间错过的通知）
  const resync = useCallback(() => {
    if (!token) return;
    notifApi.list(token).then(list => {
      setNotifications(list);
      seenIds.current = new Set(list.map(n => n.id));
    }).catch(e => console.warn('API:', e));
    notifApi.unreadCount(token).then(d => setUnreadCount(d.count)).catch(e => console.warn('API:', e));
  }, [token]);

  // WebSocket 连接（指数退避：3s→6s→12s→24s→30s 封顶）
  useEffect(() => {
    if (!token) return;
    let ws: WebSocket | null = null;
    let timer: number | undefined;
    let closed = false;
    let retryCount = 0;
    const connect = () => {
      try {
        ws = new WebSocket(`${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws/notifications`, [`bearer.${token}`]);
        ws.onopen = () => { retryCount = 0; resync(); };
        ws.onmessage = e => {
          try {
            const d = JSON.parse(e.data);
            if (Array.isArray(d)) {
              seenIds.current = new Set(d.map((x: Notification) => x.id));
              setNotifications(d.slice(0, 50));
              setUnreadCount(d.filter((x: Notification) => !x.read).length);
            } else if (d && typeof d.id === 'number') {
              if (seenIds.current.has(d.id)) return;
              seenIds.current.add(d.id);
              setNotifications(list => [d as Notification, ...list].slice(0, 50));
              if (!(d as Notification).read) setUnreadCount(c => c + 1);
            }
          } catch (e) { console.warn('WS:', e); }
        };
        ws.onclose = (ev) => {
          if (closed) return;
          if (ev.code === 1008) { logout(); return; }
          const d = Math.min(3000 * Math.pow(2, retryCount), 30000);
          retryCount++;
          timer = window.setTimeout(connect, d);
        };
        ws.onerror = () => { ws?.close(); };
      } catch {
        if (closed) return;
        const d = Math.min(3000 * Math.pow(2, retryCount), 30000);
        retryCount++;
        timer = window.setTimeout(connect, d);
      }
    };
    connect();
    return () => { closed = true; if (timer) clearTimeout(timer); ws?.close(); };
  }, [token, logout, resync]);

  const markRead = useCallback(async (id: number) => {
    if (!token) return;
    try {
      await notifApi.markRead(token, id);
      setNotifications(list => list.map(x => x.id === id ? { ...x, read: true } : x));
      setUnreadCount(c => Math.max(0, c - 1));
    } catch (e) { console.warn('API:', e); }
  }, [token]);

  const markAllRead = useCallback(async () => {
    if (!token) return;
    try {
      await notifApi.markAllRead(token);
      setNotifications(list => list.map(n => ({ ...n, read: true })));
      setUnreadCount(0);
    } catch (e) { console.warn('API:', e); }
  }, [token]);

  // 注意：setClickCb 直接传函数会被 React 当作 updater 执行，必须包一层
  const setOnNotifClick = useCallback((cb: (n: Notification) => Promise<void>) => {
    setClickCb(() => cb);
  }, []);

  const onNotifClick = useCallback(async (n: Notification) => {
    if (!n.read) await markRead(n.id);
    setShowNotif(false);
    await clickCb(n);
  }, [markRead, clickCb]);

  return (
    <NotificationContext.Provider value={{
      notifications, unreadCount, showNotif, setShowNotif,
      markRead, markAllRead, onNotifClick, setOnNotifClick,
    }}>
      {children}
    </NotificationContext.Provider>
  );
}

export function useNotifications(): NotifState {
  const ctx = useContext(NotificationContext);
  if (!ctx) throw new Error('useNotifications 必须在 NotificationProvider 内使用');
  return ctx;
}

/** 通知类型对应图标 */
export const notifIcon = (k: string): string =>
  k === 'REPLY' ? '↩' : k === 'UPVOTE' ? '↑' : k === 'MENTION' ? '@' : k === 'FEATURED' ? '★' : k === 'FOLLOW' ? '✚' : '•';
