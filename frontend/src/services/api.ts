// API 服务层：集中管理 fetch 请求、token 持久化与 401 失效广播
import type { Forum, Post, Comment, Page, TagView, UserView, UserProfile, Notification, ReportReasons, ReportView, ReportFilter } from '../types';

const TOKEN_KEY = 'tb-token';
const USER_KEY = 'tb-user';

/** 读取本地持久化的 token */
export const getToken = (): string => localStorage.getItem(TOKEN_KEY) || '';
/** 读取本地持久化的用户名 */
export const getUser = (): string => localStorage.getItem(USER_KEY) || '';
/** 持久化 token */
export const setToken = (token: string): void => { localStorage.setItem(TOKEN_KEY, token); };
/** 持久化用户名 */
export const setUser = (username: string): void => { localStorage.setItem(USER_KEY, username); };
/** 清除登录态 */
export const clearAuth = (): void => { localStorage.removeItem(TOKEN_KEY); localStorage.removeItem(USER_KEY); };

/**
 * 统一 fetch 封装：自动注入 JSON 头与 Authorization，401 时清除登录态并广播事件。
 * 业务层调用 api<T>(path, options, token?)，返回解析后的 JSON。
 */
export async function api<T>(path: string, options: RequestInit = {}, token = ''): Promise<T> {
  const res = await fetch(path, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(options.headers || {}),
    },
  });
  if (!res.ok) {
    const msg = (await res.json().catch(() => ({ message: '请求失败' }))).message || `请求失败 (${res.status})`;
    if (res.status === 401 && token) {
      clearAuth();
      window.dispatchEvent(new Event('tb-auth-expired'));
    }
    throw new Error(msg);
  }
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** 文件上传（FormData，跳过 JSON Content-Type 由浏览器自动设置 boundary） */
export async function uploadFile(file: File, token: string): Promise<{ url: string }> {
  const fd = new FormData();
  fd.append('file', file);
  const res = await fetch('/api/upload', { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: fd });
  if (res.status === 401) {
    clearAuth();
    window.dispatchEvent(new Event('tb-auth-expired'));
    throw new Error('登录已失效');
  }
  if (!res.ok) throw new Error((await res.json().catch(() => ({ message: '上传失败' }))).message || '上传失败');
  return res.json();
}

