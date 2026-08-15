package cn.tbforum.controller;
import cn.tbforum.dto.Dtos.*; import cn.tbforum.service.ReportService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity; import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController @Validated @RequestMapping("/api") public class ReportController {
 private final ReportService reportService;
 public ReportController(ReportService r){reportService=r;}
 @PostMapping("/reports") public ResponseEntity<Void> report(@Valid @RequestBody ReportInput input){return reportService.createReport(input);}
 @GetMapping("/moderator/reports") public Page<ReportView> moderatorReports(@RequestParam(defaultValue="") String forum,@RequestParam(defaultValue="PENDING") String status,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="15") int size){return reportService.listModerated(status,forum,page,size);}
 @GetMapping("/moderator/reports/count") public Map<String,Long> moderatorCounts(@RequestParam(defaultValue="") String forum){return reportService.moderatedCounts(forum);}
 @PatchMapping("/moderator/reports/{id}") public ReportView handleModerated(@PathVariable Long id,@RequestParam String status,@RequestParam(defaultValue="") String forum){return reportService.handleModerated(id,status,forum);}
}
