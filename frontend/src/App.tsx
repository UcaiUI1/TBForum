// 应用主组件：组装顶栏、侧边栏、主内容区与各弹窗
import React, { FormEvent, Suspense, lazy, useEffect, useMemo, useRef, useState, useCallback } from 'react';
import type { Post, Forum, Comment, UserProfile, ModalType, ReportFilter } from './types';
import { useAuth } from './contexts/AuthContext';
import { useNotifications } from './contexts/NotificationContext';
import {
  authApi, forumApi, postApi, commentApi, userApi, reportApi,
} from './services/api';
import { useForums, usePopularTags } from './hooks/useForums';
import { usePosts, useSearch } from './hooks/usePosts';
import { usePostDetail, useComments } from './hooks/usePostDetail';
import { useUserProfile, useUserPosts, useUserFollowList, useMyFavorites } from './hooks/useUserProfile';
import { useReportState } from './hooks/useReportState';
import { useModState } from './hooks/useModState';
import { useAdminState } from './hooks/useAdminState';

import { Header } from './components/Header';
import { Sidebar } from './components/Sidebar';
import { PostList } from './components/PostList';
import { PostDetail } from './components/PostDetail';
import { ProfileView } from './components/ProfileView';
import { Modal } from './components/Modal';
import { ConfirmModal } from './components/ConfirmModal';
import { ContextMenu } from './components/ContextMenu';
import { ReportModal } from './components/ReportModal';

// 代码分割：管理面板懒加载，减少首屏体积
const ModPanel = lazy(() => import('./components/ModPanel').then(m => ({ default: m.ModPanel })));
const AdminPanel = lazy(() => import('./components/AdminPanel').then(m => ({ default: m.AdminPanel })));

const PAGE_SIZE = 10;

