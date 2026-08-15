// 帖子列表查询：支持全站/版块/标签三种来源与 latest/hot/featured/fate 排序
import { useQuery } from '@tanstack/react-query';
import { postApi } from '../services/api';

type Source =
  | { kind: 'global' }
  | { kind: 'forum'; slug: string }
  | { kind: 'tag'; name: string };

export function usePosts(source: Source, order: string, page: number, seed: string) {
  const key = source.kind === 'global' ? 'global' : source.kind === 'forum' ? source.slug : `tag:${source.name}`;
  return useQuery({
    queryKey: ['posts', key, order, page, seed],
    queryFn: async () => {
      if (source.kind === 'global') return postApi.feed(order, page, seed);
      if (source.kind === 'forum') return postApi.byForum(source.slug, order, page, seed);
      return postApi.byTag(source.name, order, page, seed);
    },
    staleTime: 30_000,
  });
}

export function useSearch(query: string, enabled: boolean, page = 0) {
  return useQuery({
    queryKey: ['posts', 'search', query, page],
    queryFn: () => postApi.search(query, page),
    enabled,
    staleTime: 60_000,
  });
}
