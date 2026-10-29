--liquibase formatted sql

--changeset deseille:006-article-suivi
--comment Suivi des articles : empreinte des métadonnées (section, ordre, dates, cid) et date de disparition du dump (null = présent).
ALTER TABLE article ADD COLUMN meta_hash VARCHAR(64);
ALTER TABLE article ADD COLUMN disparu_le TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_article_texte_present ON article (texte_id) WHERE disparu_le IS NULL;

--rollback DROP INDEX idx_article_texte_present;
--rollback ALTER TABLE article DROP COLUMN disparu_le;
--rollback ALTER TABLE article DROP COLUMN meta_hash;
