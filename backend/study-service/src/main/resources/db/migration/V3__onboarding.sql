CREATE TABLE onboarding_states (
  workspace_id uuid PRIMARY KEY,
  competition_id uuid NULL REFERENCES competitions(id) ON DELETE SET NULL,
  dismissed_at timestamptz NULL,
  completed_at timestamptz NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL
);

INSERT INTO onboarding_states (
  workspace_id,
  competition_id,
  completed_at,
  created_at,
  updated_at
)
SELECT
  workspace_id,
  (array_agg(competition_id ORDER BY created_at))[1],
  min(created_at),
  min(created_at),
  max(updated_at)
FROM study_plans
GROUP BY workspace_id;
