package cn.tbforum.controller;
import cn.tbforum.dto.Dtos.*; import cn.tbforum.service.ReportService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity; import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController @Validated @RequestMapping("/api") public class ReportController {
 private final ReportService reportService;
 public ReportController(ReportService r){reportService=r;}
 @PostMapping("/reports") public ResponseEntity<Void> report(@Valid @RequestBody ReportInput input){return reportService.createReport(input);}
}
