package cn.tbforum.repo;

import cn.tbforum.domain.*;
import java.util.*;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CommentRepository extends JpaRepository<Comment, Long> {
    @Modifying
    @Transactional
    @Query("update Comment c set c.deleted=true, c.content='[该评论已删除]' where c.id=:id and c.deleted=false")
    int softDeleteIfActive(@Param("id") Long id);

    List<Comment> findByPostAndDeletedFalseOrderByCreatedAtAsc(Post post);

    List<Comment> findByPostAndDeletedFalseOrderByPinnedDescCreatedAtAsc(Post post);

    List<Comment> findByPostOrderByPinnedDescCreatedAtAsc(Post post);
    /** P3-4: 分页查询根评论（parent is null），按置顶和创建时间排序 */
    @Query("select c from Comment c where c.post=:post and c.parent is null order by c.pinned desc, c.createdAt asc")
    Page<Comment> findRootsByPost(@Param("post") Post post, Pageable p);
    /** 查询指定根评论集合下的所有评论（含根评论自身及全部子孙），按创建时间升序 */
    @Query("select c from Comment c where c.post=:post and c.rootId in :rootIds order by c.createdAt asc")
    List<Comment> findByPostAndRootIdIn(@Param("post") Post post, @Param("rootIds") List<Long> rootIds);
}
