package cn.tbforum;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "cn.tbforum.repo", considerNestedRepositories = true)
@EnableScheduling
public class TbForumApplication {
    public static void main(String[] args) {
        SpringApplication.run(TbForumApplication.class, args);
    }
}
