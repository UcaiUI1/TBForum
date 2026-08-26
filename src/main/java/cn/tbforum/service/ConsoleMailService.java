package cn.tbforum.service;

import org.slf4j.Logger;
import org.springframework.stereotype.Service;

@Service
public class ConsoleMailService implements MailService {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(ConsoleMailService.class);

    @Override
    public void sendPasswordReset(String email, String link) {
        log.info("【密码重置】发送至 {}: {}", email, link);
    }
}
