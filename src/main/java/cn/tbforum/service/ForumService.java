package cn.tbforum.service;

import cn.tbforum.cache.CacheService;
import cn.tbforum.config.AppProperties;
import cn.tbforum.domain.*;
import cn.tbforum.dto.Dtos.*;
import cn.tbforum.repo.ForumRepository;
import cn.tbforum.repo.ModeratorRepository;
import cn.tbforum.repo.PostRepository;
import cn.tbforum.repo.PostTagRepository;
import cn.tbforum.repo.ReportRepository;
import cn.tbforum.repo.TagRepository;
import cn.tbforum.repo.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.slf4j.Logger;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ForumService {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(ForumService.class);
    private final ForumRepository forums;
    private final ModeratorRepository moderators;
    private final UserRepository users;
    private final CacheService cache;
    private final ObjectMapper mapper;
    private final UserService userService;
    private final PostRepository posts;
    private final PostTagRepository postTags;
    private final TagRepository tags;
    private final ReportRepository reports;
    private final AppProperties props;

    public ForumService(
            ForumRepository f,
            ModeratorRepository m,
            UserRepository u,
            CacheService c,
            ObjectMapper mp,
            UserService us,
            PostRepository p,
            PostTagRepository pt,
            TagRepository t,
            ReportRepository r,
            AppProperties pr) {
        forums = f;
        moderators = m;
        users = u;
        cache = c;
        mapper = mp;
        userService = us;
        posts = p;
        postTags = pt;
        tags = t;
        reports = r;
        props = pr;
    }

    public Forum getForum(String s) {
        return forums.findBySlugAndDeletedFalse(s).orElseThrow(() -> UserService.notFound("版块不存在"));
    }

    public void requireOwner(Forum f) {
        if (!f.owner.id.equals(userService.current().id)) throw UserService.forbidden("仅版主可执行此操作");
    }

    public void requireModerator(Forum f) {
        User u = userService.current();
        if (!f.owner.id.equals(u.id) && !moderators.existsByForumAndUser(f, u) && u.role != Role.ADMIN)
            throw UserService.forbidden("需要版主或小版主权限");
    }

    public Page<ForumView> listForums(int page, int size) {
        String cacheKey = "forums:list:" + page + ":" + Math.min(size, 50);
        String cached = cache.get(cacheKey);
        if (cached != null) {
            try {
                var node = mapper.readTree(cached);
                var content = mapper.readValue(node.get("content").traverse(), ForumView[].class);
                return new PageImpl<>(
                        Arrays.asList(content),
                        PageRequest.of(page, Math.min(size, 50)),
                        node.get("total").asLong());
            } catch (Exception e) {
                log.warn("forum cache read failed key={}", cacheKey, e);
            }
        }
        Page<ForumView> r = forums.findByDeletedFalse(PageRequest.of(
                        page, Math.min(size, 50), Sort.by("createdAt").descending()))
                .map(ForumView::of);
        if (page == 0 && size <= 20) {
            try {
                var obj = mapper.createObjectNode();
                obj.set("content", mapper.valueToTree(r.getContent()));
                obj.put("total", r.getTotalElements());
                cache.set(cacheKey, mapper.writeValueAsString(obj), Duration.ofMinutes(10));
            } catch (Exception e) {
                log.warn("forum cache write failed key={}", cacheKey, e);
            }
        }
        return r;
    }

    public ResponseEntity<ForumView> createForum(ForumInput input) {
        User u = userService.current();
        if (!u.verified) throw UserService.forbidden("需完成实名认证才可创建版块");
        if (forums.findBySlugAndDeletedFalse(input.slug()).isPresent()) throw UserService.bad("版块标识已存在");
        Forum f = new Forum();
        f.name = input.name();
        f.slug = input.slug();
        f.description = input.description();
        f.owner = u;
        ForumView v = ForumView.of(forums.save(f));
        cache.evictByPrefix("forums:list:");
        return ResponseEntity.status(201).body(v);
    }

    @Transactional
    public ResponseEntity<Void> appointModerator(String slug, String username) {
        Forum f = getForum(slug);
        requireOwner(f);
        User u = users.findByUsername(username).orElseThrow(() -> UserService.notFound("用户不存在"));
        if (!u.verified) throw UserService.bad("小版主需完成实名认证");
        if (moderators.existsByForumAndUser(f, u))
            return ResponseEntity.noContent().build();
        if (moderators.countByForum(f) >= props.getModerator().getMaxPerForum()) throw UserService.bad("最多两名小版主");
        ForumModerator m = new ForumModerator();
        m.forum = f;
        m.user = u;
        moderators.save(m);
        return ResponseEntity.status(201).build();
    }

    @Transactional
    public ResponseEntity<Void> removeModerator(String slug, String username) {
        Forum f = getForum(slug);
        requireOwner(f);
        User u = users.findByUsername(username).orElseThrow(() -> UserService.notFound("用户不存在"));
        moderators.deleteByForumAndUser(f, u);
        return ResponseEntity.noContent().build();
    }

    public List<UserView> listModerators(String slug) {
        Forum f = getForum(slug);
        return moderators.findByForum(f).stream().map(m -> UserView.of(m.user)).toList();
    }

    public ReportReasons reportReasons(String slug) {
        Forum f = getForum(slug);
        List<String> custom = new ArrayList<>();
        if (f.customReportReason1 != null && !f.customReportReason1.isBlank()) custom.add(f.customReportReason1);
        if (f.customReportReason2 != null && !f.customReportReason2.isBlank()) custom.add(f.customReportReason2);
        return new ReportReasons(List.of("垃圾广告/营销", "人身攻击/辱骂", "色情低俗", "违法违规", "造谣传谣", "引战/恶意刷屏", "其他"), custom);
    }

    @Transactional
    public ForumView updateReportReasons(String slug, CustomReasonsInput input) {
        Forum f = getForum(slug);
        requireModerator(f);
        String r1 = input.reason1() == null ? "" : input.reason1().trim();
        String r2 = input.reason2() == null ? "" : input.reason2().trim();
        if (r1.length() > 60 || r2.length() > 60) throw UserService.bad("举报原因长度不能超过60");
        if (!r1.isEmpty() && r1.equals(r2)) throw UserService.bad("两个特色原因不能相同");
        f.customReportReason1 = r1.isEmpty() ? null : r1;
        f.customReportReason2 = r2.isEmpty() ? null : r2;
        return ForumView.of(forums.save(f));
    }
    /** 下线版块：仅版主或管理员可执行。级联软删版块下所有帖子，同步扣减标签计数、关闭关联举报 */
    @Transactional
    public ResponseEntity<Void> deleteForum(String slug) {
        Forum f = getForum(slug);
        User u = userService.current();
        if (!f.owner.id.equals(u.id) && u.role != Role.ADMIN) throw UserService.forbidden("仅版主或管理员可下线版块");
        List<Post> forumPosts = posts.findByForumAndDeletedFalse(f);
        for (Post p : forumPosts) {
            for (var pt : postTags.findByPost(p)) tags.incrementPostCount(pt.tag.id, -1);
            reports.dismissPendingByTarget(Report.TargetType.POST, p.id, Instant.now());
        }
        posts.softDeleteByForum(f);
        f.deleted = true;
        forums.save(f);
        cache.evictByPrefix("feed:");
        cache.evictByPrefix("forums:list:");
        return ResponseEntity.noContent().build();
    }
}
