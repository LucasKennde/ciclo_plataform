-- Catálogo de modelos passa a ser dado administrável, não uma constante em Java:
-- sem esta tabela o admin não consegue registrar o modelo que realmente usa.
CREATE TABLE ai_models (
  provider varchar(30) NOT NULL,
  model varchar(120) NOT NULL,
  label varchar(120) NOT NULL,
  input_price numeric(16,6) NOT NULL CHECK (input_price >= 0),
  output_price numeric(16,6) NOT NULL CHECK (output_price >= 0),
  operations varchar(400) NOT NULL,
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  updated_by varchar(100) NOT NULL,
  PRIMARY KEY (provider, model)
);

CREATE INDEX idx_ai_models_provider ON ai_models(provider);

-- operações aplicáveis: SYLLABUS_EXTRACTION, MOCK_EXAM_EXTRACTION,
-- QUESTION_CLASSIFICATION e QUESTION_GENERATION (todas servem a todas as quatro).
INSERT INTO ai_models (provider,model,label,input_price,output_price,operations,created_at,updated_at,updated_by) VALUES
('OPENAI','gpt-4.1','GPT-4.1',2.00,8.00,'SYLLABUS_EXTRACTION,MOCK_EXAM_EXTRACTION,QUESTION_CLASSIFICATION,QUESTION_GENERATION',now(),now(),'seed'),
('OPENAI','gpt-4.1-mini','GPT-4.1 mini',0.40,1.60,'SYLLABUS_EXTRACTION,MOCK_EXAM_EXTRACTION,QUESTION_CLASSIFICATION,QUESTION_GENERATION',now(),now(),'seed'),
('OPENAI','gpt-4o','GPT-4o',2.50,10.00,'SYLLABUS_EXTRACTION,MOCK_EXAM_EXTRACTION,QUESTION_CLASSIFICATION,QUESTION_GENERATION',now(),now(),'seed'),
('OPENAI','gpt-4o-mini','GPT-4o mini',0.15,0.60,'SYLLABUS_EXTRACTION,MOCK_EXAM_EXTRACTION,QUESTION_CLASSIFICATION,QUESTION_GENERATION',now(),now(),'seed'),
('OPENAI','o4-mini','o4-mini',1.10,4.40,'SYLLABUS_EXTRACTION,MOCK_EXAM_EXTRACTION,QUESTION_CLASSIFICATION,QUESTION_GENERATION',now(),now(),'seed'),
('ANTHROPIC','claude-sonnet-4-5','Claude Sonnet 4.5',3.00,15.00,'SYLLABUS_EXTRACTION,MOCK_EXAM_EXTRACTION,QUESTION_CLASSIFICATION,QUESTION_GENERATION',now(),now(),'seed'),
('ANTHROPIC','claude-haiku-4-5','Claude Haiku 4.5',1.00,5.00,'SYLLABUS_EXTRACTION,MOCK_EXAM_EXTRACTION,QUESTION_CLASSIFICATION,QUESTION_GENERATION',now(),now(),'seed'),
('ANTHROPIC','claude-opus-4-1','Claude Opus 4.1',15.00,75.00,'SYLLABUS_EXTRACTION,MOCK_EXAM_EXTRACTION,QUESTION_CLASSIFICATION,QUESTION_GENERATION',now(),now(),'seed'),
('ANTHROPIC','claude-3-5-haiku-latest','Claude 3.5 Haiku',0.80,4.00,'SYLLABUS_EXTRACTION,MOCK_EXAM_EXTRACTION,QUESTION_CLASSIFICATION,QUESTION_GENERATION',now(),now(),'seed'),
('GEMINI','gemini-2.5-pro','Gemini 2.5 Pro',1.25,10.00,'SYLLABUS_EXTRACTION,MOCK_EXAM_EXTRACTION,QUESTION_CLASSIFICATION,QUESTION_GENERATION',now(),now(),'seed'),
('GEMINI','gemini-2.5-flash','Gemini 2.5 Flash',0.30,2.50,'SYLLABUS_EXTRACTION,MOCK_EXAM_EXTRACTION,QUESTION_CLASSIFICATION,QUESTION_GENERATION',now(),now(),'seed'),
('GEMINI','gemini-2.5-flash-lite','Gemini 2.5 Flash-Lite',0.10,0.40,'SYLLABUS_EXTRACTION,MOCK_EXAM_EXTRACTION,QUESTION_CLASSIFICATION,QUESTION_GENERATION',now(),now(),'seed'),
('GEMINI','gemini-2.0-flash','Gemini 2.0 Flash',0.10,0.40,'SYLLABUS_EXTRACTION,MOCK_EXAM_EXTRACTION,QUESTION_CLASSIFICATION,QUESTION_GENERATION',now(),now(),'seed');
