-- Um edital pode vir do catálogo oficial versionado (programas/seduc-2026.json) em vez da
-- extração por IA. Nesse caso não existe PDF para amarrar, e o NOT NULL impedia o Syllabus de
-- existir. O vínculo com documents() continua valendo quando há extração.
ALTER TABLE syllabi ALTER COLUMN document_id DROP NOT NULL;
