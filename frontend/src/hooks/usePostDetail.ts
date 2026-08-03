// 帖子详情与评论树查询
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { postApi, commentApi } from '../services/api';

export function usePostDetail(id: number | null) {
  return useQuery({
    queryKey: ['post', id],
    queryFn: () => postApi.detail(id!),
    enabled: id != null,
    staleTime: 0,
  });
}

export function useComments(postId: number | null) {
  const qc = useQueryClient();
  return {
    query: useQuery({
      queryKey: ['comments', postId],
      queryFn: () => commentApi.tree(postId!).then(p => p.content),
      enabled: postId != null,
      staleTime: 0,
    }),
    invalidate: () => { if (postId != null) qc.invalidateQueries({ queryKey: ['comments', postId] }); },
  };
}
