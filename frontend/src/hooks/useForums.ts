// 版块列表与热门标签查询
import { useQuery } from '@tanstack/react-query';
import { forumApi, tagApi } from '../services/api';

export function useForums() {
  return useQuery({
    queryKey: ['forums'],
    queryFn: async () => (await forumApi.list()).content,
    staleTime: 60_000,
  });
}

export function usePopularTags() {
  return useQuery({
    queryKey: ['tags', 'popular'],
    queryFn: () => tagApi.popular(),
    staleTime: 60_000,
  });
}
