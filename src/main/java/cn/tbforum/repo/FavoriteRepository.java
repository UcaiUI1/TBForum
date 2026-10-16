package cn.tbforum.repo;

import cn.tbforum.domain.*;
import java.util.*;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {
    Optional<Favorite> findByUserAndPost(User u, Post p);

    boolean existsByUserAndPost(User u, Post p);

    @Query("select f.post.id from Favorite f where f.user.id=:userId and f.post.id in :postIds")
    List<Long> findPostIdsByUserIdAndPostIdIn(@Param("userId") Long userId, @Param("postIds") List<Long> postIds);

    @Transactional
    void deleteByUserAndPost(User u, Post p);

    @Query(
            value =
                    "select p from Favorite fav join fav.post p join fetch p.author join fetch p.forum f join fetch f.owner where fav.user=:u and p.deleted=false order by fav.createdAt desc",
            countQuery = "select count(fav) from Favorite fav where fav.user=:u and fav.post.deleted=false")
    Page<Post> findPostsByUser(@Param("u") User u, Pageable p);
}
