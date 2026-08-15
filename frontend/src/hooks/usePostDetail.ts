// 帖子详情与评论树查询：评论按根评论分页，支持“加载更多”追加
import { useQuery, useQueryClient, useInfiniteQuery } from '@tanstack/react-query';
import { postApi, commentApi } from '../services/api';

const COMMENT_PAGE_SIZE = 20;

export function usePostDetail(id: number | null, token = '') {
  return useQuery({
    queryKey: ['post', id],
    queryFn: () => postApi.detail(id!, token),
    enabled: id != null,
    staleTime: 0,
  });
}

export function useComments(postId: number | null) {
  const qc = useQueryClient();
  const query = useInfiniteQuery({
    queryKey: ['comments', postId],
    queryFn: ({ pageParam }) => commentApi.tree(postId!, pageParam, COMMENT_PAGE_SIZE),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.number + 1 < last.totalPages ? last.number + 1 : undefined),
    enabled: postId != null,
    staleTime: 0,
  });
  return {
    query,
    invalidate: () => { if (postId != null) qc.invalidateQueries({ queryKey: ['comments', postId] }); },
  };
}
