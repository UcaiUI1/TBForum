CREATE TABLE uploaded_files (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES users(id),
    purpose VARCHAR(20) NOT NULL CHECK (purpose IN ('AVATAR', 'POST_IMAGE')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'REFERENCED')),
    url VARCHAR(255) NOT NULL UNIQUE,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    reference_id BIGINT,
    created_at TIMESTAMP NOT NULL,
    referenced_at TIMESTAMP,
    expires_at TIMESTAMP
);

CREATE INDEX idx_uploaded_files_owner ON uploaded_files(owner_id);
CREATE INDEX idx_uploaded_files_reference ON uploaded_files(purpose, reference_id, status);
CREATE INDEX idx_uploaded_files_cleanup ON uploaded_files(status, expires_at);
