CREATE TABLE identity_settings (
  id smallint PRIMARY KEY CHECK (id = 1),
  email_verification_required boolean NOT NULL,
  updated_at timestamptz NOT NULL,
  updated_by varchar(100) NOT NULL
);

INSERT INTO identity_settings (
  id,
  email_verification_required,
  updated_at,
  updated_by
)
VALUES (1, false, now(), 'system');
