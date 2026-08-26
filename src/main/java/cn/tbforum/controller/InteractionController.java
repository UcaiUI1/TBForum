package cn.tbforum.controller;

import cn.tbforum.dto.Dtos.*;
import cn.tbforum.service.InteractionService;
import cn.tbforum.service.PostService;
import cn.tbforum.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@RequestMapping("/api")
public class InteractionController {
    private final InteractionService interactionService;
    private final PostService postService;
    private final UserService userService;

    public InteractionController(InteractionService i, PostService p, UserService u) {
        interactionService = i;
        postService = p;
        userService = u;
    }

    @PostMapping("/posts/{id}/interactions")
    public ResponseEntity<PostView> interact(@PathVariable Long id, @Valid @RequestBody InteractionInput input) {
        return interactionService.interact(postService.getPost(id), userService.current(), input.kind());
    }

    @PostMapping("/posts/{id}/favorite")
    public ResponseEntity<PostView> favorite(@PathVariable Long id) {
        return interactionService.favorite(postService.getPost(id), userService.current());
    }

    @DeleteMapping("/posts/{id}/favorite")
    public ResponseEntity<PostView> unfavorite(@PathVariable Long id) {
        return interactionService.unfavorite(postService.getPost(id), userService.current());
    }
}
