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

    @Transactional
    void deleteByUserAndPost(User u, Post p);

    @Query("select f.post from Favorite f where f.user=:u and f.post.deleted=false order by f.createdAt desc")
    Page<Post> findPostsByUser(@Param("u") User u, Pageable p);
}
