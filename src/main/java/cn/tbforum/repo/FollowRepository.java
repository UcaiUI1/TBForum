package cn.tbforum.repo;
import cn.tbforum.domain.*; import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional; import java.util.*;
public interface FollowRepository extends JpaRepository<Follow,Long>{
 Optional<Follow> findByFollowerAndFollowee(User follower,User followee); long countByFollowee(User followee); long countByFollower(User follower);
 List<Follow> findByFollowerOrderByCreatedAtDesc(User follower); List<Follow> findByFolloweeOrderByCreatedAtDesc(User followee);
 @Transactional void deleteByFollowerAndFollowee(User follower,User followee);
}
