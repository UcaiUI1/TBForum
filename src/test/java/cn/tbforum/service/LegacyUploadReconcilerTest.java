package cn.tbforum.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.tbforum.config.AppProperties;
import cn.tbforum.domain.Post;
import cn.tbforum.domain.UploadedFile.Purpose;
import cn.tbforum.domain.User;
import cn.tbforum.repo.PostRepository;
import cn.tbforum.repo.UserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class LegacyUploadReconcilerTest {
    @Mock
    UserRepository users;

    @Mock
    PostRepository posts;

    @Mock
    ManagedUploadService uploads;

    @Test
    void reconcilesAvatarAndDeduplicatedPostImagesInBatches() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getUpload().setLegacyReconciliationBatchSize(10);
        User alice = user(7L, "/uploads/avatar_7_old.png");
        Post post = post(12L, alice, "![one](/uploads/post_7_old.png) ![copy](/uploads/post_7_old.png)");
        when(users.findByIdGreaterThanOrderByIdAsc(any(Long.class), any(Pageable.class)))
                .thenReturn(List.of(alice));
        when(posts.findByIdGreaterThanOrderByIdAsc(any(Long.class), any(Pageable.class)))
                .thenReturn(List.of(post));

        new LegacyUploadReconciler(users, posts, uploads, properties).run(new DefaultApplicationArguments());

        verify(uploads).reconcileLegacyReference(alice, Purpose.AVATAR, 7L, "/uploads/avatar_7_old.png");
        verify(uploads).reconcileLegacyReference(alice, Purpose.POST_IMAGE, 12L, "/uploads/post_7_old.png");
    }

    @Test
    void canDisableStartupReconciliation() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getUpload().setLegacyReconciliationEnabled(false);

        new LegacyUploadReconciler(users, posts, uploads, properties).run(new DefaultApplicationArguments());

        verify(users, org.mockito.Mockito.never())
                .findByIdGreaterThanOrderByIdAsc(any(Long.class), any(Pageable.class));
    }

    private static User user(long id, String avatarUrl) {
        User user = new User();
        user.id = id;
        user.avatarUrl = avatarUrl;
        return user;
    }

    private static Post post(long id, User author, String content) {
        Post post = new Post();
        post.id = id;
        post.author = author;
        post.content = content;
        return post;
    }
}
