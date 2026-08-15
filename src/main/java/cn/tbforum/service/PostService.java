package cn.tbforum.service;
import cn.tbforum.domain.*; import cn.tbforum.dto.Dtos.*;
import cn.tbforum.repo.PostRepository; import cn.tbforum.repo.PostTagRepository; import cn.tbforum.repo.TagRepository; import cn.tbforum.repo.FavoriteRepository; import cn.tbforum.repo.ReportRepository; import cn.tbforum.repo.InteractionRepository;
import cn.tbforum.cache.CacheService; import cn.tbforum.notif.NotificationService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.*; import org.springframework.http.*;
import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
import java.time.Duration; import java.time.Instant; import java.util.*; import java.util.stream.Collectors;

@Service
public class PostService {
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(PostService.class);
 private final PostRepository posts; private final PostTagRepository postTags; private final TagRepository tags; private final FavoriteRepository favorites; private final InteractionRepository interactions; private final ReportRepository reports; private final AuditService audit; private final CacheService cache; private final com.fasterxml.jackson.databind.ObjectMapper mapper; private final UserService userService; private final ForumService forumService; private final NotificationService notif; private final cn.tbforum.config.AppProperties props; private final MeterRegistry meterRegistry;
 public PostService(PostRepository p,PostTagRepository pt,TagRepository t,FavoriteRepository f,InteractionRepository it,ReportRepository r,AuditService au,CacheService c,com.fasterxml.jackson.databind.ObjectMapper mp,UserService us,ForumService fs,NotificationService n,cn.tbforum.config.AppProperties pr,@Autowired(required=false) MeterRegistry mr){posts=p;postTags=pt;tags=t;favorites=f;interactions=it;reports=r;audit=au;cache=c;mapper=mp;userService=us;forumService=fs;notif=n;props=pr;meterRegistry=mr;}
 /** 查询帖子的标签名列表 */
 private List<String> tagNamesOf(Post p){return postTags.findByPost(p).stream().map(pt->pt.tag.name).toList();}
 /** 批量查询帖子的标签名（避免 N+1，按 post.id 查询而非 PostTag 主键） */
 private Map<Long,List<String>> tagNamesOf(List<Post> list){if(list.isEmpty())return Map.of();var all=postTags.findByPostIdIn(list.stream().map(p->p.id).toList());return all.stream().collect(Collectors.groupingBy(pt->pt.post.id,Collectors.mapping(pt->pt.tag.name,Collectors.toList())));}
 /** 封装 PostView 构建（含标签） */
 public PostView viewOf(Post p,User viewer){return PostView.of(p,viewer,favorites,interactions,tagNamesOf(p));}
 /** 热门评分：点赞 + 评论×2 + 浏览×0.01，除以 (年龄小时+2)^1.5 做时间衰减 */
 private double hotScore(Post p){long hours=Math.max(1,Duration.between(p.createdAt,Instant.now()).toHours());return (p.upvotes + p.commentCount*2.0 + p.views*0.01)/Math.pow(hours+2,1.5);}
 private Page<PostView> hotPage(List<Post> candidates,User viewer,int page,int pageSize){var sorted=candidates.stream().sorted(Comparator.comparingDouble(this::hotScore).reversed()).toList();int from=page*pageSize;int to=Math.min(from+pageSize,sorted.size());var slice=from>=sorted.size()?List.<Post>of():sorted.subList(from,to);var tagMap=tagNamesOf(slice);return new PageImpl<>(slice.stream().map(p->PostView.of(p,viewer,favorites,interactions,tagMap.getOrDefault(p.id,List.of()))).toList(),PageRequest.of(page,pageSize),sorted.size());}
 /** 从内容中解析 #标签（最多5个，2-30字符，去重） */
 private List<String> parseTags(String content){var found=new LinkedHashSet<String>();var p=java.util.regex.Pattern.compile("#([^#\\s]{"+props.getTag().getMinLength()+","+props.getTag().getMaxLength()+"})");var m=p.matcher(content);while(m.find()&&found.size()<props.getTag().getMaxPerPost())found.add(m.group(1));return new java.util.ArrayList<>(found);}
 /** 同步帖子标签（删除旧的，解析并创建新的）。事务由调用者 createPost/editPost 提供。tag.postCount 用原子 SQL 更新防并发丢失 */
 private void syncTags(Post p,String content){var old=postTags.findByPost(p);for(var pt:old)tags.incrementPostCount(pt.tag.id,-1);postTags.deleteByPost(p);for(String name:parseTags(content)){Tag t=tags.findByName(name).orElseGet(()->{Tag nt=new Tag();nt.name=name;return tags.save(nt);});PostTag pt=new PostTag();pt.post=p;pt.tag=t;postTags.save(pt);tags.incrementPostCount(t.id,1);}}
 public Post getPost(Long id){Post p=posts.findById(id).orElseThrow(()->UserService.notFound("帖子不存在"));if(p.deleted)throw UserService.notFound("帖子不存在");return p;}
 /** 转义 LIKE 查询中的特殊字符 */
 private String escapeLike(String s){return s==null?"":s.replaceAll("([%_\\\\])","\\\\$1");}
 /** 缓存前清除 viewer 相关字段（favorited），避免跨用户隐私泄漏 */
 private List<PostView> stripViewer(List<PostView> views){return views.stream().map(v->new PostView(v.id(),v.title(),v.content(),v.forum(),v.author(),v.authorAvatar(),v.createdAt(),v.views(),v.upvotes(),v.comments(),v.featured(),v.locked(),v.edited(),v.editedAt(),false,false,v.tags())).toList();}
 /** 缓存命中后按当前 viewer 重算 favorited */
 private List<PostView> applyViewer(List<PostView> views,User viewer){if(viewer==null)return views;return views.stream().map(v->{boolean fav=favorites.existsByUserAndPost(viewer,posts.getReferenceById(v.id()));boolean up=interactions.findByUserAndPost(viewer,posts.getReferenceById(v.id())).map(i->i.kind==Interaction.Kind.UP).orElse(false);return new PostView(v.id(),v.title(),v.content(),v.forum(),v.author(),v.authorAvatar(),v.createdAt(),v.views(),v.upvotes(),v.comments(),v.featured(),v.locked(),v.edited(),v.editedAt(),up,fav,v.tags());}).toList();}
 @Transactional public ResponseEntity<PostView> createPost(String slug,PostInput input){Post p=new Post();p.forum=forumService.getForum(slug);User actor=userService.current();userService.requireNotRestricted(actor);p.author=actor;p.title=input.title();p.content=input.content();posts.save(p);syncTags(p,input.content());notif.notifyMentions(p.content,p.author,p.id,null);cache.evictByPrefix("feed:");incrementCounter("tbforum.posts.created","forum",slug);return ResponseEntity.status(201).body(viewOf(p,userService.current()));}
 @Transactional public PostView editPost(Long id,PostInput input){Post p=getPost(id);User actor=userService.current();userService.requireNotRestricted(actor);if(!p.author.id.equals(actor.id))throw UserService.forbidden("只能编辑自己的帖子");p.title=input.title();p.content=input.content();p.edited=true;p.editedAt=Instant.now();posts.save(p);syncTags(p,input.content());cache.evictByPrefix("feed:");return viewOf(p,userService.current());}
 @Transactional public ResponseEntity<Void> deletePost(Long id){Post p=getPost(id);User u=userService.current();if(!p.author.id.equals(u.id))forumService.requireModerator(p.forum);p.deleted=true;posts.save(p);for(var pt:postTags.findByPost(p))tags.incrementPostCount(pt.tag.id,-1);reports.dismissPendingByTarget(Report.TargetType.POST,id,Instant.now());cache.evictByPrefix("feed:");audit.log(u,"DELETE_POST","POST",p.id,p.title);return ResponseEntity.noContent().build();}
 @Transactional public PostView feature(Long id,boolean value){Post p=getPost(id);forumService.requireModerator(p.forum);p.featured=value;posts.save(p);if(value)notif.notify(p.author,userService.current(),Notification.Kind.FEATURED,"你的帖子被设为精华",p.id,null);audit.log(userService.current(),value?"FEATURE_POST":"UNFEATURE_POST","POST",p.id,p.title);return viewOf(p,userService.current());}
 @Transactional public PostView lock(Long id,boolean value){Post p=getPost(id);forumService.requireModerator(p.forum);p.locked=value;posts.save(p);audit.log(userService.current(),value?"LOCK_POST":"UNLOCK_POST","POST",p.id,p.title);return viewOf(p,userService.current());}
 @Transactional public PostView viewPost(Long id,HttpServletResponse res){Post p=getPost(id);User viewer=userService.currentOrNull();String viewerKey=viewer!=null?("u:"+viewer.id):("ip:"+userService.clientIp());if(cache.tryIncrementView(viewerKey,id,props.getView().getDedupWindow())){posts.incrementViews(id,1);p.views++;}res.setHeader("Cache-Control","no-store");return viewOf(p,viewer);}
 /** 全站帖子流：latest（最新）/ hot（热门）/ featured（精华）/ fate（随缘） */
 public Page<PostView> feed(String order,int page,int size,Long seed){
     int pageSize=Math.min(size,50);
     User viewer=userService.currentOrNull();
     if("fate".equals(order)){
         // P3-2: 改用 DB 分页伪随机排序，避免全表加载 OOM
         Page<Post> result=seed!=null?posts.findFateBySeed(seed,PageRequest.of(page,pageSize)):posts.findFateRandom(PageRequest.of(page,pageSize));
         var tagMap=tagNamesOf(result.getContent());
         return result.map(p->PostView.of(p,viewer,favorites,interactions,tagMap.getOrDefault(p.id,List.of())));
     }
     if("featured".equals(order)){
         Page<Post> result=posts.findByFeaturedTrueAndDeletedFalse(PageRequest.of(page,pageSize,Sort.by(Sort.Order.desc("createdAt"))));
         Map<Long,List<String>> tagMap=tagNamesOf(result.getContent());
         return result.map(p->PostView.of(p,viewer,favorites,interactions,tagMap.getOrDefault(p.id,List.of())));
     }
     String cacheKey="feed:"+order+":"+page+":"+pageSize;
    if(cache.isAvailable()){String cached=cache.get(cacheKey);if(cached!=null){try{var node=mapper.readTree(cached);var content=mapper.readValue(node.get("content").traverse(),PostView[].class);var views=Arrays.asList(content);return new PageImpl<>(applyViewer(views,viewer),PageRequest.of(page,pageSize),node.get("total").asLong());}catch(Exception e){log.warn("cache read failed key={}",cacheKey,e);}}}
     if("hot".equals(order)){
         // P3-3: 改用 DB 分页，避免加载全部候选帖到内存
         Instant since=Instant.now().minusSeconds(7*86400);
         List<Post> candidates=posts.findHotCandidates(since,PageRequest.of(0,300));
         
         Page<PostView> result=hotPage(candidates,viewer,page,pageSize);
         if(cache.isAvailable()&&page==0){try{var obj=mapper.createObjectNode();obj.set("content",mapper.valueToTree(stripViewer(result.getContent())));obj.put("total",result.getTotalElements());cache.set(cacheKey,mapper.writeValueAsString(obj),props.getCache().getFeedTtl());}catch(Exception e){log.warn("cache write failed key={}",cacheKey,e);}}
         return result;
     }
     Page<Post> result=posts.findAllByDeletedFalseOrderByCreatedAtDesc(PageRequest.of(page,pageSize));
     Map<Long,List<String>> tagMap=tagNamesOf(result.getContent());
     Page<PostView> r=result.map(p->PostView.of(p,viewer,favorites,interactions,tagMap.getOrDefault(p.id,List.of())));
     if(cache.isAvailable()&&page==0){try{var obj=mapper.createObjectNode();obj.set("content",mapper.valueToTree(stripViewer(r.getContent())));obj.put("total",r.getTotalElements());cache.set(cacheKey,mapper.writeValueAsString(obj),props.getCache().getFeedTtl());}catch(Exception e){log.warn("cache write failed key={}",cacheKey,e);}}
     return r;
 }
 public Page<PostView> search(String q,int page,int size){User viewer=userService.currentOrNull();Page<Post> result=posts.search(escapeLike(q),PageRequest.of(page,Math.min(size,50),Sort.by("createdAt").descending()));var tagMap=tagNamesOf(result.getContent());return result.map(p->PostView.of(p,viewer,favorites,interactions,tagMap.getOrDefault(p.id,List.of())));}
 public Page<PostView> postsByForum(String slug,String order,int page,int size,Long seed){Forum f=forumService.getForum(slug);int pageSize=Math.min(size,50);User viewer=userService.currentOrNull();if("fate".equals(order)){// P3-2: 改用 DB 分页伪随机排序，避免全表加载 OOM
  Page<Post> result=seed!=null?posts.findFateByForumBySeed(f,seed,PageRequest.of(page,pageSize)):posts.findFateRandomByForum(f.id,PageRequest.of(page,pageSize));var tagMap=tagNamesOf(result.getContent());return result.map(p->PostView.of(p,viewer,favorites,interactions,tagMap.getOrDefault(p.id,List.of())));}if("hot".equals(order)){Instant hotSince=Instant.now().minusSeconds(7*86400);List<Post> hotCandidates=posts.findHotCandidatesByForum(f,hotSince,PageRequest.of(0,300));return hotPage(hotCandidates,viewer,page,pageSize);}Sort sort=switch(order){case "featured"->Sort.by(Sort.Order.desc("createdAt"));default->Sort.by(Sort.Order.desc("createdAt"));};Page<Post> result="featured".equals(order)?posts.findByForumAndFeaturedTrueAndDeletedFalse(f,PageRequest.of(page,pageSize,sort)):posts.findByForumAndDeletedFalse(f,PageRequest.of(page,pageSize,sort));var tagMap=tagNamesOf(result.getContent());return result.map(p->PostView.of(p,viewer,favorites,interactions,tagMap.getOrDefault(p.id,List.of())));}
 public Page<PostView> postsByTag(String name,int page,int size){Tag t=tags.findByName(name).orElseThrow(()->UserService.notFound("标签不存在"));User viewer=userService.currentOrNull();Page<Post> result=postTags.findPostsByTag(t,PageRequest.of(page,Math.min(size,50)));Map<Long,List<String>> tagMap=tagNamesOf(result.getContent());return result.map(p->PostView.of(p,viewer,favorites,interactions,tagMap.getOrDefault(p.id,List.of())));}
 public Page<PostView> userPosts(String username,int page,int size){User target=userService.findByUsername(username).orElseThrow(()->UserService.notFound("用户不存在"));User viewer=userService.currentOrNull();Page<Post> result=posts.findByAuthorAndDeletedFalse(target,PageRequest.of(page,Math.min(size,50),Sort.by("createdAt").descending()));var tagMap=tagNamesOf(result.getContent());return result.map(p->PostView.of(p,viewer,favorites,interactions,tagMap.getOrDefault(p.id,List.of())));}
 public Page<PostView> myFavorites(int page,int size){User u=userService.current();Page<Post> result=favorites.findPostsByUser(u,PageRequest.of(page,Math.min(size,50)));var tagMap=tagNamesOf(result.getContent());return result.map(p->PostView.of(p,u,favorites,interactions,tagMap.getOrDefault(p.id,List.of())));}
 /** 业务指标计数器：统计发帖等业务 QPS，tags 可选（如按版块维度） */
 private void incrementCounter(String name,String... tags){if(meterRegistry==null)return;try{meterRegistry.counter(name,tags).increment();}catch(Exception ignored){}}
}
