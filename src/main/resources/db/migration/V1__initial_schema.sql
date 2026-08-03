-- ============================================================
-- Flyway initial schema migration: V1__initial_schema.sql
-- Target database: PostgreSQL
-- Generated from JPA entity analysis (cn.tbforum.domain)
--
-- Naming conventions (Hibernate CamelCaseToUnderscoresNamingStrategy):
--   - table name  = @Table(name=...) (explicit) or entity name snake_cased
--   - column name = field name snake_cased
--   - @ManyToOne  = {field_name_snake_case}_id (foreign key column)
--   - enum (@Enumerated(STRING)) = VARCHAR(255) unless length given
--   - primitive boolean/long/int = NOT NULL
--   - Long/Instant (object) = nullable unless @Column(nullable=false)
-- ============================================================

-- ------------------------------------------------------------
-- users  (User.java)
-- ------------------------------------------------------------
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(40)  NOT NULL,
    password_hash   VARCHAR(255) NOT NULL,
    verified        BOOLEAN      NOT NULL,
    role            VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    avatar_url      TEXT,
    bio             VARCHAR(500),
    signature       VARCHAR(100),
    CONSTRAINT uk_users_username UNIQUE (username)
);

-- ------------------------------------------------------------
-- forums  (Forum.java)
-- ------------------------------------------------------------
CREATE TABLE forums (
    id                      BIGSERIAL PRIMARY KEY,
    slug                    VARCHAR(64)  NOT NULL,
    name                    VARCHAR(100) NOT NULL,
    description             VARCHAR(500),
    owner_id                BIGINT NOT NULL REFERENCES users (id),
    created_at              TIMESTAMP NOT NULL,
    custom_report_reason1   VARCHAR(60),
    custom_report_reason2   VARCHAR(60),
    CONSTRAINT uk_forums_slug UNIQUE (slug)
);

-- ------------------------------------------------------------
-- posts  (Post.java)
-- ------------------------------------------------------------
CREATE TABLE posts (
    id              BIGSERIAL PRIMARY KEY,
    forum_id        BIGINT NOT NULL REFERENCES forums (id),
    author_id       BIGINT NOT NULL REFERENCES users (id),
    title           VARCHAR(180) NOT NULL,
    content         TEXT NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    views           BIGINT  NOT NULL,
    upvotes         BIGINT  NOT NULL,
    downvotes       BIGINT  NOT NULL,
    comment_count   BIGINT  NOT NULL,
    featured        BOOLEAN NOT NULL,
    deleted         BOOLEAN NOT NULL,
    locked          BOOLEAN NOT NULL,
    edited          BOOLEAN NOT NULL,
    edited_at       TIMESTAMP
);
CREATE INDEX idx_posts_forum_created ON posts (forum_id, created_at);
CREATE INDEX idx_posts_author ON posts (author_id, created_at);

-- ------------------------------------------------------------
-- comments  (Comment.java)
-- ------------------------------------------------------------
CREATE TABLE comments (
    id              BIGSERIAL PRIMARY KEY,
    post_id         BIGINT NOT NULL REFERENCES posts (id),
    author_id       BIGINT NOT NULL REFERENCES users (id),
    parent_id       BIGINT REFERENCES comments (id),
    root_id         BIGINT,
    depth           INTEGER NOT NULL,
    content         TEXT NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    deleted         BOOLEAN NOT NULL,
    edited          BOOLEAN NOT NULL,
    edited_at       TIMESTAMP,
    pinned          BOOLEAN NOT NULL
);
CREATE INDEX idx_comments_post   ON comments (post_id, created_at);
CREATE INDEX idx_comments_parent ON comments (parent_id);

-- ------------------------------------------------------------
-- tags  (Tag.java)
-- ------------------------------------------------------------
CREATE TABLE tags (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(30) NOT NULL,
    post_count      BIGINT NOT NULL,
    CONSTRAINT uk_tags_name UNIQUE (name)
);
CREATE INDEX idx_tag_name ON tags (name);

-- ------------------------------------------------------------
-- post_tags  (PostTag.java)
-- ------------------------------------------------------------
CREATE TABLE post_tags (
    id              BIGSERIAL PRIMARY KEY,
    post_id         BIGINT NOT NULL REFERENCES posts (id),
    tag_id          BIGINT NOT NULL REFERENCES tags (id),
    CONSTRAINT uk_post_tags_post_tag UNIQUE (post_id, tag_id)
);

-- ------------------------------------------------------------
-- interactions  (Interaction.java)
-- ------------------------------------------------------------
CREATE TABLE interactions (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users (id),
    post_id         BIGINT NOT NULL REFERENCES posts (id),
    kind            VARCHAR(255),
    CONSTRAINT uk_interactions_user_post UNIQUE (user_id, post_id)
);

-- ------------------------------------------------------------
-- follows  (Follow.java)
-- ------------------------------------------------------------
CREATE TABLE follows (
    id              BIGSERIAL PRIMARY KEY,
    follower_id     BIGINT NOT NULL REFERENCES users (id),
    followee_id     BIGINT NOT NULL REFERENCES users (id),
    created_at      TIMESTAMP NOT NULL,
    CONSTRAINT uk_follows_follower_followee UNIQUE (follower_id, followee_id)
);
CREATE INDEX idx_follow_follower ON follows (follower_id, created_at);
CREATE INDEX idx_follow_followee ON follows (followee_id, created_at);

-- ------------------------------------------------------------
-- favorites  (Favorite.java)
-- ------------------------------------------------------------
CREATE TABLE favorites (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users (id),
    post_id         BIGINT NOT NULL REFERENCES posts (id),
    created_at      TIMESTAMP NOT NULL,
    CONSTRAINT uk_favorites_user_post UNIQUE (user_id, post_id)
);
CREATE INDEX idx_favorite_user ON favorites (user_id, created_at);

-- ------------------------------------------------------------
-- notifications  (Notification.java)
-- ------------------------------------------------------------
CREATE TABLE notifications (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users (id),
    actor_id        BIGINT REFERENCES users (id),
    kind            VARCHAR(255) NOT NULL,
    text            VARCHAR(255) NOT NULL,
    post_id         BIGINT,
    comment_id      BIGINT,
    created_at      TIMESTAMP NOT NULL,
    read            BOOLEAN NOT NULL
);
CREATE INDEX idx_notif_user ON notifications (user_id, created_at);

-- ------------------------------------------------------------
-- forum_moderators  (ForumModerator.java)
-- ------------------------------------------------------------
CREATE TABLE forum_moderators (
    id              BIGSERIAL PRIMARY KEY,
    forum_id        BIGINT NOT NULL REFERENCES forums (id),
    user_id         BIGINT NOT NULL REFERENCES users (id),
    CONSTRAINT uk_forum_moderators_forum_user UNIQUE (forum_id, user_id)
);

-- ------------------------------------------------------------
-- reports  (Report.java)
-- ------------------------------------------------------------
CREATE TABLE reports (
    id              BIGSERIAL PRIMARY KEY,
    reporter_id     BIGINT NOT NULL REFERENCES users (id),
    target_type     VARCHAR(10) NOT NULL,
    target_id       BIGINT NOT NULL,
    reason          VARCHAR(80) NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    status          VARCHAR(10) NOT NULL,
    handled_by_id   BIGINT REFERENCES users (id),
    handled_at      TIMESTAMP,
    CONSTRAINT uk_reports_reporter_target UNIQUE (reporter_id, target_type, target_id)
);
CREATE INDEX idx_report_status ON reports (status, created_at);
CREATE INDEX idx_report_target ON reports (target_type, target_id);
