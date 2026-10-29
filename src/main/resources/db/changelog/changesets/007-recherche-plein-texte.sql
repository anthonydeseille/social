--liquibase formatted sql

--changeset deseille:007-recherche-plein-texte
--comment Recherche plein texte des articles : configuration française insensible aux accents, vecteur de recherche généré (numéro puis contenu) et index GIN.
CREATE EXTENSION IF NOT EXISTS unaccent;

CREATE TEXT SEARCH CONFIGURATION francais (COPY = french);
ALTER TEXT SEARCH CONFIGURATION francais
    ALTER MAPPING FOR hword, hword_part, word WITH unaccent, french_stem;

-- Contenu en clair (Code) sinon HTML débarrassé de ses balises (conventions)
ALTER TABLE article ADD COLUMN recherche tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('francais', coalesce(num, '')), 'A') ||
    setweight(to_tsvector('francais', coalesce(contenu_texte, regexp_replace(coalesce(contenu_html, ''), '<[^>]+>', ' ', 'g'))), 'B')
) STORED;

CREATE INDEX idx_article_recherche ON article USING GIN (recherche);

--rollback DROP INDEX idx_article_recherche;
--rollback ALTER TABLE article DROP COLUMN recherche;
--rollback DROP TEXT SEARCH CONFIGURATION francais;
