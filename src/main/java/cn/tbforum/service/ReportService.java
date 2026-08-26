package cn.tbforum.service;

import cn.tbforum.domain.*;
import cn.tbforum.dto.Dtos.*;
import cn.tbforum.repo.CommentRepository;
import cn.tbforum.repo.ForumRepository;
import cn.tbforum.repo.ModeratorRepository;
import cn.tbforum.repo.PostRepository;
import cn.tbforum.repo.ReportRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {
    private final ReportRepository reports;
    private final PostRepository posts;
    private final CommentRepository comments;
    private final ForumRepository forums;
    private final ModeratorRepository mods;
    private final UserService userService;
    private final AuditService audit;

    public ReportService(
            ReportRepository r,
            PostRepository p,
            CommentRepository c,
            UserService u,
            ForumRepository f,
            ModeratorRepository m,
            AuditService a) {
        reports = r;
        posts = p;
        comments = c;
        userService = u;
        forums = f;
        mods = m;
        audit = a;
    }

    public void requireAdmin() {
        if (userService.current().role != Role.ADMIN) throw UserService.forbidden("需要管理员权限");
    }
    /** 当前用户担任版主/小版主的版块集合 */
    private List<Forum> moderatedForums(User u) {
        List<Forum> list = new ArrayList<>(forums.findByOwner(u));
        for (var fm : mods.findByUser(u))
            if (list.stream().noneMatch(x -> x.id.equals(fm.forum.id))) list.add(fm.forum);
        return list;
    }
    /** 按版块 slug 过滤当前用户可管理的版块集合 */
    private List<Forum> resolveScope(String forumSlug) {
        List<Forum> fs = moderatedForums(userService.current());
        if (forumSlug == null || forumSlug.isBlank()) return fs;
        Forum f = forums.findBySlugAndDeletedFalse(forumSlug).orElse(null);
        if (f == null || fs.stream().noneMatch(x -> x.id.equals(f.id))) throw UserService.forbidden("无权管理该版块");
        return List.of(f);
    }

    @Transactional
    public ResponseEntity<Void> createReport(ReportInput input) {
        User u = userService.current();
        userService.requireNotRestricted(u);
        if (reports.existsByReporterAndTargetTypeAndTargetId(u, input.targetType(), input.targetId()))
            throw UserService.bad("你已举报过该内容");
        if (input.targetType() == Report.TargetType.POST) {
            Post p = posts.findById(input.targetId()).orElseThrow(() -> UserService.notFound("帖子不存在"));
            if (p.deleted) throw UserService.notFound("帖子不存在");
        } else {
            Comment c = comments.findById(input.targetId()).orElseThrow(() -> UserService.notFound("评论不存在"));
            if (c.deleted) throw UserService.notFound("评论不存在");
        }
        Report r = new Report();
        r.reporter = u;
        r.targetType = input.targetType();
        r.targetId = input.targetId();
        r.reason = input.reason();
        reports.save(r);
        return ResponseEntity.status(201).build();
    }

    public Page<ReportView> listReports(String status, int page, int size) {
        requireAdmin();
        Pageable p = PageRequest.of(page, Math.min(size, 50));
        if (status == null || status.isBlank())
            return reports.findAllByOrderByCreatedAtDesc(p).map(ReportView::of);
        Report.Status s;
        try {
            s = Report.Status.valueOf(status.toUpperCase());
        } catch (Exception e) {
            throw UserService.bad("无效的状态");
        }
        return reports.findByStatusOrderByCreatedAtDesc(s, p).map(ReportView::of);
    }
    /** 版主查看本版（或自己管辖范围内）的举报 */
    public Page<ReportView> listModerated(String status, String forumSlug, int page, int size) {
        List<Forum> fs = resolveScope(forumSlug);
        if (fs.isEmpty()) return new PageImpl<>(List.of());
        Pageable p = PageRequest.of(page, Math.min(size, 50));
        Page<Report> r;
        if (status == null || status.isBlank()) r = reports.findModeratedAll(fs, p);
        else {
            Report.Status s;
            try {
                s = Report.Status.valueOf(status.toUpperCase());
            } catch (Exception e) {
                throw UserService.bad("无效的状态");
            }
            r = reports.findModeratedByStatus(s, fs, p);
        }
        return r.map(ReportView::of);
    }

    public Map<String, Long> reportCount(String status) {
        requireAdmin();
        if (status == null || status.isBlank()) {
            Map<String, Long> m = new HashMap<>();
            m.put("total", reports.count());
            m.put("pending", reports.countByStatus(Report.Status.PENDING));
            return m;
        }
        Report.Status s;
        try {
            s = Report.Status.valueOf(status.toUpperCase());
        } catch (Exception e) {
            throw UserService.bad("无效的状态");
        }
        return Map.of("count", reports.countByStatus(s));
    }

    public Map<String, Long> moderatedCounts(String forumSlug) {
        List<Forum> fs = resolveScope(forumSlug);
        long pending = fs.isEmpty() ? 0 : reports.countModeratedPending(fs);
        long total = fs.isEmpty() ? 0 : reports.countModeratedAll(fs);
        return Map.of("total", total, "pending", pending);
    }

    public List<ReportView> reportsForTarget(String type, Long id) {
        requireAdmin();
        Report.TargetType t;
        try {
            t = Report.TargetType.valueOf(type.toUpperCase());
        } catch (Exception e) {
            throw UserService.bad("无效的类型");
        }
        return reports.findByTargetTypeAndTargetId(t, id).stream()
                .map(ReportView::of)
                .toList();
    }

    @Transactional
    public ReportView handleReport(Long id, String status) {
        requireAdmin();
        Report r = reports.findById(id).orElseThrow(() -> UserService.notFound("举报记录不存在"));
        Report.Status s;
        try {
            s = Report.Status.valueOf(status.toUpperCase());
        } catch (Exception e) {
            throw UserService.bad("无效的状态");
        }
        if (r.status != Report.Status.PENDING) throw UserService.bad("该举报已处理");
        r.status = s;
        r.handledBy = userService.current();
        r.handledAt = Instant.now();
        ReportView v = ReportView.of(reports.save(r));
        audit.log(
                userService.current(),
                s == Report.Status.RESOLVED ? "RESOLVE_REPORT" : "DISMISS_REPORT",
                "REPORT",
                r.id,
                r.reason);
        return v;
    }
    /** 版主处理本版举报：校验举报目标属于自己管辖的版块 */
    @Transactional
    public ReportView handleModerated(Long id, String status, String forumSlug) {
        User u = userService.current();
        List<Forum> fs = moderatedForums(u);
        Report r = reports.findById(id).orElseThrow(() -> UserService.notFound("举报记录不存在"));
        boolean inScope;
        if (r.targetType == Report.TargetType.POST) {
            Post p = posts.findById(r.targetId).orElse(null);
            inScope = p != null && fs.stream().anyMatch(x -> x.id.equals(p.forum.id));
        } else {
            Comment c = comments.findById(r.targetId).orElse(null);
            inScope = c != null && fs.stream().anyMatch(x -> x.id.equals(c.post.forum.id));
        }
        if (!inScope) throw UserService.forbidden("只有该版块的版主可处理此举报");
        Report.Status s;
        try {
            s = Report.Status.valueOf(status.toUpperCase());
        } catch (Exception e) {
            throw UserService.bad("无效的状态");
        }
        if (r.status != Report.Status.PENDING) throw UserService.bad("该举报已处理");
        r.status = s;
        r.handledBy = u;
        r.handledAt = Instant.now();
        ReportView v = ReportView.of(reports.save(r));
        audit.log(u, s == Report.Status.RESOLVED ? "RESOLVE_REPORT" : "DISMISS_REPORT", "REPORT", r.id, r.reason);
        return v;
    }
}
