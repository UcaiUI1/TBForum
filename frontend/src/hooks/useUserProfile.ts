// 用户资料、帖子、关注列表查询
import { useQuery } from '@tanstack/react-query';
import { userApi, postApi } from '../services/api';

export function useUserProfile(username: string | null, token: string) {
  return useQuery({
    queryKey: ['user', username],
    queryFn: () => userApi.profile(username!, token),
    enabled: !!username,
    staleTime: 30_000,
  });
}

export function useUserPosts(username: string | null) {
  return useQuery({
    queryKey: ['user', username, 'posts'],
    queryFn: async () => (await postApi.userPosts(username!)).content,
    enabled: !!username,
    staleTime: 30_000,
  });
}

export function useUserFollowList(username: string | null, kind: 'following' | 'followers', token: string) {
  return useQuery({
    queryKey: ['user', username, kind],
    queryFn: () => kind === 'following' ? userApi.following(username!, token) : userApi.followers(username!, token),
    enabled: !!username,
    staleTime: 30_000,
  });
}

export function useMyFavorites(token: string) {
  return useQuery({
    queryKey: ['user', 'me', 'favorites'],
    queryFn: async () => (await postApi.myFavorites(token)).content,
    enabled: !!token,
    staleTime: 30_000,
  });
}
