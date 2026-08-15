// 应用主组件：组装顶栏、侧边栏、主内容区与各弹窗；hash 路由实现可分享深链。
import React, { FormEvent, Suspense, lazy, useEffect, useMemo, useRef, useState, useCallback } from 'react';
import type { Post, Forum, Comment, UserProfile, ModalType, ReportFilter, ReportView, AuditLogView } from './types';
import { useAuth } from './contexts/AuthContext';
import { useNotifications } from './contexts/NotificationContext';
import {
  authApi, forumApi, postApi, commentApi, userApi, reportApi, adminApi,
} from './services/api';
import { useForums, usePopularTags } from './hooks/useForums';
import { usePosts, useSearch } from './hooks/usePosts';
import { usePostDetail, useComments } from './hooks/usePostDetail';
import { useUserProfile, useUserPosts, useUserFollowList, useMyFavorites } from './hooks/useUserProfile';
import { useReportState } from './hooks/useReportState';
import { useModState } from './hooks/useModState';
import { useAdminState } from './hooks/useAdminState';
import { useQueryClient } from '@tanstack/react-query';

import { Header } from './components/Header';
import { Sidebar } from './components/Sidebar';
import { PostList } from './components/PostList';
import { PostDetail } from './components/PostDetail';
import { ProfileView } from './components/ProfileView';
import { Modal } from './components/Modal';
import { ConfirmModal } from './components/ConfirmModal';
import { ContextMenu } from './components/ContextMenu';
import { ReportModal } from './components/ReportModal';

// 代码分割：管理面板懒加载，减小首屏体积
const ModPanel = lazy(() => import('./components/ModPanel').then(m => ({ default: m.ModPanel })));
const AdminPanel = lazy(() => import('./components/AdminPanel').then(m => ({ default: m.AdminPanel })));

const PAGE_SIZE = 10;
const DRAFT_KEY = 'tb-post-draft';

type Route =
  | { kind: 'global' }
  | { kind: 'forum'; slug: string }
  | { kind: 'tag'; name: string }
  | { kind: 'post'; id: number }
  | { kind: 'user'; username: string }
  | { kind: 'profile' };

