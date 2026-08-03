package cn.tbforum.controller;
import cn.tbforum.dto.Dtos.*; import cn.tbforum.service.ForumService; import cn.tbforum.service.PostService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page; import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated; import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @Validated @RequestMapping("/api") public class ForumController {
 private final ForumService forumService; private final PostService postService;
 public ForumController(ForumService f,PostService p){forumService=f;postService=p;}
 @GetMapping("/forums") public Page<ForumView> listForums(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return forumService.listForums(page,size);}
 @PostMapping("/forums") public ResponseEntity<ForumView> createForum(@Valid @RequestBody ForumInput input){return forumService.createForum(input);}
 @GetMapping("/forums/{slug}") public ForumView forum(@PathVariable String slug){return ForumView.of(forumService.getForum(slug));}
 @PostMapping("/forums/{slug}/moderators/{username}") public ResponseEntity<Void> appoint(@PathVariable String slug,@PathVariable String username){return forumService.appointModerator(slug,username);}
 @DeleteMapping("/forums/{slug}/moderators/{username}") public ResponseEntity<Void> removeModerator(@PathVariable String slug,@PathVariable String username){return forumService.removeModerator(slug,username);}
 @GetMapping("/forums/{slug}/moderators") public List<UserView> listModerators(@PathVariable String slug){return forumService.listModerators(slug);}
 @GetMapping("/forums/{slug}/posts") public Page<PostView> listPosts(@PathVariable String slug,@RequestParam(defaultValue="latest") String order,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(required=false) Long seed){return postService.postsByForum(slug,order,page,size,seed);}
 @PostMapping("/forums/{slug}/posts") public ResponseEntity<PostView> createPost(@PathVariable String slug,@Valid @RequestBody PostInput input){return postService.createPost(slug,input);}
 @GetMapping("/forums/{slug}/report-reasons") public ReportReasons reportReasons(@PathVariable String slug){return forumService.reportReasons(slug);}
 @PatchMapping("/forums/{slug}/report-reasons") public ForumView updateReportReasons(@PathVariable String slug,@Valid @RequestBody CustomReasonsInput input){return forumService.updateReportReasons(slug,input);}
 @DeleteMapping("/forums/{slug}") public ResponseEntity<Void> deleteForum(@PathVariable String slug){return forumService.deleteForum(slug);}
}
