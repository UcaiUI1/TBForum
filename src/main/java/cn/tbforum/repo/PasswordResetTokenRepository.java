package cn.tbforum.repo;

import cn.tbforum.domain.PasswordResetToken;
import cn.tbforum.domain.User;
import java.time.Instant;
import java.util.*;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("update PasswordResetToken t set t.used=true where t.id=:id and t.used=false and t.expiresAt>:now")
    int consume(@Param("id") Long id, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Transactional
    @Query("update PasswordResetToken t set t.used=true where t.user=:user and t.used=false")
    int invalidateUnusedForUser(@Param("user") User user);
}