function parseHash(): Route | null {
  const h = window.location.hash.replace(/^#\/?/, '');
  if (!h) return null;
  const parts = h.split('/').map(decodeURIComponent);
  switch (parts[0]) {
    case 'global': return { kind: 'global' };
    case 'forum': return parts[1] ? { kind: 'forum', slug: parts[1] } : null;
    case 'tag': return parts[1] ? { kind: 'tag', name: parts[1] } : null;
    case 'post': {
      const id = Number(parts[1]);
      return Number.isInteger(id) && id > 0 ? { kind: 'post', id } : null;
    }
    case 'u': return parts[1] ? { kind: 'user', username: parts[1] } : null;
    case 'profile': return { kind: 'profile' };
    default: return null;
  }
}

function loadDraft(): { title: string; content: string } {
  try {
    const raw = localStorage.getItem(DRAFT_KEY);
    if (raw) {
      const d = JSON.parse(raw);
      if (typeof d.title === 'string' && typeof d.content === 'string') return { title: d.title, content: d.content };
    }
  } catch { /* ignore */ }
  return { title: '', content: '' };
}

export function App() {
  const { token, user, verified, isAdmin, login, logout, setVerified } = useAuth();
  const { setOnNotifClick } = useNotifications();
  const qc = useQueryClient();

  // 导航与列表状态
  const [active, setActive] = useState<Forum | null>(null);
  const [globalMode, setGlobalMode] = useState(false);
  const [activeTag, setActiveTag] = useState<string | null>(null);
  const [pendingSlug, setPendingSlug] = useState<string | null>(null);
  const [order, setOrder] = useState('latest');
  const [page, setPage] = useState(0);
  const [seed, setSeed] = useState(() => String(Math.floor(Math.random() * (2 ** 31 - 1))));
  const [query, setQuery] = useState('');
  const [searching, setSearching] = useState(false);

  // 详情/弹窗状态
  const [viewPostId, setViewPostId] = useState<number | null>(null);
  const [replyTo, setReplyTo] = useState<number | null>(null);
  const [commentContent, setCommentContent] = useState('');
  const [editPost, setEditPost] = useState(false);
  const [editPostTitle, setEditPostTitle] = useState('');
  const [editPostContent, setEditPostContent] = useState('');
  const [confirmDel, setConfirmDel] = useState<Post | null>(null);
  const [ctxMenu, setCtxMenu] = useState<{ x: number; y: number; p: Post } | null>(null);
  const [modal, setModal] = useState<ModalType | null>(null);
  const [postForm, setPostForm] = useState({ title: '', content: '' });
  const [notice, setNotice] = useState('');
  const noticeTimer = useRef<number | undefined>(undefined);
  const [navOpen, setNavOpen] = useState(false);
  const [theme, setTheme] = useState<'dark' | 'light'>(() => (localStorage.getItem('tb-theme') as 'dark' | 'light') || 'dark');
  const [pendingCommentId, setPendingCommentId] = useState<number | null>(null);
  const [modReports, setModReports] = useState<ReportView[]>([]);
  const [modReportPending, setModReportPending] = useState(0);
  const [adminAudit, setAdminAudit] = useState<AuditLogView[]>([]);
  const [adminAuditPage, setAdminAuditPage] = useState(0);
  const [adminAuditTotalPages, setAdminAuditTotalPages] = useState(1);

  // 个人中心/用户主页状态
  const [viewProfile, setViewProfile] = useState(false);
  const [profileForm, setProfileForm] = useState<UserProfile>({ id: 0, username: '', verified: false, avatarUrl: '', bio: '', signature: '', following: 0, followers: 0, isFollowing: false, isSelf: false, mutual: false });
  const [profileTab, setProfileTab] = useState<'info' | 'posts' | 'favorites' | 'following' | 'followers'>('info');
  const [viewUser, setViewUser] = useState<UserProfile | null>(null);
  const [userTab, setUserTab] = useState<'posts' | 'following' | 'followers'>('posts');

  // 版主信息
  const [mods, setMods] = useState<string[]>([]);

  // 数据查询
  const forumsQuery = useForums();
  const forums = forumsQuery.data ?? [];
  const popularTags = usePopularTags().data ?? [];
  const postSource = activeTag ? { kind: 'tag' as const, name: activeTag } : globalMode ? { kind: 'global' as const } : active ? { kind: 'forum' as const, slug: active.slug } : null;
  const postsQuery = usePosts(postSource ?? { kind: 'global' }, order, page, seed);
  const posts = postsQuery.data?.content ?? [];
  const totalPages = postsQuery.data ? Math.ceil(postsQuery.data.totalElements / PAGE_SIZE) : 0;
  const searchQuery = useSearch(query, searching, page);
  const displayedPosts = searching ? (searchQuery.data?.content ?? []) : posts;
  const displayedTotalPages = searching ? (searchQuery.data ? Math.ceil(searchQuery.data.totalElements / PAGE_SIZE) : 0) : totalPages;

  const postDetailQuery = usePostDetail(viewPostId, token);
  const viewPost = viewPostId ? (postDetailQuery.data ?? null) : null;
  const commentsQuery = useComments(viewPostId);
  const comments = commentsQuery.query.data?.pages.flatMap(p => p.content) ?? [];

  const profilePosts = useUserPosts(viewProfile ? user : null).data ?? [];
  const profileFavPosts = useMyFavorites(token).data ?? [];
  const profileFollowList = useUserFollowList(viewProfile ? user : null, profileTab === 'following' ? 'following' : 'followers', token).data ?? [];
  const userPosts = useUserPosts(viewUser?.username ?? null).data ?? [];
  const userFollowList = useUserFollowList(viewUser?.username ?? null, userTab === 'following' ? 'following' : 'followers', token).data ?? [];

  // 聚合状态（useReducer）
  const report = useReportState();
  const mod = useModState();
  const admin = useAdminState();

  const showError = useCallback((e: unknown) => {
    setNotice(e instanceof Error ? e.message : '发生未知错误');
    if (noticeTimer.current) clearTimeout(noticeTimer.current);
    noticeTimer.current = window.setTimeout(() => setNotice(''), 3500);
  }, []);

  const notify = useCallback((msg: string) => {
    setNotice(msg);
    if (noticeTimer.current) clearTimeout(noticeTimer.current);
    noticeTimer.current = window.setTimeout(() => setNotice(''), 3500);
  }, []);

  // ===== hash 路由 =====
  const navigate = useCallback((hash: string) => {
    setNavOpen(false);
    if (window.location.hash !== hash) window.location.hash = hash;
  }, []);

  // 主题：写入 html[data-theme] 并持久化
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try { localStorage.setItem('tb-theme', theme); } catch { /* ignore */ }
  }, [theme]);

  const listHash = useCallback(() => {
    if (activeTag) return `#/tag/${encodeURIComponent(activeTag)}`;
    if (globalMode) return '#/global';
    if (active) return `#/forum/${active.slug}`;
    return '#/';
  }, [activeTag, globalMode, active]);

  const showProfile = useCallback(() => {
    if (!user) return;
    setViewUser(null);
    setViewPostId(null);
    setViewProfile(true);
    setProfileTab('info');
    userApi.profile(user, token).then(d => setProfileForm(d)).catch(showError);
  }, [user, token, showError]);

  const showUser = useCallback((username: string) => {
    setViewProfile(false);
    setViewPostId(null);
    setUserTab('posts');
    userApi.profile(username, token).then(d => setViewUser(d)).catch(showError);
  }, [token, showError]);

  const applyRoute = useCallback((r: Route | null) => {
    if (!r) return;
    setSearching(false);
    switch (r.kind) {
      case 'global':
        setGlobalMode(true); setActive(null); setActiveTag(null); setViewPostId(null); setViewProfile(false); setViewUser(null); setEditPost(false); setPage(0); setOrder('latest');
        break;
      case 'forum': {
        setGlobalMode(false); setActiveTag(null); setViewPostId(null); setViewProfile(false); setViewUser(null); setEditPost(false); setPage(0); setOrder('latest');
        const f = forums.find(x => x.slug === r.slug);
        if (f) setActive(f); else setPendingSlug(r.slug);
        break;
      }
      case 'tag':
        setActiveTag(r.name); setGlobalMode(false); setActive(null); setViewPostId(null); setViewProfile(false); setViewUser(null); setEditPost(false); setPage(0); setOrder('latest');
        break;
      case 'post':
        setViewPostId(r.id); setViewProfile(false); setViewUser(null); setReplyTo(null); setEditPost(false); setCommentContent('');
        break;
      case 'user':
        showUser(r.username);
        break;
      case 'profile':
        showProfile();
        break;
    }
  }, [forums, showProfile, showUser]);

  const applyRouteRef = useRef(applyRoute);
  applyRouteRef.current = applyRoute;

  // 首次挂载 + hash 变化时应用路由
  useEffect(() => {
    const onHash = () => applyRouteRef.current(parseHash());
    onHash();
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  // 版块数据到达后解析路由中的 slug
  useEffect(() => {
    if (pendingSlug && forums.length) {
      const f = forums.find(x => x.slug === pendingSlug);
      if (f) { setActive(f); setGlobalMode(false); setActiveTag(null); setViewPostId(null); setViewProfile(false); setViewUser(null); }
      setPendingSlug(null);
    }
  }, [pendingSlug, forums]);

  // 通知点击 → 跳转帖子
  useEffect(() => {
    setOnNotifClick(async (n) => {
      if (n.postId) { setSearching(false); setPendingCommentId(n.commentId ?? null); navigate(`#/post/${n.postId}`); }
    });
  }, [setOnNotifClick, navigate]);

  // 通知定位：评论加载后滚动到对应评论并高亮
  useEffect(() => {
    if (!pendingCommentId || !viewPostId) return;
    const t = window.setTimeout(() => {
      const el = document.querySelector(`[data-comment-id="${pendingCommentId}"]`);
      if (el) {
        el.scrollIntoView({ behavior: 'smooth', block: 'center' });
        el.classList.add('comment-flash');
        window.setTimeout(() => el.classList.remove('comment-flash'), 2200);
      }
      setPendingCommentId(null);
    }, 700);
    return () => clearTimeout(t);
  }, [pendingCommentId, viewPostId, comments.length]);

  // 右键菜单外部点击关闭
  useEffect(() => {
    if (!ctxMenu) return;
    const h = (e: Event) => {
      const el = document.querySelector('.ctx-menu');
      if (el && !el.contains(e.target as Node)) setCtxMenu(null);
    };
    document.addEventListener('mousedown', h);
    window.addEventListener('scroll', h);
    window.addEventListener('resize', h);
    return () => { document.removeEventListener('mousedown', h); window.removeEventListener('scroll', h); window.removeEventListener('resize', h); };
  }, [ctxMenu]);

  // 初始化版块（无 hash 时默认第一个）并规范化 URL
  useEffect(() => {
    if (forums.length && !active && !globalMode && !activeTag) {
      setActive(forums[0]);
      if (!window.location.hash) history.replaceState(null, '', `#/forum/${forums[0].slug}`);
    }
  }, [forums, active, globalMode, activeTag]);

  // 拉取版主列表
  useEffect(() => {
    if (!active) return;
    forumApi.moderators(active.slug).then(r => setMods(r.map(m => m.username))).catch(e => { console.warn('API:', e); setMods([]); });
  }, [active?.slug]);

  // 发帖草稿自动保存
  useEffect(() => {
    if (!modal) return;
    const t = window.setTimeout(() => {
      try { localStorage.setItem(DRAFT_KEY, JSON.stringify(postForm)); } catch { /* ignore */ }
    }, 400);
    return () => clearTimeout(t);
  }, [postForm, modal]);

  // 导航
  const enterGlobal = () => navigate('#/global');
  const enterForum = (f: Forum) => navigate(`#/forum/${f.slug}`);
  const enterTag = (tag: string) => navigate(`#/tag/${encodeURIComponent(tag)}`);
  const openPost = (p: Post) => navigate(`#/post/${p.id}`);
  const openUser = (username: string) => navigate(`#/u/${encodeURIComponent(username)}`);
  const openProfile = () => navigate('#/profile');
  const closePost = () => navigate(listHash());
  const exitSearch = () => { setSearching(false); setQuery(''); navigate(listHash()); };

  const isModerator = !!active && (active.owner.username === user || mods.includes(user));
  const canDelete = (p: Post) => !!token && (p.author === user || isModerator);

  // 认证
  async function submitAuth(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    const registering = modal === 'register';
    try {
      const r = await authApi[registering ? 'register' : 'login'](String(f.get('username')), String(f.get('password')));
      login(r.token, r.username);
      setModal(null);
      notify(registering ? `注册成功，欢迎加入 ${r.username}` : `欢迎回来，${r.username}`);
    } catch (err) { showError(err); }
  }

  async function doVerify() {
    try {
      await authApi.verify(token);
      setVerified(true);
      notify('实名认证已完成');
    } catch (err) { showError(err); }
  }

  // 帖子操作
  function openPostModal() {
    if (!token) { setModal('login'); return; }
    if (!active) { notify('请先创建一个版块'); setModal('forum'); return; }
    setPostForm(loadDraft());
    setModal('post');
  }

  async function submitPost(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!active) return;
    try {
      await postApi.create(token, active.slug, postForm);
      setModal(null);
      setPostForm({ title: '', content: '' });
      try { localStorage.removeItem(DRAFT_KEY); } catch { /* ignore */ }
      postsQuery.refetch();
      notify('帖子已发布');
    } catch (err) { showError(err); }
  }

  async function submitForum(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    try {
      await forumApi.create(token, { name: String(f.get('name')), slug: String(f.get('slug')), description: String(f.get('description')) });
      setModal(null);
      forumsQuery.refetch();
      notify('版块已创建');
    } catch (err) { showError(err); }
  }

  async function vote(p: Post) {
    if (!token) { setModal('login'); return; }
    try {
      await postApi.interact(token, p.id, 'UP');
      postsQuery.refetch();
      if (viewPostId === p.id) postDetailQuery.refetch();
    } catch (err) { showError(err); }
  }

  async function doDelete() {
    if (!confirmDel) return;
    try {
      const id = confirmDel.id;
      setConfirmDel(null);
      await postApi.delete(token, id);
      if (viewPostId === id) { setViewPostId(null); navigate(listHash()); }
      postsQuery.refetch();
      notify('帖子已删除');
    } catch (err) { showError(err); }
  }

  async function toggleFeature(p: Post) {
    if (!token) { setModal('login'); return; }
    try {
      await postApi.feature(token, p.id, !p.featured);
      postsQuery.refetch();
      if (viewPostId === p.id) postDetailQuery.refetch();
    } catch (err) { showError(err); }
  }

  async function toggleLock(p: Post) {
    if (!token) { setModal('login'); return; }
    try {
      const updated = await postApi.lock(token, p.id, !p.locked);
      postsQuery.refetch();
      if (viewPostId === p.id) postDetailQuery.refetch();
      notify(updated.locked ? '帖子已锁定' : '帖子已解锁');
    } catch (err) { showError(err); }
  }

  async function toggleFavorite(p: Post) {
    if (!token) { setModal('login'); return; }
    try {
      await postApi.favorite(token, p.id, !p.favorited);
      qc.invalidateQueries({ queryKey: ['user', 'me', 'favorites'] });
      postsQuery.refetch();
      if (viewPostId === p.id) postDetailQuery.refetch();
    } catch (err) { showError(err); }
  }

  const onPostContext = (e: React.MouseEvent, p: Post) => {
    if (!token || (!canDelete(p) && !isModerator)) return;
    e.preventDefault();
    e.stopPropagation();
    setCtxMenu({ x: e.clientX, y: e.clientY, p });
  };

  const closePostView = () => { closePost(); };
  const startEditPost = () => {
    if (!viewPost) return;
    setEditPostTitle(viewPost.title);
    setEditPostContent(viewPost.content);
    setEditPost(true);
  };

  async function saveEditPost() {
    if (!viewPost) return;
    try {
      await postApi.edit(token, viewPost.id, { title: editPostTitle, content: editPostContent });
      postDetailQuery.refetch();
      setEditPost(false);
      notify('帖子已更新');
    } catch (err) { showError(err); }
  }

  async function submitComment(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!viewPost) return;
    if (!token) { setModal('login'); return; }
    if (!commentContent.trim()) return;
    const body: { content: string; parentId?: number } = { content: commentContent };
    if (replyTo != null) body.parentId = replyTo;
    try {
      await commentApi.create(token, viewPost.id, body);
      setReplyTo(null);
      setCommentContent('');
      commentsQuery.invalidate();
      postDetailQuery.refetch();
      notify('评论已发表');
    } catch (err) { showError(err); }
  }

  async function togglePinComment(c: Comment) {
    if (!token || !viewPost) return;
    try {
      await commentApi.pin(token, viewPost.id, c.id, !c.pinned);
      commentsQuery.invalidate();
      notify(c.pinned ? '已取消置顶' : '评论已置顶');
    } catch (err) { showError(err); }
  }

  async function deleteComment(c: Comment) {
    if (!token || !viewPost) return;
    if (!window.confirm('确定删除这条评论吗？')) return;
    try {
      await commentApi.remove(token, viewPost.id, c.id);
      commentsQuery.invalidate();
      postDetailQuery.refetch();
      notify('评论已删除');
    } catch (err) { showError(err); }
  }

  // 搜索（游客可用，后端 /api/search 已 permitAll）
  function search(e: FormEvent) {
    e.preventDefault();
    if (!query.trim()) return;
    setPage(0);
    setSearching(true);
    setViewPostId(null);
    setViewProfile(false);
    setViewUser(null);
  }

  // 个人中心
  function openProfileSelf() {
    navigate('#/profile');
  }

  async function submitProfile(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    try {
      const r = await authApi.updateProfile(token, { username: profileForm.username, avatarUrl: profileForm.avatarUrl, bio: profileForm.bio, signature: profileForm.signature });
      if (r.token) { login(r.token, r.user.username); }
      setVerified(r.user.verified);
      setViewProfile(false);
      navigate(listHash());
      notify('个人资料已更新');
    } catch (err) { showError(err); }
  }

  async function toggleFollow() {
    if (!token) { setModal('login'); return; }
    if (!viewUser || viewUser.isSelf) return;
    try {
      await userApi.follow(token, viewUser.username, !viewUser.isFollowing);
      const updated = await userApi.profile(viewUser.username, token);
      setViewUser(updated);
      qc.invalidateQueries({ queryKey: ['user'] });
    } catch (err) { showError(err); }
  }

  // 举报
  async function openReport(type: 'POST' | 'COMMENT', id: number) {
    if (!token) { setModal('login'); return; }
    const slug = viewPost?.forum || active?.slug;
    if (!slug) { notify('版块信息缺失'); return; }
    try {
      const r = await forumApi.reportReasons(slug);
      report.open({ type, id, reasons: r });
    } catch (err) { showError(err); }
  }

  async function submitReport() {
    if (!report.state.modal || !report.state.reason.trim()) return;
    report.setSubmitting(true);
    try {
      await reportApi.submit(token, { targetType: report.state.modal.type, targetId: report.state.modal.id, reason: report.state.reason.trim() });
      report.close();
      notify('举报已提交，管理员将尽快处理');
    } catch (err) { showError(err); }
    finally { report.setSubmitting(false); }
  }

  // 版主面板
  function loadModReports() {
    if (!token || !active) return;
    reportApi.moderatorList(token, active.slug, 'PENDING', 0).then(r => setModReports(r.content)).catch(showError);
    reportApi.moderatorCounts(token, active.slug).then(d => setModReportPending(d.pending)).catch(e => console.warn('API:', e));
  }

  async function handleModReport(id: number, status: 'RESOLVED' | 'DISMISSED') {
    if (!token || !active) return;
    try {
      await reportApi.moderatorHandle(token, id, status, active.slug);
      notify(status === 'RESOLVED' ? '举报已标记处理' : '举报已驳回');
      loadModReports();
    } catch (err) { showError(err); }
  }

  function openModPanel() {
    if (!active) return;
    mod.open(active.customReportReason1 || '', active.customReportReason2 || '');
    forumApi.moderators(active.slug).then(r => mod.setModList(r)).catch(e => { console.warn('API:', e); mod.setModList([]); });
    loadModReports();
  }

  async function removeModerator(username: string) {
    if (!active) return;
    try {
      await forumApi.removeModerator(token, active.slug, username);
      mod.removeMod(username);
      setMods(list => list.filter(m => m !== username));
      notify(`已撤任 ${username} 的小版主`);
    } catch (err) { showError(err); }
  }

  async function saveReportReasons() {
    if (!active) return;
    try {
      const updated = await forumApi.saveReportReasons(token, active.slug, mod.state.reason1, mod.state.reason2);
      forumsQuery.refetch();
      setActive(updated);
      notify('版块特色举报原因已更新');
    } catch (err) { showError(err); }
  }

  // 管理员面板
  async function loadAudit(pg: number) {
    if (!token) return;
    try {
      const r = await adminApi.audit(token, pg);
      setAdminAudit(r.content);
      setAdminAuditTotalPages(Math.max(1, Math.ceil(r.totalElements / 20)));
      setAdminAuditPage(pg);
    } catch (err) { showError(err); }
  }

  async function loadReports(filter: ReportFilter, pg: number) {
    if (!token) return;
    try {
      const r = await reportApi.list(token, filter, pg);
      admin.setReports(r.content, Math.max(1, Math.ceil(r.totalElements / 15)));
    } catch (err) { showError(err); }
  }

  function openAdminPanel() {
    admin.open();
    loadReports('PENDING', 0);
    loadAudit(0);
    reportApi.counts(token).then(admin.setCounts).catch(e => console.warn('API:', e));
  }

  async function handleBan(username: string, days: number) {
    try {
      await adminApi.ban(token, username, days);
      notify(days > 0 ? `已封禁 ${username} ${days} 天` : `已解除 ${username} 的封禁`);
      loadAudit(0);
    } catch (err) { showError(err); }
  }

  async function handleMute(username: string, days: number) {
    try {
      await adminApi.mute(token, username, days);
      notify(days > 0 ? `已禁言 ${username} ${days} 天` : `已解除 ${username} 的禁言`);
      loadAudit(0);
    } catch (err) { showError(err); }
  }

  async function handleReport(id: number, status: 'RESOLVED' | 'DISMISSED') {
    try {
      await reportApi.handle(token, id, status);
      admin.handleReport(id, status, user);
      notify(status === 'RESOLVED' ? '举报已标记处理' : '举报已驳回');
    } catch (err) { showError(err); }
  }

  const draftActive = !!(postForm.title.trim() || postForm.content.trim());

  return (
    <div className="app">
      <div className="aurora a1" />
      <div className="aurora a2" />
      <Header
        query={query}
        setQuery={setQuery}
        onSearch={search}
        onEnterGlobal={enterGlobal}
        onOpenProfile={openProfileSelf}
        onOpenAdminPanel={openAdminPanel}
        onRequireLogin={() => setModal('login')}
        onOpenPost={openPostModal}
        onCreateForum={() => token ? setModal('forum') : setModal('login')}
        onVerify={doVerify}
        onOpenNav={() => setNavOpen(true)}
        theme={theme}
        onToggleTheme={() => setTheme(t => t === 'dark' ? 'light' : 'dark')}
      />
      <Sidebar
        forums={forums}
        popularTags={popularTags}
        active={active}
        globalMode={globalMode}
        activeTag={activeTag}
        token={token}
        onEnterGlobal={enterGlobal}
        onEnterForum={enterForum}
        onEnterTag={enterTag}
        onExitTag={() => navigate(listHash())}
        onCreateForum={() => token ? setModal('forum') : setModal('login')}
        onRequireLogin={() => setModal('login')}
        mobileOpen={navOpen}
        onCloseNav={() => setNavOpen(false)}
      />
      <main>
        {viewProfile ? (
          <ProfileView
            profile={profileForm}
            isSelf
            tab={profileTab}
            setTab={setProfileTab}
            posts={profileTab === 'favorites' ? profileFavPosts : profilePosts}
            followList={profileFollowList}
            editForm={profileForm}
            setEditForm={setProfileForm}
            token={token}
            onError={showError}
            onBack={() => navigate(listHash())}
            onOpenPost={openPost}
            onOpenUser={openUser}
            onSubmitProfile={submitProfile}
            onToggleFollow={toggleFollow}
            onEditProfile={() => setProfileTab('info')}
            onCancelEdit={() => setProfileTab('posts')}
            onAvatarChange={async () => {}}
            onRemoveAvatar={() => setProfileForm(f => ({ ...f, avatarUrl: '' }))}
          />
        ) : viewUser ? (
          <ProfileView
            profile={viewUser}
            isSelf={false}
            tab={userTab === 'posts' ? 'posts' : userTab === 'following' ? 'following' : 'followers'}
            setTab={(t) => setUserTab(t === 'following' ? 'following' : t === 'followers' ? 'followers' : 'posts')}
            posts={userPosts}
            followList={userFollowList}
            editForm={viewUser}
            setEditForm={() => {}}
            token={token}
            onError={showError}
            onBack={() => navigate(listHash())}
            onOpenPost={openPost}
            onOpenUser={openUser}
            onSubmitProfile={async () => {}}
            onToggleFollow={toggleFollow}
            onEditProfile={() => { navigate('#/profile'); }}
            onAvatarChange={async () => {}}
            onRemoveAvatar={() => {}}
          />
        ) : viewPost ? (
          <PostDetail
            viewPost={viewPost}
            comments={comments}
            replyTo={replyTo}
            setReplyTo={setReplyTo}
            commentContent={commentContent}
            setCommentContent={setCommentContent}
            token={token}
            user={user}
            isModerator={isModerator}
            onUser={openUser}
            onClose={closePostView}
            onVote={vote}
            onToggleFavorite={toggleFavorite}
            onToggleFeature={toggleFeature}
            onToggleLock={toggleLock}
            onDelete={(p) => setConfirmDel(p)}
            onOpenReport={openReport}
            onSubmitComment={submitComment}
            onReloadComments={commentsQuery.invalidate}
            onPinComment={togglePinComment}
            onDeleteComment={deleteComment}
            commentsHasMore={!!commentsQuery.query.hasNextPage}
            commentsLoadingMore={commentsQuery.query.isFetchingNextPage}
            onLoadMoreComments={commentsQuery.query.fetchNextPage}
            editPost={editPost}
            editPostTitle={editPostTitle}
            setEditPostTitle={setEditPostTitle}
            editPostContent={editPostContent}
            setEditPostContent={setEditPostContent}
            onStartEdit={startEditPost}
            onSaveEdit={saveEditPost}
            onCancelEdit={() => setEditPost(false)}
            onError={showError}
          />
        ) : (
          <PostList
            posts={displayedPosts}
            active={active}
            globalMode={globalMode}
            activeTag={activeTag}
            order={order}
            page={page}
            totalPages={displayedTotalPages}
            mods={mods}
            user={user}
            token={token}
            isModerator={isModerator}
            setOrder={setOrder}
            setSeed={setSeed}
            setPage={setPage}
            onOpenPost={openPost}
            onVote={vote}
            onEnterTag={enterTag}
            onToggleFeature={toggleFeature}
            onPostContext={onPostContext}
            onOpenUser={openUser}
            onNewPost={openPostModal}
            searching={searching}
            query={query}
            onExitSearch={exitSearch}
            loading={!searching ? postsQuery.isLoading : searchQuery.isLoading}
          />
        )}
      </main>

      {notice && <div className="toast" role="alert" aria-live="polite">✓ {notice}</div>}
      <ConfirmModal post={confirmDel} onClose={() => setConfirmDel(null)} onConfirm={doDelete} />
      <ContextMenu
        ctx={ctxMenu}
        isModerator={isModerator}
        canDelete={canDelete}
        onClose={() => setCtxMenu(null)}
        onToggleFeature={toggleFeature}
        onToggleLock={toggleLock}
        onDelete={(p) => setConfirmDel(p)}
      />
      {modal && (
        <Modal
          type={modal}
          close={() => { setModal(null); setPostForm({ title: '', content: '' }); }}
          change={setModal}
          submit={modal === 'login' || modal === 'register' ? submitAuth : modal === 'post' ? submitPost : submitForum}
          active={active}
          postForm={postForm}
          setPostForm={setPostForm}
          token={token}
          onError={showError}
          draftActive={modal === 'post' && draftActive}
          onClearDraft={() => { try { localStorage.removeItem(DRAFT_KEY); } catch { /* ignore */ } setPostForm({ title: '', content: '' }); }}
        />
      )}
      <ReportModal
        modal={report.state.modal}
        reason={report.state.reason}
        setReason={report.setReason}
        submitting={report.state.submitting}
        onClose={report.close}
        onSubmit={submitReport}
      />
      {mod.state.open && active && (
        <Suspense fallback={null}>
          <ModPanel
            active={active}
            modList={mod.state.modList}
            reason1={mod.state.reason1}
            reason2={mod.state.reason2}
            user={user}
            setReason1={mod.setReason1}
            setReason2={mod.setReason2}
            onClose={mod.close}
            onRemoveModerator={removeModerator}
            onSaveReportReasons={saveReportReasons}
            reports={modReports}
            reportPending={modReportPending}
            onHandleReport={handleModReport}
            onRefreshReports={loadModReports}
          />
        </Suspense>
      )}
      {admin.state.open && (
        <Suspense fallback={null}>
          <AdminPanel
            reports={admin.state.reports}
            filter={admin.state.filter}
            page={admin.state.page}
            totalPages={admin.state.totalPages}
            counts={admin.state.counts}
            setFilter={(f) => { admin.setFilter(f); loadReports(f, 0); }}
            setPage={(p) => { admin.setPage(p); loadReports(admin.state.filter, p); }}
            onClose={admin.close}
            onHandle={handleReport}
            onBan={handleBan}
            onMute={handleMute}
            onSearchUsers={(q) => userApi.search(q, token)}
            audit={adminAudit}
            auditPage={adminAuditPage}
            auditTotalPages={adminAuditTotalPages}
            setAuditPage={loadAudit}
          />
        </Suspense>
      )}
    </div>
  );
}
