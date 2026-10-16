package cn.tbforum.repo;

import cn.tbforum.domain.*;
import cn.tbforum.domain.Interaction;
import cn.tbforum.domain.Post;
import cn.tbforum.domain.User;
import java.util.*;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InteractionRepository extends JpaRepository<Interaction, Long> {
    Optional<Interaction> findByUserAndPost(User u, Post p);

    List<Interaction> findByUserAndKind(User u, Interaction.Kind kind);

    @Query("select i.post.id from Interaction i where i.user.id=:userId and i.post.id in :postIds and i.kind=:kind")
    List<Long> findPostIdsByUserIdAndPostIdInAndKind(
            @Param("userId") Long userId, @Param("postIds") List<Long> postIds, @Param("kind") Interaction.Kind kind);
}
