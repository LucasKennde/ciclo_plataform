CREATE TABLE users (
  id uuid PRIMARY KEY,
  email varchar(190) NOT NULL UNIQUE,
  display_name varchar(120) NOT NULL,
  password_hash varchar(255) NOT NULL,
  role varchar(30) NOT NULL,
  status varchar(40) NOT NULL,
  created_at timestamptz NOT NULL,
  verified_at timestamptz NULL
);
CREATE INDEX idx_users_status_created ON users(status, created_at DESC);

CREATE TABLE workspaces (
  id uuid PRIMARY KEY,
  name varchar(100) NOT NULL,
  owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL
);
CREATE INDEX idx_workspaces_owner ON workspaces(owner_id);

CREATE TABLE refresh_sessions (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash varchar(64) NOT NULL UNIQUE,
  expires_at timestamptz NOT NULL,
  consumed_at timestamptz NULL,
  revoked_at timestamptz NULL,
  created_at timestamptz NOT NULL
);
CREATE INDEX idx_refresh_sessions_user ON refresh_sessions(user_id, created_at DESC);

CREATE TABLE account_tokens (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind varchar(30) NOT NULL,
  token_hash varchar(64) NOT NULL UNIQUE,
  expires_at timestamptz NOT NULL,
  consumed_at timestamptz NULL,
  created_at timestamptz NOT NULL
);
CREATE INDEX idx_account_tokens_user_kind ON account_tokens(user_id, kind);

