package cn.tbforum.controller;
import cn.tbforum.dto.Dtos.*; import cn.tbforum.service.CommentService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page; import org.springframework.http.ResponseEntity; import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @Validated @RequestMapping("/api") public class CommentController {
 private final CommentService commentService;
 public CommentController(CommentService c){commentService=c;}
 @GetMapping("/posts/{id}/comments") public Page<CommentView> commentTree(@PathVariable Long id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size){return commentService.commentTree(id,page,size);}
 @PostMapping("/posts/{id}/comments") public ResponseEntity<CommentView> comment(@PathVariable Long id,@Valid @RequestBody CommentInput input){return commentService.createComment(id,input);}
 @PatchMapping("/posts/{id}/comments/{commentId}") public CommentView editComment(@PathVariable Long id,@PathVariable Long commentId,@Valid @RequestBody CommentInput input){return commentService.editComment(id,commentId,input);}
 @PatchMapping("/posts/{id}/comments/{commentId}/pinned") public CommentView pinComment(@PathVariable Long id,@PathVariable Long commentId,@RequestParam boolean value){return commentService.pinComment(id,commentId,value);}
 @DeleteMapping("/posts/{id}/comments/{commentId}") public ResponseEntity<Void> deleteComment(@PathVariable Long id,@PathVariable Long commentId){return commentService.deleteComment(id,commentId);}
}
