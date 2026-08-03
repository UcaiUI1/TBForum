package cn.tbforum.repo;
import cn.tbforum.domain.*; import org.springframework.data.domain.Page; import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query; import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional; import java.util.*;
public interface ReportRepository extends JpaRepository<Report,Long>{
 Page<Report> findByStatusOrderByCreatedAtDesc(Report.Status status,Pageable p); Page<Report> findAllByOrderByCreatedAtDesc(Pageable p);
 boolean existsByReporterAndTargetTypeAndTargetId(User reporter,Report.TargetType type,Long targetId); long countByStatus(Report.Status status);
 List<Report> findByTargetTypeAndTargetId(Report.TargetType type,Long targetId);
 @Modifying @Transactional @Query("update Report r set r.status=:dismissed,r.handledAt=:now where r.targetType=:type and r.targetId=:targetId and r.status=:pending") int updateStatusByTarget(@Param("type") Report.TargetType type,@Param("targetId") Long targetId,@Param("now") java.time.Instant now,@Param("pending") Report.Status pending,@Param("dismissed") Report.Status dismissed);
 default int dismissPendingByTarget(Report.TargetType type,Long targetId,java.time.Instant now){return updateStatusByTarget(type,targetId,now,Report.Status.PENDING,Report.Status.DISMISSED);}
}
