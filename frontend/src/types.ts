// 全局类型定义：与后端 DTO 对齐
export type Forum = {
  id: number;
  slug: string;
  name: string;
  description: string;
  owner: { username: string };
  customReportReason1?: string | null;
  customReportReason2?: string | null;
};

export type Post = {
  id: number;
  title: string;
  content: string;
  forum: string;
  author: string;
  authorAvatar?: string;
  createdAt: string;
  views: number;
  upvotes: number;
  comments: number;
  featured: boolean;
  locked: boolean;
  edited: boolean;
  editedAt: string | null;
  favorited: boolean;
  tags?: string[];
};

export type Comment = {
  id: number;
  author: string;
  authorAvatar?: string;
  content: string;
  depth: number;
  createdAt: string;
  children: Comment[];
  edited: boolean;
  editedAt: string | null;
  pinned: boolean;
};

export type Page<T> = { content: T[]; totalElements: number };

export type TagView = { id: number; name: string; postCount: number };

export type UserView = {
  id: number;
  username: string;
  verified: boolean;
  avatarUrl: string;
  bio: string;
  signature: string;
  role?: string;
};

export type UserProfile = {
  id: number;
  username: string;
  verified: boolean;
  avatarUrl: string;
  bio: string;
  signature: string;
  following: number;
  followers: number;
  isFollowing: boolean;
  isSelf: boolean;
  mutual: boolean;
};

export type Notification = {
  id: number;
  kind: string;
  text: string;
  actor: string;
  postId: number | null;
  commentId: number | null;
  createdAt: string;
  read: boolean;
};

export type ReportReasons = { standard: string[]; custom: string[] };

export type ReportView = {
  id: number;
  reporter: string;
  targetType: string;
  targetId: number;
  reason: string;
  createdAt: string;
  status: string;
  handledBy: string | null;
  handledAt: string | null;
};

export type ModalType = 'login' | 'register' | 'post' | 'forum';

export type ReportFilter = 'PENDING' | 'RESOLVED' | 'DISMISSED' | 'ALL';
