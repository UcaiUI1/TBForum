package cn.tbforum.controller;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity; import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler; import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestControllerAdvice
public class WebExceptionHandler {
 private static final Logger log=LoggerFactory.getLogger(WebExceptionHandler.class);
 @ExceptionHandler(ResponseStatusException.class)
 public ResponseEntity<Map<String,Object>> handleResponseStatus(ResponseStatusException e){
  Map<String,Object> body=new LinkedHashMap<>();
  body.put("message",e.getReason());
  return ResponseEntity.status(e.getStatusCode()).body(body);
 }
 @ExceptionHandler(MethodArgumentNotValidException.class)
 public ResponseEntity<Map<String,Object>> handleValidation(MethodArgumentNotValidException e){
  String message=e.getBindingResult().getFieldErrors().stream().findFirst().map(fe->fe.getDefaultMessage()).orElse("参数校验失败");
  Map<String,Object> body=new LinkedHashMap<>();
  body.put("message",message);
  return ResponseEntity.status(400).body(body);
 }
 @ExceptionHandler(DataIntegrityViolationException.class)
 public ResponseEntity<Map<String,Object>> handleDataIntegrity(DataIntegrityViolationException e){
  Map<String,Object> body=new LinkedHashMap<>();
  body.put("message","数据冲突");
  return ResponseEntity.status(400).body(body);
 }
 @ExceptionHandler(Exception.class)
 public ResponseEntity<Map<String,Object>> handleGeneric(Exception e){
  log.error("未处理异常",e);
  Map<String,Object> body=new LinkedHashMap<>();
  body.put("message","服务器内部错误");
  return ResponseEntity.status(500).body(body);
 }
}
