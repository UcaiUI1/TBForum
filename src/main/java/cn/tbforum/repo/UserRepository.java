package cn.tbforum.repo;

import cn.tbforum.domain.User;
import jakarta.persistence.LockModeType;
import java.util.*;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    List<User> findTop10ByUsernameContainingIgnoreCase(String username);

    Optional<User> findByEmail(String email);

    List<User> findByIdGreaterThanOrderByIdAsc(Long id, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id=:id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
