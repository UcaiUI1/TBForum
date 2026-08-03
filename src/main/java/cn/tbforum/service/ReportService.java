package cn.tbforum.service;
import cn.tbforum.domain.*; import cn.tbforum.dto.Dtos.*;
import cn.tbforum.repo.ReportRepository; import cn.tbforum.repo.PostRepository; import cn.tbforum.repo.CommentRepository;
import org.springframework.data.domain.*; import org.springframework.http.*;
import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
import java.time.Instant; import java.util.*;

@Service
public class ReportService {
 private final ReportRepository reports; private final PostRepository posts; private final CommentRepository comments; private final UserService userService;
 public ReportService(ReportRepository r,PostRepository p,CommentRepository c,UserService u){reports=r;posts=p;comments=c;userService=u;}
 public void requireAdmin(){if(userService.current().role!=Role.ADMIN)throw UserService.forbidden("需要管理员权限");}
 @Transactional public ResponseEntity<Void> createReport(ReportInput input){User u=userService.current();if(reports.existsByReporterAndTargetTypeAndTargetId(u,input.targetType(),input.targetId()))throw UserService.bad("你已举报过该内容");if(input.targetType()==Report.TargetType.POST){Post p=posts.findById(input.targetId()).orElseThrow(()->UserService.notFound("帖子不存在"));if(p.deleted)throw UserService.notFound("帖子不存在");}else{Comment c=comments.findById(input.targetId()).orElseThrow(()->UserService.notFound("评论不存在"));if(c.deleted)throw UserService.notFound("评论不存在");}Report r=new Report();r.reporter=u;r.targetType=input.targetType();r.targetId=input.targetId();r.reason=input.reason();reports.save(r);return ResponseEntity.status(201).build();}
 public Page<ReportView> listReports(String status,int page,int size){requireAdmin();Pageable p=PageRequest.of(page,Math.min(size,50));if(status==null||status.isBlank())return reports.findAllByOrderByCreatedAtDesc(p).map(ReportView::of);Report.Status s;try{s=Report.Status.valueOf(status.toUpperCase());}catch(Exception e){throw UserService.bad("无效的状态");}return reports.findByStatusOrderByCreatedAtDesc(s,p).map(ReportView::of);}
 public Map<String,Long> reportCount(String status){requireAdmin();if(status==null||status.isBlank()){Map<String,Long> m=new HashMap<>();m.put("total",reports.count());m.put("pending",reports.countByStatus(Report.Status.PENDING));return m;}Report.Status s;try{s=Report.Status.valueOf(status.toUpperCase());}catch(Exception e){throw UserService.bad("无效的状态");}return Map.of("count",reports.countByStatus(s));}
 public List<ReportView> reportsForTarget(String type,Long id){requireAdmin();Report.TargetType t;try{t=Report.TargetType.valueOf(type.toUpperCase());}catch(Exception e){throw UserService.bad("无效的类型");}return reports.findByTargetTypeAndTargetId(t,id).stream().map(ReportView::of).toList();}
 @Transactional public ReportView handleReport(Long id,String status){requireAdmin();Report r=reports.findById(id).orElseThrow(()->UserService.notFound("举报记录不存在"));Report.Status s;try{s=Report.Status.valueOf(status.toUpperCase());}catch(Exception e){throw UserService.bad("无效的状态");}if(r.status!=Report.Status.PENDING)throw UserService.bad("该举报已处理");r.status=s;r.handledBy=userService.current();r.handledAt=Instant.now();return ReportView.of(reports.save(r));}
}
