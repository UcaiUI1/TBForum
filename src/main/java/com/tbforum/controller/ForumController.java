package com.tbforum.controller;

import com.tbforum.model.dto.ForumCreateDTO;
import com.tbforum.model.entity.Forum;
import com.tbforum.service.ForumService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/forums")
public class ForumController {

    private final ForumService forumService;

    @Autowired
    public ForumController(ForumService forumService) {
        this.forumService = forumService;
    }

    @PostMapping
    public ResponseEntity<Forum> create(@RequestBody ForumCreateDTO dto) {
        Forum created = forumService.createForum(dto);
        return ResponseEntity.ok(created);
    }

    @GetMapping
    public ResponseEntity<List<Forum>> list() {
        return ResponseEntity.ok(forumService.listAll());
    }
}