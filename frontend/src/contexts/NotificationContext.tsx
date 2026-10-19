// 通知以数据库快照为准，实时消息负责及时提示；重连、定时同步和历史分页负责补偿。
import React, { createContext, useContext, useEffect, useRef, useState, useCallback } from 'react';
import type { Notification } from '../types';
import { notifApi } from '../services/api';
import { mergeNotifications } from '../services/notificationState';
import { useAuth } from './AuthContext';

type NotifState = {
  notifications: Notification[]; unreadCount: number; showNotif: boolean;
  setShowNotif: (v: boolean) => void;
  markRead: (id: number) => Promise<void>; markAllRead: () => Promise<void>;
  onNotifClick: (n: Notification) => Promise<void>;
  setOnNotifClick: (cb: (n: Notification) => Promise<void>) => void;
  hasMore: boolean; loadingMore: boolean; syncError: string; loadMore: () => Promise<void>; retrySync: () => void;
};

const NotificationContext = createContext<NotifState | null>(null);

export function NotificationProvider({ children }: { children: React.ReactNode }) {
  const { token, logout } = useAuth();
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const [showNotif, setShowNotif] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [syncError, setSyncError] = useState('');
  const [clickCb, setClickCb] = useState<(n: Notification) => Promise<void>>(async () => {});
  const currentToken = useRef(token);
  currentToken.current = token;
  const epoch = useRef(0);
  const nextBefore = useRef<number | null>(null);
  const paged = useRef(false);
  const morePending = useRef(false);
  const requestSync = useRef<() => void>(() => {});

  useEffect(() => {
    const generation = ++epoch.current;
    setNotifications([]); setUnreadCount(0); setHasMore(false); setSyncError(''); setLoadingMore(false);
    nextBefore.current = null; paged.current = false; morePending.current = false;
    if (!token) return;
    let closed = false;
    let ws: WebSocket | null = null;
    let reconnect: number | undefined;
    let debounce: number | undefined;
    let retryCount = 0;
    let syncing = false;
    let dirty = false;
    let revision = 0;
    const valid = () => !closed && generation === epoch.current && currentToken.current === token;

    const sync = async () => {
      if (!valid()) return;
      if (syncing) { dirty = true; return; }
      syncing = true;
      dirty = false;
      const started = revision;
      try {
        const page = await notifApi.history(token);
        if (!valid()) return;
        if (revision !== started) dirty = true;
        setNotifications(list => {
          const merged = mergeNotifications(list, page.content);
          return page.unreadCount === 0 ? merged.map(item => ({ ...item, read: true })) : merged;
        });
        setUnreadCount(page.unreadCount);
        if (!paged.current) { nextBefore.current = page.nextBefore; setHasMore(page.hasMore); }
        setSyncError('');
      } catch {
        if (valid()) setSyncError('通知同步失败，请重试');
      } finally {
        syncing = false;
        if (dirty && valid() && debounce == null) debounce = window.setTimeout(() => { debounce = undefined; void sync(); }, 200);
      }
    };
    const scheduleSync = () => {
      revision++;
      if (debounce == null) debounce = window.setTimeout(() => { debounce = undefined; void sync(); }, 200);
    };
    requestSync.current = scheduleSync;
    const connect = () => {
      if (!valid()) return;
      ws = new WebSocket(`${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws/notifications`, [`bearer.${token}`]);
      ws.onopen = () => { retryCount = 0; scheduleSync(); };
      ws.onmessage = event => {
        if (!valid()) return;
        try {
          const value = JSON.parse(event.data);
          if (value && typeof value.id === 'number') {
            setNotifications(list => mergeNotifications(list, [value as Notification]));
            scheduleSync();
          }
        } catch { /* The next snapshot repairs malformed realtime hints. */ }
      };
      ws.onclose = event => {
        if (!valid()) return;
        if (event.code === 1008) { logout(); return; }
        const delay = Math.min(3000 * 2 ** retryCount++, 30000);
        reconnect = window.setTimeout(connect, delay);
      };
      ws.onerror = () => ws?.close();
    };
    void sync();
    connect();
    const interval = window.setInterval(() => { if (!document.hidden) void sync(); }, 30000);
    const focus = () => { if (!document.hidden) scheduleSync(); };
    document.addEventListener('visibilitychange', focus);
    window.addEventListener('focus', focus);
    return () => {
      closed = true; requestSync.current = () => {};
      window.clearTimeout(reconnect); window.clearTimeout(debounce); window.clearInterval(interval);
      document.removeEventListener('visibilitychange', focus); window.removeEventListener('focus', focus);
      ws?.close();
    };
  }, [token, logout]);

  const loadMore = useCallback(async () => {
    if (!token || nextBefore.current == null || morePending.current) return;
    const generation = epoch.current;
    morePending.current = true; setLoadingMore(true);
    try {
      const page = await notifApi.history(token, nextBefore.current);
      if (generation !== epoch.current || currentToken.current !== token) return;
      setNotifications(list => mergeNotifications(list, page.content));
      nextBefore.current = page.nextBefore; paged.current = true; setHasMore(page.hasMore); setSyncError('');
    } catch {
      if (generation === epoch.current) setSyncError('通知加载失败，请重试');
    } finally {
      if (generation === epoch.current) { morePending.current = false; setLoadingMore(false); }
    }
  }, [token]);

  const markRead = useCallback(async (id: number) => {
    if (!token) return;
    const generation = epoch.current;
    try {
      await notifApi.markRead(token, id);
      if (generation !== epoch.current || currentToken.current !== token) return;
      setNotifications(list => list.map(item => item.id === id ? { ...item, read: true } : item));
      requestSync.current();
    } catch { if (generation === epoch.current) setSyncError('标记已读失败，请重试'); }
  }, [token]);

  const markAllRead = useCallback(async () => {
    if (!token) return;
    const generation = epoch.current;
    try {
      await notifApi.markAllRead(token);
      if (generation !== epoch.current || currentToken.current !== token) return;
      setNotifications(list => list.map(item => ({ ...item, read: true })));
      requestSync.current();
    } catch { if (generation === epoch.current) setSyncError('标记已读失败，请重试'); }
  }, [token]);
  const setOnNotifClick = useCallback((cb: (n: Notification) => Promise<void>) => setClickCb(() => cb), []);
  const onNotifClick = useCallback(async (notification: Notification) => {
    if (!notification.read) await markRead(notification.id);
    setShowNotif(false); await clickCb(notification);
  }, [markRead, clickCb]);

  return <NotificationContext.Provider value={{ notifications, unreadCount, showNotif, setShowNotif,
    markRead, markAllRead, onNotifClick, setOnNotifClick, hasMore, loadingMore, syncError, loadMore,
    retrySync: () => requestSync.current() }}>{children}</NotificationContext.Provider>;
}

export function useNotifications(): NotifState {
  const ctx = useContext(NotificationContext);
  if (!ctx) throw new Error('useNotifications 必须在 NotificationProvider 内使用');
  return ctx;
}

export const notifIcon = (k: string): string =>
  k === 'REPLY' ? '↩' : k === 'UPVOTE' ? '↑' : k === 'MENTION' ? '@' : k === 'FEATURED' ? '★' : k === 'FOLLOW' ? '✚' : '•';