export function App() {
  const { token, user, verified, isAdmin, login, logout, setVerified } = useAuth();
  const { setOnNotifClick } = useNotifications();

  // 导航与列表状态
  const [active, setActive] = useState<Forum | null>(null);
  const [globalMode, setGlobalMode] = useState(false);
  const [activeTag, setActiveTag] = useState<string | null>(null);
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
  const searchQuery = useSearch(query, searching);
  const displayedPosts = searching ? (searchQuery.data?.content ?? []) : posts;
  const displayedTotalPages = searching ? (searchQuery.data ? Math.ceil(searchQuery.data.totalElements / PAGE_SIZE) : 0) : totalPages;

  const postDetailQuery = usePostDetail(viewPostId);
  const viewPost = viewPostId ? (postDetailQuery.data ?? null) : null;
  const commentsHook = useComments(viewPostId);
  const comments = commentsHook.query.data ?? [];

  const profilePosts = useUserPosts(viewProfile ? user : null).data ?? [];
  const profileFavPosts = useMyFavorites(token).data ?? [];
  const profileFollowList = useUserFollowList(viewProfile ? user : null, profileTab === 'following' ? 'following' : 'followers', token).data ?? [];
  const userPosts = useUserPosts(viewUser?.username ?? null).data ?? [];
  const userFollowList = useUserFollowList(viewUser?.username ?? null, userTab === 'following' ? 'following' : 'followers', token).data ?? [];

  // 聚合状态（useReducer）
  const report = useReportState();
  const mod = useModState();
  const admin = useAdminState();

  // 通知点击 → 跳转帖子
  useEffect(() => {
    setOnNotifClick(async (n) => {
      if (n.postId) { setViewProfile(false); setViewUser(null); setViewPostId(n.postId); }
    });
  }, [setOnNotifClick]);

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

  // 初始化版块
  useEffect(() => {
    if (forums.length && !active && !globalMode && !activeTag) setActive(forums[0]);
  }, [forums, active, globalMode, activeTag]);

  // 拉取版主列表
  useEffect(() => {
    if (!active) return;
    forumApi.moderators(active.slug).then(r => setMods(r.map(m => m.username))).catch(e => { console.warn('API:', e); setMods([]); });
  }, [active?.slug]);

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

  // 导航
  const enterGlobal = () => { setGlobalMode(true); setActive(null); setActiveTag(null); setPage(0); setOrder('latest'); setSearching(false); };
  const enterTag = (tag: string) => { setActiveTag(tag); setGlobalMode(false); setActive(null); setPage(0); setOrder('latest'); setSearching(false); };
  const enterForum = (f: Forum) => { setGlobalMode(false); setActiveTag(null); setActive(f); setPage(0); setSearching(false); };

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
  async function submitPost(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!active) return;
    try {
      await postApi.create(token, active.slug, postForm);
      setModal(null);
      setPostForm({ title: '', content: '' });
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
      const updated = await postApi.interact(token, p.id, 'UP');
      // 直接更新缓存（避免重新拉取触发浏览数+1）
      postsQuery.refetch();
      if (viewPostId === p.id) postDetailQuery.refetch();
    } catch (err) { showError(err); }
  }

  async function doDelete() {
    if (!confirmDel) return;
    try {
      await postApi.delete(token, confirmDel.id);
      const id = confirmDel.id;
      setConfirmDel(null);
      if (viewPostId === id) { setViewPostId(null); }
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

  // 帖子详情
  const openPost = (p: Post) => {
    setViewPostId(p.id);
    setReplyTo(null);
    setEditPost(false);
    setCommentContent('');
  };
  const closePost = () => { setViewPostId(null); setReplyTo(null); setEditPost(false); };

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
      commentsHook.invalidate();
      postDetailQuery.refetch();
      notify('评论已发表');
    } catch (err) { showError(err); }
  }

  async function togglePinComment(c: Comment) {
    if (!token || !viewPost) return;
    try {
      await commentApi.pin(token, viewPost.id, c.id, !c.pinned);
      commentsHook.invalidate();
      notify(c.pinned ? '已取消置顶' : '评论已置顶');
    } catch (err) { showError(err); }
  }

  // 搜索
  async function search(e: FormEvent) {
    e.preventDefault();
    if (!query.trim()) return;
    if (!token) { setModal('login'); return; }
    setPage(0);
    setSearching(true);
  }

  // 个人中心
  function openProfile() {
    setViewProfile(true);
    setViewUser(null);
    setProfileTab('info');
    userApi.profile(user, token).then(d => setProfileForm(d)).catch(showError);
  }

  async function submitProfile(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    try {
      const r = await authApi.updateProfile(token, { username: profileForm.username, avatarUrl: profileForm.avatarUrl, bio: profileForm.bio, signature: profileForm.signature });
      if (r.token) { login(r.token, r.user.username); }
      setVerified(r.user.verified);
      setViewProfile(false);
      notify('个人资料已更新');
    } catch (err) { showError(err); }
  }

  // 用户主页
  function openUser(username: string) {
    setViewPostId(null);
    setViewProfile(false);
    setUserTab('posts');
    userApi.profile(username, token).then(d => setViewUser(d)).catch(showError);
  }

  async function toggleFollow() {
    if (!token) { setModal('login'); return; }
    if (!viewUser || viewUser.isSelf) return;
    try {
      await userApi.follow(token, viewUser.username, !viewUser.isFollowing);
      const updated = await userApi.profile(viewUser.username, token);
      setViewUser(updated);
    } catch (err) { showError(err); }
  }

  // 举报
  async function openReport(type: 'POST' | 'COMMENT', id: number) {
    if (!token) { setModal('login'); return; }
    if (!active) { notify('版块信息缺失'); return; }
    try {
      const r = await forumApi.reportReasons(active.slug);
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
  function openModPanel() {
    if (!active) return;
    mod.open(active.customReportReason1 || '', active.customReportReason2 || '');
    forumApi.moderators(active.slug).then(r => mod.setModList(r)).catch(e => { console.warn('API:', e); mod.setModList([]); });
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
    reportApi.counts(token).then(admin.setCounts).catch(e => console.warn('API:', e));
  }

  async function handleReport(id: number, status: 'RESOLVED' | 'DISMISSED') {
    try {
      await reportApi.handle(token, id, status);
      admin.handleReport(id, status, user);
      notify(status === 'RESOLVED' ? '举报已标记处理' : '举报已驳回');
    } catch (err) { showError(err); }
  }

  return (
    <div className="app">
      <div className="aurora a1" />
      <div className="aurora a2" />
      <Header
        query={query}
        setQuery={setQuery}
        onSearch={search}
        onEnterGlobal={enterGlobal}
        onOpenProfile={openProfile}
        onOpenAdminPanel={openAdminPanel}
        onRequireLogin={() => setModal('login')}
        onOpenPost={() => {
          if (!token) { setModal('login'); return; }
          if (!active) { notify('请先创建一个版块'); setModal('forum'); return; }
          setModal('post');
        }}
        onCreateForum={() => token ? setModal('forum') : setModal('login')}
        onVerify={doVerify}
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
        onExitTag={() => { setActiveTag(null); setActive(forums[0] || null); setPage(0); }}
        onCreateForum={() => token ? setModal('forum') : setModal('login')}
        onRequireLogin={() => setModal('login')}
      />
      <main>
        {viewProfile ? (
          <ProfileView
            profile={profileForm}
            isSelf
            tab={profileTab}
            setTab={setProfileTab}
            posts={profilePosts}
            followList={profileFollowList}
            editForm={profileForm}
            setEditForm={setProfileForm}
            token={token}
            onError={showError}
            onBack={() => setViewProfile(false)}
            onOpenPost={openPost}
            onOpenUser={openUser}
            onSubmitProfile={submitProfile}
            onToggleFollow={toggleFollow}
            onEditProfile={() => setProfileTab('info')}
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
            onBack={() => setViewUser(null)}
            onOpenPost={openPost}
            onOpenUser={openUser}
            onSubmitProfile={async () => {}}
            onToggleFollow={toggleFollow}
            onEditProfile={() => { setViewUser(null); openProfile(); }}
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
            onClose={closePost}
            onVote={vote}
            onToggleFavorite={toggleFavorite}
            onToggleFeature={toggleFeature}
            onToggleLock={toggleLock}
            onDelete={(p) => setConfirmDel(p)}
            onOpenReport={openReport}
            onSubmitComment={submitComment}
            onReloadComments={commentsHook.invalidate}
            onPinComment={togglePinComment}
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
            onNewPost={() => {
              if (!token) { setModal('login'); return; }
              if (!active) { notify('请先创建一个版块'); setModal('forum'); return; }
              setModal('post');
            }}
          />
        )}
      </main>

      {notice && <div className="toast" role="alert" aria-live="polite">✦ {notice}</div>}
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
          />
        </Suspense>
      )}
    </div>
  );
}
