package com.tbforum.model.dto;

public class ForumCreateDTO {
    private String name;
    private String description;
    private String avatarUrl;

    public ForumCreateDTO() {}

    public ForumCreateDTO(String name, String description, String avatarUrl) {
        this.name = name;
        this.description = description;
        this.avatarUrl = avatarUrl;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
}