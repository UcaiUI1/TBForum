package cn.tbforum.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.tbforum.domain.PasswordResetToken;
import cn.tbforum.domain.User;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PasswordResetTokenRepositoryTest {
    @Autowired
    PasswordResetTokenRepository resetTokens;

    @Autowired
    UserRepository users;

    @Test
    void consumeIsAtomicUnderConcurrentRequests() throws Exception {
        User user = new User();
        user.username = "reset_race_user";
        user.passwordHash = "hash";
        user = users.save(user);

        PasswordResetToken token = new PasswordResetToken();
        token.user = user;
        token.tokenHash = "concurrent-token-hash";
        token.expiresAt = Instant.now().plusSeconds(600);
        Long tokenId = resetTokens.save(token).id;

        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var task = (Callable<Integer>) () -> {
                ready.countDown();
                start.await();
                return resetTokens.consume(tokenId, Instant.now());
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            ready.await();
            start.countDown();

            assertEquals(1, first.get() + second.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
