CREATE TABLE IF NOT EXISTS user_style_profile (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL UNIQUE,
    explanation_style VARCHAR(128),
    preferred_depth VARCHAR(64),
    prefers_examples BOOLEAN DEFAULT FALSE,
    prefers_storytelling BOOLEAN DEFAULT FALSE,
    prefers_step_by_step BOOLEAN DEFAULT FALSE,
    avoidance JSON,
    summary TEXT,
    confidence DOUBLE DEFAULT 0.5,
    created_at DATETIME,
    updated_at DATETIME
);

CREATE TABLE IF NOT EXISTS reading_understanding_profile (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL,
    book_category VARCHAR(128) NOT NULL,
    understanding_level VARCHAR(64),
    learning_stage VARCHAR(64),
    strengths JSON,
    weaknesses JSON,
    preferred_explanation JSON,
    background_needs JSON,
    typical_questions JSON,
    summary TEXT,
    confidence DOUBLE DEFAULT 0.5,
    last_evidence_id BIGINT,
    evidence_count INT DEFAULT 0,
    created_at DATETIME,
    updated_at DATETIME,
    UNIQUE KEY uk_user_book_category (user_id, book_category)
);

CREATE TABLE IF NOT EXISTS profile_growth_evidence (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL,
    evidence_domain VARCHAR(64) NOT NULL,
    evidence_type VARCHAR(64) NOT NULL,
    book_category VARCHAR(128),
    related_book_id BIGINT,
    related_book_title VARCHAR(255),
    related_chapter_index INT,
    content TEXT NOT NULL,
    importance DOUBLE DEFAULT 0.5,
    status VARCHAR(32) DEFAULT 'active',
    created_at DATETIME,
    updated_at DATETIME
);

CREATE TABLE IF NOT EXISTS profile_update_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL,
    old_style_snapshot JSON,
    new_style_snapshot JSON,
    old_reading_snapshot JSON,
    new_reading_snapshot JSON,
    update_patch JSON,
    used_memory_ids JSON,
    used_evidence_ids JSON,
    update_reason TEXT,
    created_at DATETIME
);

CREATE TABLE IF NOT EXISTS profile_vector_index_mapping (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id VARCHAR(64) NOT NULL,
    source_table VARCHAR(64) NOT NULL,
    source_id BIGINT NOT NULL,
    vector_id VARCHAR(256) NOT NULL,
    vector_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) DEFAULT 'active',
    created_at DATETIME,
    updated_at DATETIME,
    UNIQUE KEY uk_profile_vector_source (source_table, source_id)
);
