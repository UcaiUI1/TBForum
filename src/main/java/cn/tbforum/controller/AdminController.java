package cn.tbforum.controller;
import cn.tbforum.dto.Dtos.*; import cn.tbforum.service.ReportService;
import org.springframework.data.domain.Page; import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @Validated @RequestMapping("/api") public class AdminController {
 private final ReportService reportService;
 public AdminController(ReportService r){reportService=r;}
 @GetMapping("/admin/reports") public Page<ReportView> listReports(@RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return reportService.listReports(status,page,size);}
 @GetMapping("/admin/reports/count") public Map<String,Long> reportCount(@RequestParam(required=false) String status){return reportService.reportCount(status);}
 @GetMapping("/admin/reports/target") public List<ReportView> reportsForTarget(@RequestParam String type,@RequestParam Long id){return reportService.reportsForTarget(type,id);}
 @PatchMapping("/admin/reports/{id}") public ReportView handleReport(@PathVariable Long id,@RequestParam String status){return reportService.handleReport(id,status);}
}
