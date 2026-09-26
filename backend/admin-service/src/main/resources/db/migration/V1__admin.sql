CREATE TABLE platform_settings(id smallint PRIMARY KEY CHECK(id=1),product_name varchar(100) NOT NULL,support_email varchar(190) NOT NULL,description text NOT NULL,locale varchar(20) NOT NULL,timezone varchar(80) NOT NULL,maintenance_mode boolean NOT NULL,updated_at timestamptz NOT NULL,updated_by varchar(100) NOT NULL);
INSERT INTO platform_settings VALUES(1,'Ciclo','contato@ciclo.local','Preparação que se adapta a você.','pt-BR','America/Fortaleza',false,now(),'system');
CREATE TABLE admin_audit(id bigserial PRIMARY KEY,action varchar(80) NOT NULL,subject varchar(120),detail text,actor varchar(100) NOT NULL,created_at timestamptz NOT NULL);

