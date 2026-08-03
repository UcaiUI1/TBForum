-- ============================================================
-- V2: 补全软删除字段（P2 阶段新增）
-- users.deleted  : 用户注销（软删）
-- forums.deleted : 版块下线（软删）
-- ============================================================

-- users 表新增 deleted 字段（用户注销功能）
ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE;

-- forums 表新增 deleted 字段（版块下线功能）
ALTER TABLE forums ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE;
