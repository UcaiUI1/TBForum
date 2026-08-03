// 个人中心 / 用户主页（复用同一布局）
import React, { FormEvent } from 'react';
import type { UserProfile, UserView, Post } from '../types';
import { time } from '../utils/format';
import { uploadFile } from '../services/api';

type Props = {
  // 当前查看的资料（个人中心或他人主页）
  profile: UserProfile;
  isSelf: boolean;
  // tab 状态
  tab: 'info' | 'posts' | 'favorites' | 'following' | 'followers';
  setTab: (t: 'info' | 'posts' | 'favorites' | 'following' | 'followers') => void;
  posts: Post[];
  followList: UserView[];
  // 仅个人中心编辑态使用
  editForm: UserProfile;
  setEditForm: (f: UserProfile) => void;
  token: string;
  onError: (e: unknown) => void;
  onBack: () => void;
  onOpenPost: (p: Post) => void;
  onOpenUser: (username: string) => void;
  onSubmitProfile: (e: FormEvent<HTMLFormElement>) => void;
  onToggleFollow: () => void;
  onEditProfile: () => void;
  onAvatarChange: (e: React.ChangeEvent<HTMLInputElement>) => Promise<void>;
  onRemoveAvatar: () => void;
};

export function ProfileView(props: Props) {
  const { profile, isSelf, tab, setTab, posts, followList, editForm, setEditForm, token, onError, onBack, onOpenPost, onOpenUser, onSubmitProfile, onToggleFollow, onEditProfile, onAvatarChange, onRemoveAvatar } = props;
  const showEditForm = isSelf && tab === 'info';

  async function handleAvatarChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    if (!file.type.startsWith('image/')) { onError(new Error('只能上传图片文件')); return; }
    if (file.size > 2 * 1024 * 1024) { onError(new Error('图片不能超过 2MB')); return; }
    try {
      const data = await uploadFile(file, token);
      setEditForm({ ...editForm, avatarUrl: data.url });
    } catch (err) { onError(err); }
  }

  return (
    <section className="profile-view">
      <button className="back-btn" onClick={onBack}>← 返回</button>
      <p className="eyebrow">{isSelf ? 'TBFORUM / PROFILE' : 'TBFORUM / MEMBER'}</p>
      <h1>{isSelf ? '个人中心' : '用户资料'}</h1>
      <div className="profile-card user-profile-card">
        <div className="avatar-section">
          <div className="avatar-preview avatar-preview-lg">
            {profile.avatarUrl ? <img src={profile.avatarUrl} alt="头像" /> : <span className="avatar-fallback">{profile.username.charAt(0).toUpperCase() || '◉'}</span>}
          </div>
          <div className="user-info">
            <h2 className="user-name">{profile.username}</h2>
            {profile.verified && <span className="verified-badge">已认证</span>}
            <div className="follow-stats">
              <button className="follow-stat" onClick={() => setTab('following')}><b>{profile.following}</b><span>关注</span></button>
              <button className="follow-stat" onClick={() => setTab('followers')}><b>{profile.followers}</b><span>粉丝</span></button>
            </div>
            {!isSelf && token && <button className={profile.isFollowing ? 'follow-btn following' : 'follow-btn'} onClick={onToggleFollow}>{profile.isFollowing ? (profile.mutual ? '互相关注' : '已关注') : '+ 关注'}</button>}
            {isSelf && <button className="follow-btn" onClick={onEditProfile}>编辑资料</button>}
          </div>
        </div>
        {profile.signature && <div className="profile-field"><label>个人签名</label><p className="profile-text">{profile.signature}</p></div>}
        {!isSelf && profile.bio && <div className="profile-field"><label>个人简介</label><p className="profile-text">{profile.bio}</p></div>}
        {!isSelf && !profile.signature && !profile.bio && <p className="no-profile-text">该用户还没有填写个人资料。</p>}
      </div>
      <div className="profile-tabs">
        {isSelf && <button className={tab === 'info' ? 'selected' : ''} onClick={() => setTab('info')}>资料</button>}
        <button className={tab === 'posts' ? 'selected' : ''} onClick={() => setTab('posts')}>{isSelf ? '我的帖子' : '帖子'}</button>
        {isSelf && <button className={tab === 'favorites' ? 'selected' : ''} onClick={() => setTab('favorites')}>我的收藏</button>}
        <button className={tab === 'following' ? 'selected' : ''} onClick={() => setTab('following')}>关注</button>
        <button className={tab === 'followers' ? 'selected' : ''} onClick={() => setTab('followers')}>粉丝</button>
      </div>
      {showEditForm ? (
        <form className="profile-card" onSubmit={onSubmitProfile}>
          <div className="avatar-section">
            <div className="avatar-preview">{editForm.avatarUrl ? <img src={editForm.avatarUrl} alt="头像" /> : <span className="avatar-fallback">{editForm.username.charAt(0).toUpperCase() || '◉'}</span>}</div>
            <div className="avatar-actions">
              <label className="avatar-upload"><input type="file" accept="image/*" onChange={handleAvatarChange} hidden /><span>选择头像</span></label>
              {editForm.avatarUrl && <button type="button" className="text-link" onClick={onRemoveAvatar}>移除头像</button>}
              <small>JPG/PNG，最大 2MB</small>
            </div>
          </div>
          <div className="profile-field"><label>用户名</label><input value={editForm.username} onChange={e => setEditForm({ ...editForm, username: e.target.value })} minLength={3} maxLength={40} required /></div>
          <div className="profile-field"><label>个人签名</label><input value={editForm.signature} onChange={e => setEditForm({ ...editForm, signature: e.target.value })} maxLength={100} placeholder="一句话展示你的态度" /></div>
          <div className="profile-field"><label>个人简介</label><textarea value={editForm.bio} onChange={e => setEditForm({ ...editForm, bio: e.target.value })} maxLength={500} rows={5} placeholder="介绍一下自己，让别人更了解你" /></div>
          <div className="profile-foot"><button type="button" className="plain" onClick={onBack}>取消</button><button type="submit" className="primary">保存修改</button></div>
        </form>
      ) : tab === 'posts' || tab === 'favorites' ? (
        <div className="tab-content">
          {posts.length ? posts.map(p => (
            <div key={p.id} className="tab-post" onClick={() => onOpenPost(p)}>
              <span className="forum-pill">#{p.forum}</span>
              <h3>{p.title}</h3>
              <div className="tab-post-meta"><span>↑ {p.upvotes}</span><span>◌ {p.comments}</span><time>{time(p.createdAt)}</time></div>
            </div>
          )) : <p className="no-profile-text">{tab === 'posts' ? '还没有发布过帖子' : '还没有收藏过帖子'}</p>}
        </div>
      ) : (
        <div className="tab-content">
          {followList.length ? followList.map(u => (
            <div key={u.id} className="tab-user" onClick={() => onOpenUser(u.username)}>
              <span className="avatar avatar-md">{u.avatarUrl ? <img src={u.avatarUrl} alt={u.username} /> : <span className="avatar-fallback">{u.username.charAt(0).toUpperCase()}</span>}</span>
              <div className="tab-user-info"><b>{u.username}</b>{u.verified && <span className="verified-badge">✓</span>}{u.signature && <span className="tab-user-sig">{u.signature}</span>}</div>
            </div>
          )) : <p className="no-profile-text">{tab === 'following' ? '还没有关注任何人' : '还没有粉丝'}</p>}
        </div>
      )}
    </section>
  );
}
