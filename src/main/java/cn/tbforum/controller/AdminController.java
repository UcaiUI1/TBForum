package cn.tbforum.controller;
import cn.tbforum.dto.Dtos.*; import cn.tbforum.service.ReportService; import cn.tbforum.service.UserService; import cn.tbforum.service.AuditService;
import org.springframework.data.domain.Page; import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @Validated @RequestMapping("/api") public class AdminController {
 private final ReportService reportService; private final UserService userService; private final AuditService auditService;
 public AdminController(ReportService r,UserService u,AuditService a){reportService=r;userService=u;auditService=a;}
 @GetMapping("/admin/reports") public Page<ReportView> listReports(@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return reportService.listReports(status,page,size);}
 @GetMapping("/admin/reports/count") public Map<String,Long> reportCount(@RequestParam(required=false) String status){return reportService.reportCount(status);}
 @GetMapping("/admin/reports/target") public List<ReportView> reportsForTarget(@RequestParam String type,@RequestParam Long id){return reportService.reportsForTarget(type,id);}
 @PatchMapping("/admin/reports/{id}") public ReportView handleReport(@PathVariable Long id,@RequestParam String status){return reportService.handleReport(id,status);}
 @PatchMapping("/admin/users/{username}/ban") public UserView banUser(@PathVariable String username,@RequestParam(defaultValue="7") int days){return userService.ban(username,days);}
 @PatchMapping("/admin/users/{username}/mute") public UserView muteUser(@PathVariable String username,@RequestParam(defaultValue="1") int days){return userService.mute(username,days);}
 @GetMapping("/admin/audit") public Page<AuditLogView> auditLogs(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){userService.requireAdmin();return auditService.list(page,size);}
}
