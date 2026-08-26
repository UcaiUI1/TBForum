package cn.tbforum.repo;

import cn.tbforum.domain.*;
import cn.tbforum.domain.Interaction;
import cn.tbforum.domain.Post;
import cn.tbforum.domain.User;
import java.util.*;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InteractionRepository extends JpaRepository<Interaction, Long> {
    Optional<Interaction> findByUserAndPost(User u, Post p);

    List<Interaction> findByUserAndKind(User u, Interaction.Kind kind);
}
