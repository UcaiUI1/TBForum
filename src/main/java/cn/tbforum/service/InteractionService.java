package cn.tbforum.service;

import cn.tbforum.cache.CacheService;
import cn.tbforum.domain.*;
import cn.tbforum.dto.Dtos.*;
import cn.tbforum.notif.NotificationService;
import cn.tbforum.repo.FavoriteRepository;
import cn.tbforum.repo.InteractionRepository;
import cn.tbforum.repo.PostRepository;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InteractionService {
    private final InteractionRepository interactions;
    private final PostRepository posts;
    private final FavoriteRepository favorites;
    private final PostService postService;
    private final NotificationService notif;
    private final CacheService cache;
    private final UserService userService;

    public InteractionService(
            InteractionRepository i,
            PostRepository p,
            FavoriteRepository f,
            PostService ps,
            NotificationService n,
            CacheService c,
            UserService us) {
        interactions = i;
        posts = p;
        favorites = f;
        postService = ps;
        notif = n;
        cache = c;
        userService = us;
    }

    @Transactional
    public ResponseEntity<PostView> interact(Post p, User u, Interaction.Kind kind) {
        userService.requireNotRestricted(u);
        var old = interactions.findByUserAndPost(u, p);
        if (old.isPresent()) {
            Interaction x = old.get();
            if (x.kind == Interaction.Kind.UP) {
                posts.incrementUpvotes(p.id, -1);
                p.upvotes--;
            }
            if (x.kind == Interaction.Kind.DOWN) {
                posts.incrementDownvotes(p.id, -1);
                p.downvotes--;
            }
            if (x.kind == kind) {
                interactions.delete(x);
                cache.evictByPrefix("feed:");
                return ResponseEntity.ok(postService.viewOf(p, u));
            }
            x.kind = kind;
        } else {
            Interaction x = new Interaction();
            x.user = u;
            x.post = p;
            x.kind = kind;
            interactions.save(x);
        }
        if (kind == Interaction.Kind.UP) {
            posts.incrementUpvotes(p.id, 1);
            p.upvotes++;
        }
        if (kind == Interaction.Kind.DOWN) {
            posts.incrementDownvotes(p.id, 1);
            p.downvotes++;
        }
        cache.evictByPrefix("feed:");
        if (kind == Interaction.Kind.UP) notif.notify(p.author, u, Notification.Kind.UPVOTE, "赞了你的帖子", p.id, null);
        return ResponseEntity.ok(postService.viewOf(p, u));
    }

    @Transactional
    public ResponseEntity<PostView> favorite(Post p, User u) {
        if (!favorites.existsByUserAndPost(u, p)) {
            Favorite f = new Favorite();
            f.user = u;
            f.post = p;
            favorites.save(f);
        }
        return ResponseEntity.status(201).body(postService.viewOf(p, u));
    }

    @Transactional
    public ResponseEntity<PostView> unfavorite(Post p, User u) {
        favorites.deleteByUserAndPost(u, p);
        return ResponseEntity.ok(postService.viewOf(p, u));
    }
}
