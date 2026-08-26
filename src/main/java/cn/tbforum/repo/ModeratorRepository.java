package cn.tbforum.repo;

import cn.tbforum.domain.*;
import cn.tbforum.domain.Forum;
import cn.tbforum.domain.ForumModerator;
import cn.tbforum.domain.User;
import java.util.*;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface ModeratorRepository extends JpaRepository<ForumModerator, Long> {
    long countByForum(Forum forum);

    boolean existsByForumAndUser(Forum f, User u);

    List<ForumModerator> findByForum(Forum forum);

    List<ForumModerator> findByUser(User user);

    @Transactional
    void deleteByForumAndUser(Forum f, User u);
}
