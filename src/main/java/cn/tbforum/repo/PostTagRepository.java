package cn.tbforum.repo;

import cn.tbforum.domain.*;
import java.util.*;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PostTagRepository extends JpaRepository<PostTag, Long> {
    @Query("select pt from PostTag pt join fetch pt.tag where pt.post=:post order by pt.id")
    List<PostTag> findByPost(@Param("post") Post post);

    List<PostTag> findByTag(Tag tag);

    @Query("select pt from PostTag pt where pt.post.id in :postIds")
    List<PostTag> findByPostIdIn(@Param("postIds") List<Long> postIds);

    interface PostTagName {
        Long getPostId();

        String getTagName();
    }

    @Query(
            "select pt.post.id as postId, pt.tag.name as tagName from PostTag pt where pt.post.id in :postIds order by pt.id")
    List<PostTagName> findTagNamesByPostIdIn(@Param("postIds") List<Long> postIds);

    @Query(
            value =
                    "select p from PostTag pt join pt.post p join fetch p.author join fetch p.forum f join fetch f.owner where pt.tag=:tag and p.deleted=false order by p.createdAt desc",
            countQuery = "select count(pt) from PostTag pt where pt.tag=:tag and pt.post.deleted=false")
    Page<Post> findPostsByTag(@Param("tag") Tag tag, Pageable p);

    @Modifying
    @Transactional
    @Query("delete from PostTag pt where pt.post=:post")
    void deleteByPost(@Param("post") Post post);
}
