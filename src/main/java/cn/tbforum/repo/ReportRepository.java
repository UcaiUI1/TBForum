package cn.tbforum.repo;
import cn.tbforum.domain.*; import org.springframework.data.domain.Page; import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query; import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional; import java.util.*;
public interface ReportRepository extends JpaRepository<Report,Long>{
 Page<Report> findByStatusOrderByCreatedAtDesc(Report.Status status,Pageable p); Page<Report> findAllByOrderByCreatedAtDesc(Pageable p);
 boolean existsByReporterAndTargetTypeAndTargetId(User reporter,Report.TargetType type,Long targetId); long countByStatus(Report.Status status);
 List<Report> findByTargetTypeAndTargetId(Report.TargetType type,Long targetId);
 @Query("select r from Report r where r.status=:status and ((r.targetType='POST' and r.targetId in (select p.id from Post p where p.forum in :forums)) or (r.targetType='COMMENT' and r.targetId in (select c.id from Comment c where c.post.forum in :forums))) order by r.createdAt desc")
 Page<Report> findModeratedByStatus(@Param("status") Report.Status status,@Param("forums") Collection<Forum> forums,Pageable p);
 @Query("select r from Report r where (r.targetType='POST' and r.targetId in (select p.id from Post p where p.forum in :forums)) or (r.targetType='COMMENT' and r.targetId in (select c.id from Comment c where c.post.forum in :forums)) order by r.createdAt desc")
 Page<Report> findModeratedAll(@Param("forums") Collection<Forum> forums,Pageable p);
 @Query("select count(r) from Report r where r.status='PENDING' and ((r.targetType='POST' and r.targetId in (select p.id from Post p where p.forum in :forums)) or (r.targetType='COMMENT' and r.targetId in (select c.id from Comment c where c.post.forum in :forums)))")
 long countModeratedPending(@Param("forums") Collection<Forum> forums);
 @Query("select count(r) from Report r where (r.targetType='POST' and r.targetId in (select p.id from Post p where p.forum in :forums)) or (r.targetType='COMMENT' and r.targetId in (select c.id from Comment c where c.post.forum in :forums))")
 long countModeratedAll(@Param("forums") Collection<Forum> forums);
 @Modifying @Transactional @Query("update Report r set r.status=:dismissed,r.handledAt=:now where r.targetType=:type and r.targetId=:targetId and r.status=:pending") int updateStatusByTarget(@Param("type") Report.TargetType type,@Param("targetId") Long targetId,@Param("now") java.time.Instant now,@Param("pending") Report.Status pending,@Param("dismissed") Report.Status dismissed);
 default int dismissPendingByTarget(Report.TargetType type,Long targetId,java.time.Instant now){return updateStatusByTarget(type,targetId,now,Report.Status.PENDING,Report.Status.DISMISSED);}
}
