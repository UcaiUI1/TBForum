package cn.tbforum.controller;
import cn.tbforum.dto.Dtos.*; import cn.tbforum.service.PostService;
import jakarta.servlet.http.HttpServletResponse; import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page; import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated; import org.springframework.web.bind.annotation.*;

@RestController @Validated @RequestMapping("/api") public class PostController {
 private final PostService postService;
 public PostController(PostService p){postService=p;}
 @GetMapping("/posts/{id}") public PostView post(@PathVariable Long id,HttpServletResponse res){return postService.viewPost(id,res);}
 @PatchMapping("/posts/{id}") public PostView editPost(@PathVariable Long id,@Valid @RequestBody PostInput input){return postService.editPost(id,input);}
 @DeleteMapping("/posts/{id}") public ResponseEntity<Void> deletePost(@PathVariable Long id){return postService.deletePost(id);}
 @PatchMapping("/posts/{id}/featured") public PostView feature(@PathVariable Long id,@RequestParam boolean value){return postService.feature(id,value);}
 @PatchMapping("/posts/{id}/locked") public PostView lock(@PathVariable Long id,@RequestParam boolean value){return postService.lock(id,value);}
 @GetMapping("/posts/feed") public Page<PostView> feed(@RequestParam(defaultValue="latest") String order,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) Long seed){return postService.feed(order,page,size,seed);}
 @GetMapping("/search") public Page<PostView> search(@RequestParam @NotBlank String q,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return postService.search(q,page,size);}
}
