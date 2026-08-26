package cn.tbforum.repo;

import cn.tbforum.domain.*;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PostRepository extends JpaRepository<Post, Long> {
    Page<Post> findByForumAndDeletedFalse(Forum forum, Pageable p);

    Page<Post> findByForumAndFeaturedTrueAndDeletedFalse(Forum forum, Pageable p);

    List<Post> findByForumAndDeletedFalse(Forum forum);

    Page<Post> findByAuthorAndDeletedFalse(User author, Pageable p);

    Page<Post> findAllByDeletedFalseOrderByCreatedAtDesc(Pageable p);

    Page<Post> findByFeaturedTrueAndDeletedFalse(Pageable p);

    List<Post> findByDeletedFalse();

    List<Post> findByIdGreaterThanOrderByIdAsc(Long id, Pageable pageable);

    @Query(
            "select p from Post p where p.deleted=false and (lower(p.title) like lower(concat('%',:q,'%')) or lower(p.content) like lower(concat('%',:q,'%')))")
    Page<Post> search(@Param("q") String q, Pageable p);
    /** 热门候选：近 N 天内有互动的帖子，取前 300 条由服务层做时间衰减评分 */
    @Query(
            "select p from Post p where p.deleted=false and p.createdAt>=:since order by (p.upvotes + p.commentCount*2 + p.views*0.01) desc, p.createdAt desc")
    List<Post> findHotCandidates(@Param("since") Instant since, Pageable p);

    @Query(
            "select p from Post p where p.forum=:forum and p.deleted=false and p.createdAt>=:since order by (p.upvotes + p.commentCount*2 + p.views*0.01) desc, p.createdAt desc")
    List<Post> findHotCandidatesByForum(@Param("forum") Forum forum, @Param("since") Instant since, Pageable p);

    @Modifying
    @Transactional
    @Query("update Post p set p.views=p.views+:delta where p.id=:id")
    void incrementViews(@Param("id") Long id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("update Post p set p.upvotes=p.upvotes+:delta where p.id=:id")
    void incrementUpvotes(@Param("id") Long id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("update Post p set p.downvotes=p.downvotes+:delta where p.id=:id")
    void incrementDownvotes(@Param("id") Long id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("update Post p set p.commentCount=p.commentCount+:delta where p.id=:id")
    int incrementCommentCount(@Param("id") Long id, @Param("delta") int delta);

    @Modifying
    @Transactional
    @Query("update Post p set p.deleted=true where p.forum=:forum and p.deleted=false")
    int softDeleteByForum(@Param("forum") Forum forum);
    /** fate 排序：用 seed 做伪随机排序（稳定分页，避免全表加载 OOM） */
    @Query("select p from Post p where p.deleted=false order by mod(p.id * :seed, 1000003) desc, p.createdAt desc")
    Page<Post> findFateBySeed(@Param("seed") Long seed, Pageable p);
    /** fate 排序：无 seed 时用 DB 随机函数（仅适合第一页，翻页需前端传 seed） */
    @Query(
            value = "select * from posts where deleted=false order by random() desc, created_at desc",
            countQuery = "select count(*) from posts where deleted=false",
            nativeQuery = true)
    Page<Post> findFateRandom(Pageable p);

    @Query(
            value =
                    "select * from posts where forum_id=:forumId and deleted=false order by random() desc, created_at desc",
            countQuery = "select count(*) from posts where forum_id=:forumId and deleted=false",
            nativeQuery = true)
    Page<Post> findFateRandomByForum(@Param("forumId") Long forumId, Pageable p);

    @Query(
            "select p from Post p where p.forum=:forum and p.deleted=false order by mod(p.id * :seed, 1000003) desc, p.createdAt desc")
    Page<Post> findFateByForumBySeed(@Param("forum") Forum forum, @Param("seed") Long seed, Pageable p);
}
