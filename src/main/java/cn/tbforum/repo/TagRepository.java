package cn.tbforum.repo;

import cn.tbforum.domain.Tag;
import jakarta.persistence.LockModeType;
import java.util.*;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface TagRepository extends JpaRepository<Tag, Long> {
    Optional<Tag> findByName(String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Tag t where t.name=:name")
    Optional<Tag> findByNameForUpdate(@Param("name") String name);

    /** Join the caller's transaction; a competing insertion is not a failed transaction. */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query(value = "insert into tags (name, post_count) values (:name, 0) on conflict do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("name") String name);

    List<Tag> findTop50ByOrderByPostCountDesc();

    @Modifying
    @Transactional
    @Query("update Tag t set t.postCount=t.postCount+:delta where t.id=:id")
    void incrementPostCount(@Param("id") Long id, @Param("delta") int delta);
}
