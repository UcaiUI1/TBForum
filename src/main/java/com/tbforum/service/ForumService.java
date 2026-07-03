package com.tbforum.service;

import com.tbforum.model.dto.ForumCreateDTO;
import com.tbforum.model.entity.Forum;
import com.tbforum.repository.ForumRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ForumService {

    private final ForumRepository forumRepository;

    @Autowired
    public ForumService(ForumRepository forumRepository) {
        this.forumRepository = forumRepository;
    }

    public Forum createForum(ForumCreateDTO dto) {
        Forum forum = new Forum();
        forum.setName(dto.getName());
        forum.setDescription(dto.getDescription());
        forum.setAvatarUrl(dto.getAvatarUrl());
        return forumRepository.save(forum);
    }

    public List<Forum> listAll() {
        return forumRepository.findAll();
    }
}