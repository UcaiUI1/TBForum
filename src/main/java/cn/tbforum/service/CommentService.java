package cn.tbforum.service;
import cn.tbforum.domain.*; import cn.tbforum.dto.Dtos.*;
import cn.tbforum.repo.CommentRepository; import cn.tbforum.repo.PostRepository; import cn.tbforum.repo.ReportRepository;
import cn.tbforum.cache.CacheService; import cn.tbforum.notif.NotificationService;
import org.springframework.data.domain.*; import org.springframework.http.*; import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional; import org.springframework.web.server.ResponseStatusException;
import java.time.Instant; import java.util.*;

@Service
public class CommentService {
 private final CommentRepository comments; private final PostRepository posts; private final PostService postService; private final ForumService forumService; private final UserService userService; private final NotificationService notif; private final CacheService cache; private final ReportRepository reports;
 public CommentService(CommentRepository c,PostRepository p,PostService ps,ForumService fs,UserService us,NotificationService n,CacheService cs,ReportRepository r){comments=c;posts=p;postService=ps;forumService=fs;userService=us;notif=n;cache=cs;reports=r;}
 /** P3-4: 评论树分页：按根评论分页，每页含根评论及其全部子孙 */
 public Page<CommentView> commentTree(Long postId,int page,int size){
  Post p=postService.getPost(postId);int pageSize=Math.min(size,50);
  Page<Comment> roots=comments.findRootsByPost(p,PageRequest.of(page,pageSize));
  if(roots.isEmpty())return new PageImpl<>(List.of(),roots.getPageable(),roots.getTotalElements());
  List<Long> rootIds=roots.getContent().stream().map(c->c.rootId).toList();
  List<Comment> all=comments.findByPostAndRootIdIn(p,rootIds);
  Map<Long,List<Comment>> groups=new HashMap<>();
  for(Comment c:all)groups.computeIfAbsent(c.parent==null?0L:c.parent.id,k->new ArrayList<>()).add(c);
  List<CommentView> views=tree(groups.getOrDefault(0L,List.of()),groups);
  return new PageImpl<>(views,roots.getPageable(),roots.getTotalElements());
 }
 private List<CommentView> tree(List<Comment> source,Map<Long,List<Comment>> groups){return source.stream().map(c->{CommentView cv=new CommentView(c.id,c.author.username,c.author.avatarUrl,c.deleted?"[该评论已删除]":c.content,c.depth,c.createdAt,c.edited,c.editedAt,c.pinned,tree(groups.getOrDefault(c.id,List.of()),groups));return cv;}).toList();}
 @Transactional public ResponseEntity<CommentView> createComment(Long id,CommentInput input){Post p=postService.getPost(id);if(p.locked)throw UserService.forbidden("帖子已锁定，无法评论");Comment c=new Comment();c.post=p;c.author=userService.current();c.content=input.content();if(input.parentId()!=null){c.parent=comments.findById(input.parentId()).orElseThrow(()->UserService.notFound("父评论不存在"));if(!c.parent.post.id.equals(p.id))throw UserService.bad("父评论不属于该帖子");c.depth=c.parent.depth+1;c.rootId=c.parent.rootId==null?c.parent.id:c.parent.rootId;}comments.save(c);if(c.rootId==null){c.rootId=c.id;comments.save(c);}posts.incrementCommentCount(id,1);p.commentCount++;cache.evictByPrefix("feed:");notif.notify(p.author,c.author,Notification.Kind.REPLY,"回复了你的帖子",p.id,c.id);if(c.parent!=null)notif.notify(c.parent.author,c.author,Notification.Kind.REPLY,"回复了你的评论",p.id,c.id);notif.notifyMentions(c.content,c.author,p.id,c.id);return ResponseEntity.status(201).body(new CommentView(c.id,c.author.username,c.author.avatarUrl,c.content,c.depth,c.createdAt,c.edited,c.editedAt,c.pinned,List.of()));}
 @Transactional public CommentView editComment(Long id,Long commentId,CommentInput input){Comment c=comments.findById(commentId).orElseThrow(()->UserService.notFound("评论不存在"));if(!c.post.id.equals(id))throw UserService.bad("评论不属于该帖子");if(!c.author.id.equals(userService.current().id))throw UserService.forbidden("只能编辑自己的评论");c.content=input.content();c.edited=true;c.editedAt=Instant.now();comments.save(c);return new CommentView(c.id,c.author.username,c.author.avatarUrl,c.content,c.depth,c.createdAt,c.edited,c.editedAt,c.pinned,List.of());}
 @Transactional public CommentView pinComment(Long id,Long commentId,boolean value){Post p=postService.getPost(id);forumService.requireModerator(p.forum);Comment c=comments.findById(commentId).orElseThrow(()->UserService.notFound("评论不存在"));if(!c.post.id.equals(id))throw UserService.bad("评论不属于该帖子");c.pinned=value;comments.save(c);return new CommentView(c.id,c.author.username,c.author.avatarUrl,c.content,c.depth,c.createdAt,c.edited,c.editedAt,c.pinned,List.of());}
 /** 软删评论：作者自删或版主/admin强删。子评论保留，显示"[已删除]"占位。同时关闭该评论的待处理举报 */
 @Transactional public ResponseEntity<Void> deleteComment(Long postId,Long commentId){Post p=postService.getPost(postId);Comment c=comments.findById(commentId).orElseThrow(()->UserService.notFound("评论不存在"));if(!c.post.id.equals(postId))throw UserService.bad("评论不属于该帖子");User u=userService.current();boolean isAuthor=c.author.id.equals(u.id);if(!isAuthor)forumService.requireModerator(p.forum);if(!c.deleted){c.deleted=true;c.content="[该评论已删除]";comments.save(c);posts.incrementCommentCount(postId,-1);p.commentCount=Math.max(0,p.commentCount-1);cache.evictByPrefix("feed:");reports.dismissPendingByTarget(Report.TargetType.COMMENT,commentId,Instant.now());}return ResponseEntity.noContent().build();}
}
