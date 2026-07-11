CREATE TABLE IF NOT EXISTS knowledge_concept (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    canonical_name VARCHAR(255) NOT NULL,
    normalized_name VARCHAR(255) NOT NULL,
    description TEXT,
    book_id BIGINT,
    first_chapter_index INT,
    status VARCHAR(32) NOT NULL,
    merged_to_concept_id BIGINT,
    created_at DATETIME,
    updated_at DATETIME,
    INDEX idx_concept_normalized_name (normalized_name),
    INDEX idx_concept_book_status (book_id, status)
);

CREATE TABLE IF NOT EXISTS concept_alias (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    concept_id BIGINT NOT NULL,
    alias_name VARCHAR(255) NOT NULL,
    normalized_alias_name VARCHAR(255) NOT NULL,
    source VARCHAR(64),
    confidence DOUBLE,
    created_at DATETIME,
    UNIQUE KEY uk_alias_concept_normalized (concept_id, normalized_alias_name),
    INDEX idx_alias_normalized_name (normalized_alias_name)
);

CREATE TABLE IF NOT EXISTS concept_source (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    concept_id BIGINT NOT NULL,
    book_id BIGINT,
    chapter_index INT,
    source_type VARCHAR(32) NOT NULL,
    source_text TEXT,
    source_ref VARCHAR(255),
    created_at DATETIME,
    INDEX idx_concept_source_concept (concept_id),
    INDEX idx_concept_source_book (book_id, chapter_index)
);

CREATE TABLE IF NOT EXISTS concept_candidate_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    candidate_name VARCHAR(255),
    matched_concept_id BIGINT,
    confidence DOUBLE,
    model_score DOUBLE,
    lexical_score DOUBLE,
    context_score DOUBLE,
    history_score DOUBLE,
    candidate_gap_score DOUBLE,
    status VARCHAR(32) NOT NULL,
    reason TEXT,
    created_at DATETIME,
    INDEX idx_candidate_event (event_id),
    INDEX idx_candidate_status_created (status, created_at)
);

CREATE TABLE IF NOT EXISTS concept_resolution_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL UNIQUE,
    user_id VARCHAR(64),
    book_id BIGINT,
    chapter_index INT,
    session_id VARCHAR(128),
    primary_concept_name VARCHAR(255),
    matched_concept_id BIGINT,
    candidate_concepts_json JSON,
    confidence DOUBLE,
    confidence_level VARCHAR(16),
    decision VARCHAR(32),
    score_breakdown_json JSON,
    context_evidence_json JSON,
    reason TEXT,
    question TEXT,
    selected_text TEXT,
    selected_context TEXT,
    recent_dialogue_summary TEXT,
    model_name VARCHAR(128),
    prompt_version VARCHAR(64),
    analyzer_version VARCHAR(64),
    created_at DATETIME,
    INDEX idx_resolution_user_book (user_id, book_id, chapter_index)
);

CREATE TABLE IF NOT EXISTS concept_merge_relation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_concept_id BIGINT NOT NULL,
    target_concept_id BIGINT NOT NULL,
    reason TEXT,
    created_at DATETIME,
    INDEX idx_merge_source (source_concept_id),
    INDEX idx_merge_target (target_concept_id)
);