// ===== 认证 =====
export const authApi = {
  register: (username: string, password: string) =>
    api<{ token: string; username: string }>('/api/auth/register', { method: 'POST', body: JSON.stringify({ username, password }) }),
  login: (username: string, password: string) =>
    api<{ token: string; username: string }>('/api/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) }),
  refresh: (token: string) =>
    api<{ token: string; username: string }>('/api/auth/refresh', { method: 'POST' }, token),
  me: (token: string) =>
    api<{ verified: boolean; role?: string }>('/api/auth/me', {}, token),
  verify: (token: string) =>
    api('/api/auth/me/verified', { method: 'PATCH' }, token),
  updateProfile: (token: string, body: { username: string; avatarUrl: string; bio: string; signature: string }) =>
    api<{ token: string | null; user: UserProfile }>('/api/auth/me', { method: 'PATCH', body: JSON.stringify(body) }, token),
};

// ===== 版块 =====
export const forumApi = {
  list: () => api<Page<Forum>>('/api/forums?size=50'),
  create: (token: string, body: { name: string; slug: string; description: string }) =>
    api<Forum>('/api/forums', { method: 'POST', body: JSON.stringify(body) }, token),
  moderators: (slug: string) => api<UserView[]>(`/api/forums/${slug}/moderators`),
  removeModerator: (token: string, slug: string, username: string) =>
    api(`/api/forums/${slug}/moderators/${encodeURIComponent(username)}`, { method: 'DELETE' }, token),
  saveReportReasons: (token: string, slug: string, reason1: string, reason2: string) =>
    api<Forum>(`/api/forums/${slug}/report-reasons`, { method: 'PATCH', body: JSON.stringify({ reason1, reason2 }) }, token),
  reportReasons: (slug: string) => api<ReportReasons>(`/api/forums/${slug}/report-reasons`),
};

// ===== 帖子 =====
export const postApi = {
  feed: (order: string, page: number, seed: string) =>
    api<Page<Post>>(`/api/posts/feed?order=${order}&size=10&page=${page}${order === 'fate' ? `&seed=${seed}` : ''}`),
  byForum: (slug: string, order: string, page: number, seed: string) =>
    api<Page<Post>>(`/api/forums/${slug}/posts?order=${order}&size=10&page=${page}${order === 'fate' ? `&seed=${seed}` : ''}`),
  byTag: (tag: string, order: string, page: number, seed: string) =>
    api<Page<Post>>(`/api/tags/${encodeURIComponent(tag)}/posts?order=${order}&size=10&page=${page}${order === 'fate' ? `&seed=${seed}` : ''}`),
  search: (q: string) => api<Page<Post>>(`/api/search?q=${encodeURIComponent(q)}&size=10&page=0`),
  detail: (id: number) => api<Post>(`/api/posts/${id}`),
  create: (token: string, slug: string, body: { title: string; content: string }) =>
    api<Post>(`/api/forums/${slug}/posts`, { method: 'POST', body: JSON.stringify(body) }, token),
  edit: (token: string, id: number, body: { title: string; content: string }) =>
    api<Post>(`/api/posts/${id}`, { method: 'PATCH', body: JSON.stringify(body) }, token),
  delete: (token: string, id: number) =>
    api(`/api/posts/${id}`, { method: 'DELETE' }, token),
  interact: (token: string, id: number, kind: string) =>
    api<Post>(`/api/posts/${id}/interactions`, { method: 'POST', body: JSON.stringify({ kind }) }, token),
  favorite: (token: string, id: number, fav: boolean) =>
    api<Post>(`/api/posts/${id}/favorite`, { method: fav ? 'POST' : 'DELETE' }, token),
  feature: (token: string, id: number, value: boolean) =>
    api<Post>(`/api/posts/${id}/featured?value=${value}`, { method: 'PATCH' }, token),
  lock: (token: string, id: number, value: boolean) =>
    api<Post>(`/api/posts/${id}/locked?value=${value}`, { method: 'PATCH' }, token),
  userPosts: (username: string) =>
    api<Page<Post>>(`/api/users/${encodeURIComponent(username)}/posts?size=20`),
  myFavorites: (token: string) =>
    api<Page<Post>>('/api/users/me/favorites?size=20', {}, token),
};

// ===== 评论 =====
export const commentApi = {
  tree: (postId: number) => api<Page<Comment>>(`/api/posts/${postId}/comments`),
  create: (token: string, postId: number, body: { content: string; parentId?: number }) =>
    api(`/api/posts/${postId}/comments`, { method: 'POST', body: JSON.stringify(body) }, token),
  edit: (token: string, postId: number, commentId: number, content: string) =>
    api(`/api/posts/${postId}/comments/${commentId}`, { method: 'PATCH', body: JSON.stringify({ content }) }, token),
  pin: (token: string, postId: number, commentId: number, value: boolean) =>
    api(`/api/posts/${postId}/comments/${commentId}/pinned?value=${value}`, { method: 'PATCH' }, token),
};

// ===== 用户 =====
export const userApi = {
  profile: (username: string, token: string) =>
    api<UserProfile>(`/api/users/${encodeURIComponent(username)}`, {}, token),
  following: (username: string, token: string) =>
    api<UserView[]>(`/api/users/${encodeURIComponent(username)}/following`, {}, token),
  followers: (username: string, token: string) =>
    api<UserView[]>(`/api/users/${encodeURIComponent(username)}/followers`, {}, token),
  search: (q: string, token: string) =>
    api<UserView[]>(`/api/users/search?q=${encodeURIComponent(q)}`, {}, token),
  follow: (token: string, username: string, follow: boolean) =>
    api(`/api/users/${encodeURIComponent(username)}/follow`, { method: follow ? 'POST' : 'DELETE' }, token),
};

// ===== 通知 =====
export const notifApi = {
  list: (token: string) => api<Notification[]>('/api/notifications', {}, token),
  unreadCount: (token: string) => api<{ count: number }>('/api/notifications/unread-count', {}, token),
  markRead: (token: string, id: number) => api(`/api/notifications/${id}/read`, { method: 'PATCH' }, token),
  markAllRead: (token: string) => api('/api/notifications/read-all', { method: 'PATCH' }, token),
};

// ===== 标签 =====
export const tagApi = {
  popular: () => api<TagView[]>('/api/tags'),
};

// ===== 举报 =====
export const reportApi = {
  submit: (token: string, body: { targetType: 'POST' | 'COMMENT'; targetId: number; reason: string }) =>
    api('/api/reports', { method: 'POST', body: JSON.stringify(body) }, token),
  list: (token: string, filter: ReportFilter, page: number) =>
    api<Page<ReportView>>(filter === 'ALL' ? `/api/admin/reports?page=${page}&size=15` : `/api/admin/reports?status=${filter}&page=${page}&size=15`, {}, token),
  handle: (token: string, id: number, status: 'RESOLVED' | 'DISMISSED') =>
    api(`/api/admin/reports/${id}?status=${status}`, { method: 'PATCH' }, token),
  counts: (token: string) =>
    api<{ total: number; pending: number }>('/api/admin/reports/count', {}, token),
};
