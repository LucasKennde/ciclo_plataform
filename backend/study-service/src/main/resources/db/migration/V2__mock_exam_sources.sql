CREATE TABLE mock_exam_sources (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL,
    competition_id uuid NOT NULL REFERENCES competitions(id) ON DELETE CASCADE,
    version smallint NOT NULL,
    file_name varchar(255) NOT NULL,
    object_key varchar(1000) NOT NULL,
    size_bytes bigint NOT NULL,
    status varchar(30) NOT NULL,
    board varchar(120),
    question_count integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (competition_id, version)
);

CREATE INDEX idx_mock_exam_sources_workspace
    ON mock_exam_sources(workspace_id, competition_id, created_at DESC);

ALTER TABLE questions
    ADD COLUMN mock_exam_source_id uuid REFERENCES mock_exam_sources(id) ON DELETE SET NULL;

CREATE INDEX idx_questions_mock_exam_source
    ON questions(workspace_id, mock_exam_source_id, created_at);
