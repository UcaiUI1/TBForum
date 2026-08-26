package cn.tbforum.service;

import cn.tbforum.config.AppProperties;
import cn.tbforum.domain.Post;
import cn.tbforum.domain.UploadedFile.Purpose;
import cn.tbforum.domain.User;
import cn.tbforum.repo.PostRepository;
import cn.tbforum.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/** 在关闭静态目录直出前，将旧版本已有的头像和正文图片登记为受控引用。 */
@Component
public class LegacyUploadReconciler implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LegacyUploadReconciler.class);

    private final UserRepository users;
    private final PostRepository posts;
    private final ManagedUploadService uploads;
    private final AppProperties properties;

    public LegacyUploadReconciler(
            UserRepository users, PostRepository posts, ManagedUploadService uploads, AppProperties properties) {
        this.users = users;
        this.posts = posts;
        this.uploads = uploads;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getUpload().isLegacyReconciliationEnabled()) return;

        int batchSize = Math.max(1, properties.getUpload().getLegacyReconciliationBatchSize());
        int reconciled = reconcileUsers(batchSize) + reconcilePosts(batchSize);
        if (reconciled > 0) log.info("已登记 {} 个历史上传文件引用", reconciled);
    }

    private int reconcileUsers(int batchSize) {
        int reconciled = 0;
        long cursor = 0;
        while (true) {
            var batch = users.findByIdGreaterThanOrderByIdAsc(cursor, PageRequest.of(0, batchSize));
            for (User user : batch) {
                if (uploads.reconcileLegacyReference(user, Purpose.AVATAR, user.id, user.avatarUrl)) reconciled++;
            }
            if (batch.size() < batchSize) return reconciled;
            cursor = batch.get(batch.size() - 1).id;
        }
    }

    private int reconcilePosts(int batchSize) {
        int reconciled = 0;
        long cursor = 0;
        while (true) {
            var batch = posts.findByIdGreaterThanOrderByIdAsc(cursor, PageRequest.of(0, batchSize));
            for (Post post : batch) {
                for (String url : ManagedUploadService.extractManagedUrls(post.content)) {
                    if (uploads.reconcileLegacyReference(post.author, Purpose.POST_IMAGE, post.id, url)) reconciled++;
                }
            }
            if (batch.size() < batchSize) return reconciled;
            cursor = batch.get(batch.size() - 1).id;
        }
    }
}
