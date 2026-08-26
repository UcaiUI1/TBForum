package cn.tbforum.controller;

import cn.tbforum.dto.Dtos.*;
import cn.tbforum.service.PostService;
import cn.tbforum.service.UserService;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api")
public class FollowController {
    private final UserService userService;
    private final PostService postService;

    public FollowController(UserService u, PostService p) {
        userService = u;
        postService = p;
    }

    @GetMapping("/users/{username}")
    public UserDetailView userDetail(@PathVariable String username) {
        return userService.userDetail(username);
    }

    @GetMapping("/users/search")
    public List<UserView> searchUsers(@RequestParam @NotBlank String q) {
        return userService.searchUsers(q);
    }

    @PostMapping("/users/{username}/follow")
    public ResponseEntity<Void> follow(@PathVariable String username) {
        return userService.follow(username);
    }

    @DeleteMapping("/users/{username}/follow")
    public ResponseEntity<Void> unfollow(@PathVariable String username) {
        return userService.unfollow(username);
    }

    @GetMapping("/users/{username}/followers")
    public List<UserView> followers(@PathVariable String username) {
        return userService.followers(username);
    }

    @GetMapping("/users/{username}/following")
    public List<UserView> following(@PathVariable String username) {
        return userService.following(username);
    }

    @GetMapping("/users/me/favorites")
    public Page<PostView> myFavorites(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return postService.myFavorites(page, size);
    }

    @GetMapping("/users/{username}/posts")
    public Page<PostView> userPosts(
            @PathVariable String username,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return postService.userPosts(username, page, size);
    }
}
