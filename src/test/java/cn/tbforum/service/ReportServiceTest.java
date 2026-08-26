package cn.tbforum.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.tbforum.domain.Forum;
import cn.tbforum.domain.Post;
import cn.tbforum.domain.Report;
import cn.tbforum.domain.User;
import cn.tbforum.dto.Dtos.ReportInput;
import cn.tbforum.dto.Dtos.ReportView;
import cn.tbforum.repo.CommentRepository;
import cn.tbforum.repo.ForumRepository;
import cn.tbforum.repo.ModeratorRepository;
import cn.tbforum.repo.PostRepository;
import cn.tbforum.repo.ReportRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    ReportRepository reports;

    @Mock
    PostRepository posts;

    @Mock
    CommentRepository comments;

    @Mock
    UserService userService;

    @Mock
    ForumRepository forums;

    @Mock
    ModeratorRepository mods;

    @Mock
    AuditService audit;

    ReportService service;

    @BeforeEach
    void setUp() {
        service = new ReportService(reports, posts, comments, userService, forums, mods, audit);
    }

    private static User user(long id, String name) {
        User u = new User();
        u.id = id;
        u.username = name;
        return u;
    }

    private static Forum forum(long id, String slug) {
        Forum f = new Forum();
        f.id = id;
        f.slug = slug;
        return f;
    }

    @Test
    void listModerated_ownerForumMatchesById_notForbidden() {
        // 回归测试：此前用 List.contains 比较实体实例，同一行数据不同实例会误判 403
        User owner = user(1L, "owner");
        Forum forumA = forum(1L, "a");
        Forum forumACopy = forum(1L, "a");
        when(userService.current()).thenReturn(owner);
        when(forums.findByOwner(owner)).thenReturn(List.of(forumA));
        when(forums.findBySlugAndDeletedFalse("a")).thenReturn(Optional.of(forumACopy));
        when(reports.findModeratedByStatus(eq(Report.Status.PENDING), anyList(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        Page<ReportView> page = service.listModerated("PENDING", "a", 0, 15);

        assertNotNull(page);
        verify(reports).findModeratedByStatus(eq(Report.Status.PENDING), anyList(), any(Pageable.class));
    }

    @Test
    void handleModerated_reportOutsideScope_isForbidden() {
        User owner = user(1L, "owner");
        Forum owned = forum(1L, "a");
        Forum other = forum(2L, "b");
        Post p = new Post();
        p.id = 1L;
        p.forum = other;
        Report r = new Report();
        r.id = 1L;
        r.targetType = Report.TargetType.POST;
        r.targetId = 1L;
        r.status = Report.Status.PENDING;
        when(userService.current()).thenReturn(owner);
        when(forums.findByOwner(owner)).thenReturn(List.of(owned));
        when(reports.findById(1L)).thenReturn(Optional.of(r));
        when(posts.findById(1L)).thenReturn(Optional.of(p));

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> service.handleModerated(1L, "RESOLVED", ""));

        assertEquals(HttpStatus.FORBIDDEN.value(), ex.getStatusCode().value());
    }

    @Test
    void createReport_duplicateTarget_isRejected() {
        User u = user(1L, "alice");
        when(userService.current()).thenReturn(u);
        when(reports.existsByReporterAndTargetTypeAndTargetId(u, Report.TargetType.POST, 5L))
                .thenReturn(true);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.createReport(new ReportInput(Report.TargetType.POST, 5L, "ad")));

        assertEquals(HttpStatus.BAD_REQUEST.value(), ex.getStatusCode().value());
    }
}
