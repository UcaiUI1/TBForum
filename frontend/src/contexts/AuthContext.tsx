// 认证上下文：集中管理 token/user/verified/isAdmin，提供登录/登出/刷新能力
import React, { createContext, useContext, useEffect, useState, useCallback, useRef } from 'react';
import { getToken, getUser, setToken as persistToken, setUser as persistUser, clearAuth, authApi } from '../services/api';

type AuthState = {
  token: string;
  user: string;
  verified: boolean;
  isAdmin: boolean;
  login: (token: string, username: string) => void;
  logout: () => void;
  setVerified: (v: boolean) => void;
  refreshIdentity: () => void;
};

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [token, setToken] = useState(getToken());
  const [user, setUser] = useState(getUser());
  const [verified, setVerified] = useState(false);
  const [isAdmin, setIsAdmin] = useState(false);
  // auth-expired 事件触发的登出回调引用（避免闭包陈旧）
  const logoutRef = useRef<() => void>(() => {});

  const logout = useCallback(() => {
    clearAuth();
    setToken('');
    setUser('');
    setVerified(false);
    setIsAdmin(false);
  }, []);

  const login = useCallback((t: string, u: string) => {
    persistToken(t);
    persistUser(u);
    setToken(t);
    setUser(u);
  }, []);

  // 拉取当前用户身份（verified/role）
  const refreshIdentity = useCallback(() => {
    if (!token) return;
    authApi.me(token).then(d => { setVerified(d.verified); setIsAdmin(d.role === 'ADMIN'); }).catch(e => console.warn('API:', e));
  }, [token]);

  // token 变化时拉取身份
  useEffect(() => { refreshIdentity(); }, [token]);

  // JWT 过期自动刷新（提前 5 分钟）
  useEffect(() => {
    if (!token) return;
    try {
      const p = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
      const exp = (p.exp || 0) * 1000;
      const ms = exp - Date.now();
      if (ms <= 0) { logout(); return; }
      const refreshIn = ms <= 60000 ? 0 : Math.max(Math.min(ms - 5 * 60 * 1000, ms / 2), 30000);
      const t = window.setTimeout(() => {
        authApi.refresh(token).then(r => { persistToken(r.token); setToken(r.token); }).catch(e => { console.warn('API:', e); logout(); });
      }, refreshIn);
      return () => clearTimeout(t);
    } catch (e) { console.warn('token:', e); }
  }, [token, logout]);

  // 监听 401 失效事件
  useEffect(() => {
    const h = () => logout();
    window.addEventListener('tb-auth-expired', h);
    return () => window.removeEventListener('tb-auth-expired', h);
  }, [logout]);

  logoutRef.current = logout;

  return (
    <AuthContext.Provider value={{ token, user, verified, isAdmin, login, logout, setVerified, refreshIdentity }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth 必须在 AuthProvider 内使用');
  return ctx;
}
