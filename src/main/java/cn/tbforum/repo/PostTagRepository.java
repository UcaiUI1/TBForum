package cn.tbforum.repo;
import cn.tbforum.domain.*; import org.springframework.data.domain.Page; import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*; import org.springframework.data.repository.query.Param; import org.springframework.transaction.annotation.Transactional;
import java.util.*;
public interface PostTagRepository extends JpaRepository<PostTag,Long>{
 List<PostTag> findByPost(Post post); List<PostTag> findByTag(Tag tag);
 @Query("select pt from PostTag pt where pt.post.id in :postIds") List<PostTag> findByPostIdIn(@Param("postIds") List<Long> postIds);
 @Query("select pt.post from PostTag pt where pt.tag=:tag and pt.post.deleted=false order by pt.post.createdAt desc") Page<Post> findPostsByTag(@Param("tag") Tag tag,Pageable p);
 @Modifying @Transactional @Query("delete from PostTag pt where pt.post=:post") void deleteByPost(@Param("post") Post post);
}
