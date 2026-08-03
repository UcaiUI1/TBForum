package cn.tbforum.repo;
import cn.tbforum.domain.*; import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface InteractionRepository extends JpaRepository<Interaction,Long>{
 Optional<Interaction> findByUserAndPost(User u,Post p); List<Interaction> findByUserAndKind(User u,Interaction.Kind kind);
}
