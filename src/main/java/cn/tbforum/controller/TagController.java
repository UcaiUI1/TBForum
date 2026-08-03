package cn.tbforum.controller;
import cn.tbforum.dto.Dtos.*; import cn.tbforum.repo.TagRepository;
import cn.tbforum.service.PostService;
import org.springframework.data.domain.Page; import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @Validated @RequestMapping("/api") public class TagController {
 private final TagRepository tags; private final PostService postService;
 public TagController(TagRepository t,PostService p){tags=t;postService=p;}
 @GetMapping("/tags") public List<TagView> popularTags(){return tags.findTop50ByOrderByPostCountDesc().stream().map(TagView::of).toList();}
 @GetMapping("/tags/{name}/posts") public Page<PostView> postsByTag(@PathVariable String name,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return postService.postsByTag(name,page,size);}
}
