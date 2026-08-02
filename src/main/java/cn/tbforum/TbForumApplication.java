package cn.tbforum;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
@SpringBootApplication
@EnableJpaRepositories(basePackages = "cn.tbforum.repo", considerNestedRepositories = true)
public class TbForumApplication { public static void main(String[] args) { SpringApplication.run(TbForumApplication.class,args); } }
