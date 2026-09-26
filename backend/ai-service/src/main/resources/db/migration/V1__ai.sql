CREATE TABLE ai_credentials(provider varchar(30) PRIMARY KEY,api_key_enc text NOT NULL,last4 varchar(4) NOT NULL,updated_at timestamptz NOT NULL,updated_by varchar(100) NOT NULL,last_test_ok boolean,last_test_error text);
CREATE TABLE ai_routes(operation varchar(60) PRIMARY KEY,provider varchar(30) NOT NULL,model varchar(120) NOT NULL,input_price numeric(16,6) NOT NULL,output_price numeric(16,6) NOT NULL,updated_at timestamptz NOT NULL,updated_by varchar(100) NOT NULL);
CREATE TABLE ai_policy(id smallint PRIMARY KEY CHECK(id=1),per_minute integer NOT NULL,per_hour integer NOT NULL,per_day integer NOT NULL,per_principal_tokens_day bigint NOT NULL,global_per_minute integer NOT NULL,global_tokens_day bigint NOT NULL,kill_switch boolean NOT NULL,updated_at timestamptz NOT NULL,updated_by varchar(100) NOT NULL);
INSERT INTO ai_policy VALUES(1,10,60,300,200000,120,2000000,false,now(),'system');
CREATE TABLE ai_usage(id uuid PRIMARY KEY,request_id uuid NOT NULL,principal varchar(100) NOT NULL,operation varchar(60) NOT NULL,provider varchar(30) NOT NULL,model varchar(120) NOT NULL,outcome varchar(30) NOT NULL,rule_code varchar(60),input_tokens bigint NOT NULL,output_tokens bigint NOT NULL,cost_usd numeric(16,8) NOT NULL,duration_ms bigint NOT NULL,created_at timestamptz NOT NULL);
CREATE INDEX idx_ai_usage_created ON ai_usage(created_at DESC);CREATE INDEX idx_ai_usage_principal ON ai_usage(principal,created_at DESC);
CREATE TABLE ai_blocks(principal varchar(100) PRIMARY KEY,reason text NOT NULL,blocked_until timestamptz,created_by varchar(100) NOT NULL,created_at timestamptz NOT NULL);
CREATE TABLE ai_audit(id bigserial PRIMARY KEY,action varchar(60) NOT NULL,subject varchar(120),detail text,actor varchar(100) NOT NULL,created_at timestamptz NOT NULL);

