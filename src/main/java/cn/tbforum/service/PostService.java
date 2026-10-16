package cn.tbforum.service;

import cn.tbforum.cache.CacheService;
import cn.tbforum.config.AppProperties;
import cn.tbforum.domain.*;
import cn.tbforum.dto.Dtos.*;
import cn.tbforum.notif.NotificationService;
import cn.tbforum.repo.FavoriteRepository;
import cn.tbforum.repo.InteractionRepository;
import cn.tbforum.repo.PostRepository;
import cn.tbforum.repo.PostTagRepository;
import cn.tbforum.repo.ReportRepository;
import cn.tbforum.repo.TagRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.ArrayList;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PostService {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(PostService.class);
    private final PostRepository posts;
    private final PostTagRepository postTags;
    private final TagRepository tags;
    private final FavoriteRepository favorites;
    private final InteractionRepository interactions;
    private final ReportRepository reports;
    private final AuditService audit;
    private final CacheService cache;
    private final ObjectMapper mapper;
    private final UserService userService;
    private final ForumService forumService;
    private final NotificationService notif;
    private final ManagedUploadService uploads;
    private final AppProperties props;
    private final MeterRegistry meterRegistry;

    public PostService(
            PostRepository p,
            PostTagRepository pt,
            TagRepository t,
            FavoriteRepository f,
            InteractionRepository it,
            ReportRepository r,
            AuditService au,
            CacheService c,
            ObjectMapper mp,
            UserService us,
            ForumService fs,
            NotificationService n,
            ManagedUploadService uploads,
            AppProperties pr,
            @Autowired(required = false) MeterRegistry mr) {
        posts = p;
        postTags = pt;
        tags = t;
        favorites = f;
        interactions = it;
        reports = r;
        audit = au;
        cache = c;
        mapper = mp;
        userService = us;
        forumService = fs;
        notif = n;
        this.uploads = uploads;
        props = pr;
        meterRegistry = mr;
    }
    /** 查询帖子的标签名列表 */
    private List<String> tagNamesOf(Post p) {
        return postTags.findByPost(p).stream().map(pt -> pt.tag.name).toList();
    }
    /** 批量查询帖子的标签名（避免 N+1，按 post.id 查询而非 PostTag 主键） */
    private Map<Long, List<String>> tagNamesOf(List<Post> list) {
        if (list.isEmpty()) return Map.of();
        var all = postTags.findTagNamesByPostIdIn(list.stream().map(p -> p.id).toList());
        return all.stream()
                .collect(Collectors.groupingBy(
                        PostTagRepository.PostTagName::getPostId,
                        Collectors.mapping(PostTagRepository.PostTagName::getTagName, Collectors.toList())));
    }
    /** 封装 PostView 构建（含标签） */
    public PostView viewOf(Post p, User viewer) {
        return PostView.of(p, viewer, favorites, interactions, tagNamesOf(p));
    }
    /** 热门评分：点赞 + 评论×2 + 浏览×0.01，除以 (年龄小时+2)^1.5 做时间衰减 */
    private double hotScore(Post p) {
        long hours = Math.max(1, Duration.between(p.createdAt, Instant.now()).toHours());
        return (p.upvotes + p.commentCount * 2.0 + p.views * 0.01) / Math.pow(hours + 2, 1.5);
    }

    private Page<PostView> hotPage(List<Post> candidates, User viewer, int page, int pageSize) {
        var sorted = candidates.stream()
                .sorted(Comparator.comparingDouble(this::hotScore).reversed())
                .toList();
        int from = page * pageSize;
        int to = Math.min(from + pageSize, sorted.size());
        var slice = from >= sorted.size() ? List.<Post>of() : sorted.subList(from, to);
        return new PageImpl<>(listViews(slice, viewer), PageRequest.of(page, pageSize), sorted.size());
    }
    /** 从内容中解析 #标签（最多5个，2-30字符，去重） */
    private List<String> parseTags(String content) {
        var found = new LinkedHashSet<String>();
        var p = java.util.regex.Pattern.compile("#([^#\\s]{" + props.getTag().getMinLength() + ","
                + props.getTag().getMaxLength() + "})");
        var m = p.matcher(content);
        while (m.find() && found.size() < props.getTag().getMaxPerPost()) found.add(m.group(1));
        return new ArrayList<>(found);
    }
    /** 同步帖子标签（删除旧的，解析并创建新的）。事务由调用者 createPost/editPost 提供。tag.postCount 用原子 SQL 更新防并发丢失 */
    private void syncTags(Post p, String content) {
        var old = postTags.findByPost(p);
        var names = parseTags(content);
        // Lock the union in name order, including removed tags. Keep display order unchanged.
        var affected = new TreeSet<>(names);
        old.forEach(pt -> affected.add(pt.tag.name));
        Map<String, Tag> resolved = new HashMap<>();
        for (String name : affected) {
            Tag tag = tags.findByNameForUpdate(name).orElseGet(() -> {
                tags.insertIfAbsent(name);
                return tags.findByNameForUpdate(name)
                        .orElseThrow(() -> new IllegalStateException("Tag creation did not resolve: " + name));
            });
            resolved.put(name, tag);
        }
        for (var pt : old) tags.incrementPostCount(pt.tag.id, -1);
        postTags.deleteByPost(p);
        for (String name : names) {
            Tag t = resolved.get(name);
            PostTag pt = new PostTag();
            pt.post = p;
            pt.tag = t;
            postTags.save(pt);
            tags.incrementPostCount(t.id, 1);
        }
    }

    public Post getPost(Long id) {
        Post p = posts.findById(id).orElseThrow(() -> UserService.notFound("帖子不存在"));
        if (p.deleted) throw UserService.notFound("帖子不存在");
        return p;
    }
    /** 转义 LIKE 查询中的特殊字符 */
    private String escapeLike(String s) {
        return s == null ? "" : s.replaceAll("([%_\\\\])", "\\\\$1");
    }
    /** 缓存命中后按当前 viewer 重算 favorited */
    private List<PostView> applyViewer(List<PostView> views, User viewer) {
        if (viewer == null || views.isEmpty()) return views;
        var ids = views.stream().map(PostView::id).toList();
        var favored = new HashSet<>(favorites.findPostIdsByUserIdAndPostIdIn(viewer.id, ids));
        var upvoted =
                new HashSet<>(interactions.findPostIdsByUserIdAndPostIdInAndKind(viewer.id, ids, Interaction.Kind.UP));
        return views.stream()
                .map(v -> {
                    boolean fav = favored.contains(v.id());
                    boolean up = upvoted.contains(v.id());
                    return new PostView(
                            v.id(),
                            v.title(),
                            v.content(),
                            v.forum(),
                            v.author(),
                            v.authorAvatar(),
                            v.createdAt(),
                            v.views(),
                            v.upvotes(),
                            v.comments(),
                            v.featured(),
                            v.locked(),
                            v.edited(),
                            v.editedAt(),
                            up,
                            fav,
                            v.tags());
                })
                .toList();
    }

    @Transactional
    public ResponseEntity<PostView> createPost(String slug, PostInput input) {
        Post p = new Post();
        p.forum = forumService.getForum(slug);
        User actor = userService.current();
        userService.requireNotRestricted(actor);
        p.author = actor;
        p.title = input.title();
        p.content = input.content();
        posts.save(p);
        uploads.syncPostImages(actor, p.id, p.content);
        syncTags(p, input.content());
        notif.notifyMentions(p.content, p.author, p.id, null);
        cache.evictByPrefix("feed:");
        incrementCounter("tbforum.posts.created", "forum", slug);
        return ResponseEntity.status(201).body(viewOf(p, userService.current()));
    }

    @Transactional
    public PostView editPost(Long id, PostInput input) {
        Post p = getPost(id);
        User actor = userService.current();
        userService.requireNotRestricted(actor);
        if (!p.author.id.equals(actor.id)) throw UserService.forbidden("只能编辑自己的帖子");
        p.title = input.title();
        p.content = input.content();
        p.edited = true;
        p.editedAt = Instant.now();
        posts.save(p);
        uploads.syncPostImages(actor, p.id, p.content);
        syncTags(p, input.content());
        cache.evictByPrefix("feed:");
        return viewOf(p, userService.current());
    }

    @Transactional
    public ResponseEntity<Void> deletePost(Long id) {
        Post p = getPost(id);
        User u = userService.current();
        if (!p.author.id.equals(u.id)) forumService.requireModerator(p.forum);
        p.deleted = true;
        posts.save(p);
        uploads.releasePostImages(p.id);
        var tagged = postTags.findByPost(p).stream()
                .sorted(Comparator.comparing(pt -> pt.tag.name))
                .toList();
        for (var pt : tagged) tags.incrementPostCount(pt.tag.id, -1);
        reports.dismissPendingByTarget(Report.TargetType.POST, id, Instant.now());
        cache.evictByPrefix("feed:");
        audit.log(u, "DELETE_POST", "POST", p.id, p.title);
        return ResponseEntity.noContent().build();
    }

    @Transactional
    public PostView feature(Long id, boolean value) {
        Post p = getPost(id);
        forumService.requireModerator(p.forum);
        p.featured = value;
        posts.save(p);
        if (value) notif.notify(p.author, userService.current(), Notification.Kind.FEATURED, "你的帖子被设为精华", p.id, null);
        audit.log(userService.current(), value ? "FEATURE_POST" : "UNFEATURE_POST", "POST", p.id, p.title);
        return viewOf(p, userService.current());
    }

    @Transactional
    public PostView lock(Long id, boolean value) {
        Post p = getPost(id);
        forumService.requireModerator(p.forum);
        p.locked = value;
        posts.save(p);
        audit.log(userService.current(), value ? "LOCK_POST" : "UNLOCK_POST", "POST", p.id, p.title);
        return viewOf(p, userService.current());
    }

    @Transactional
    public PostView viewPost(Long id, HttpServletResponse res) {
        Post p = getPost(id);
        User viewer = userService.currentOrNull();
        String viewerKey = viewer != null ? ("u:" + viewer.id) : ("ip:" + userService.clientIp());
        if (cache.tryIncrementView(viewerKey, id, props.getView().getDedupWindow())) {
            posts.incrementViews(id, 1);
            p.views++;
        }
        res.setHeader("Cache-Control", "no-store");
        return viewOf(p, viewer);
    }
    /** Convert the page with fixed-count tag and viewer-state queries. */
    private List<PostView> listViews(List<Post> list, User viewer) {
        var tagMap = tagNamesOf(list);
        var views = list.stream()
                .map(p -> {
                    PostView v = PostView.of(p);
                    return new PostView(
                            v.id(),
                            v.title(),
                            v.content(),
                            v.forum(),
                            v.author(),
                            v.authorAvatar(),
                            v.createdAt(),
                            v.views(),
                            v.upvotes(),
                            v.comments(),
                            v.featured(),
                            v.locked(),
                            v.edited(),
                            v.editedAt(),
                            false,
                            false,
                            tagMap.getOrDefault(p.id, List.of()));
                })
                .toList();
        return applyViewer(views, viewer);
    }

    private Page<PostView> pageViews(Page<Post> page, User viewer) {
        return new PageImpl<>(listViews(page.getContent(), viewer), page.getPageable(), page.getTotalElements());
    }

    /** 全站帖子流：latest（最新）/ hot（热门）/ featured（精华）/ fate（随缘） */
    public Page<PostView> feed(String order, int page, int size, Long seed) {
        int pageSize = Math.min(size, 50);
        User viewer = userService.currentOrNull();
        if ("fate".equals(order)) {
            Page<Post> result = seed != null
                    ? posts.findFateBySeed(seed, PageRequest.of(page, pageSize))
                    : posts.findFateRandom(PageRequest.of(page, pageSize));
            return pageViews(result, viewer);
        }
        if ("featured".equals(order)) {
            return pageViews(
                    posts.findByFeaturedTrueAndDeletedFalse(
                            PageRequest.of(page, pageSize, Sort.by(Sort.Order.desc("createdAt")))),
                    viewer);
        }
        if (page != 0) return uncachedFeed(order, page, pageSize, viewer);
        String cacheKey = "feed:" + order + ":" + page + ":" + pageSize;
        // Rebuild only common data; each caller applies its own state after coalescing.
        String cached = cache.getOrLoadFeed(cacheKey, props.getCache().getFeedTtl(), () -> {
            Page<PostView> result = uncachedFeed(order, page, pageSize, null);
            try {
                var node = mapper.createObjectNode();
                node.set("content", mapper.valueToTree(result.getContent()));
                node.put("total", result.getTotalElements());
                return mapper.writeValueAsString(node);
            } catch (Exception e) {
                log.warn("cache encoding failed key={}", cacheKey, e);
                return null;
            }
        });
        if (cached != null) {
            List<PostView> views = null;
            long total = 0;
            try {
                var node = mapper.readTree(cached);
                var content = mapper.readValue(node.get("content").traverse(), PostView[].class);
                views = Arrays.asList(content);
                total = node.get("total").asLong();
            } catch (Exception e) {
                log.warn("cache read failed key={}", cacheKey, e);
                cache.evict(cacheKey);
            }
            if (views != null) return new PageImpl<>(applyViewer(views, viewer), PageRequest.of(page, pageSize), total);
        }
        return uncachedFeed(order, page, pageSize, viewer);
    }

    private Page<PostView> uncachedFeed(String order, int page, int pageSize, User viewer) {
        if ("hot".equals(order)) {
            Instant since = Instant.now().minusSeconds(7 * 86400);
            return hotPage(posts.findHotCandidates(since, PageRequest.of(0, 300)), viewer, page, pageSize);
        }
        return pageViews(posts.findAllByDeletedFalseOrderByCreatedAtDesc(PageRequest.of(page, pageSize)), viewer);
    }

    public Page<PostView> search(String q, int page, int size) {
        User viewer = userService.currentOrNull();
        Page<Post> result = posts.search(
                escapeLike(q),
                PageRequest.of(page, Math.min(size, 50), Sort.by("createdAt").descending()));
        return pageViews(result, viewer);
    }

    public Page<PostView> postsByForum(String slug, String order, int page, int size, Long seed) {
        Forum f = forumService.getForum(slug);
        int pageSize = Math.min(size, 50);
        User viewer = userService.currentOrNull();
        if ("fate".equals(order)) { // P3-2: 改用 DB 分页伪随机排序，避免全表加载 OOM
            Page<Post> result = seed != null
                    ? posts.findFateByForumBySeed(f, seed, PageRequest.of(page, pageSize))
                    : posts.findFateRandomByForum(f.id, PageRequest.of(page, pageSize));
            return pageViews(result, viewer);
        }
        if ("hot".equals(order)) {
            Instant hotSince = Instant.now().minusSeconds(7 * 86400);
            List<Post> hotCandidates = posts.findHotCandidatesByForum(f, hotSince, PageRequest.of(0, 300));
            return hotPage(hotCandidates, viewer, page, pageSize);
        }
        Sort sort =
                switch (order) {
                    case "featured" -> Sort.by(Sort.Order.desc("createdAt"));
                    default -> Sort.by(Sort.Order.desc("createdAt"));
                };
        Page<Post> result = "featured".equals(order)
                ? posts.findByForumAndFeaturedTrueAndDeletedFalse(f, PageRequest.of(page, pageSize, sort))
                : posts.findByForumAndDeletedFalse(f, PageRequest.of(page, pageSize, sort));
        return pageViews(result, viewer);
    }

    public Page<PostView> postsByTag(String name, int page, int size) {
        Tag t = tags.findByName(name).orElseThrow(() -> UserService.notFound("标签不存在"));
        User viewer = userService.currentOrNull();
        Page<Post> result = postTags.findPostsByTag(t, PageRequest.of(page, Math.min(size, 50)));
        return pageViews(result, viewer);
    }

    public Page<PostView> userPosts(String username, int page, int size) {
        User target = userService.findByUsername(username).orElseThrow(() -> UserService.notFound("用户不存在"));
        User viewer = userService.currentOrNull();
        Page<Post> result = posts.findByAuthorAndDeletedFalse(
                target,
                PageRequest.of(page, Math.min(size, 50), Sort.by("createdAt").descending()));
        return pageViews(result, viewer);
    }

    public Page<PostView> myFavorites(int page, int size) {
        User u = userService.current();
        Page<Post> result = favorites.findPostsByUser(u, PageRequest.of(page, Math.min(size, 50)));
        return pageViews(result, u);
    }
    /** 业务指标计数器：统计发帖等业务 QPS，tags 可选（如按版块维度） */
    private void incrementCounter(String name, String... tags) {
        if (meterRegistry == null) return;
        try {
            meterRegistry.counter(name, tags).increment();
        } catch (Exception ignored) {
        }
    }
}
