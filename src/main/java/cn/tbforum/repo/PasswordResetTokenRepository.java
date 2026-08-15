package cn.tbforum.repo;
import cn.tbforum.domain.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken,Long>{
 Optional<PasswordResetToken> findByTokenHash(String tokenHash);
}
