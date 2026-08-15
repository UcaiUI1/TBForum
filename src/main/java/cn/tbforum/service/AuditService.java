package cn.tbforum.service;
import cn.tbforum.domain.AuditLog; import cn.tbforum.domain.User; import cn.tbforum.dto.Dtos.AuditLogView;
import cn.tbforum.repo.AuditLogRepository;
import org.springframework.data.domain.*; import org.springframework.stereotype.Service;

@Service
public class AuditService {
 private final AuditLogRepository logs;
 public AuditService(AuditLogRepository l){logs=l;}
 public void log(User actor,String action,String targetType,Long targetId,String detail){
  AuditLog a=new AuditLog();a.actor=actor;a.action=action;a.targetType=targetType;a.targetId=targetId;a.detail=detail!=null&&detail.length()>300?detail.substring(0,300):detail;logs.save(a);
 }
 public Page<AuditLogView> list(int page,int size){return logs.findAllByOrderByCreatedAtDesc(PageRequest.of(page,Math.min(size,50))).map(AuditLogView::of);}
}
