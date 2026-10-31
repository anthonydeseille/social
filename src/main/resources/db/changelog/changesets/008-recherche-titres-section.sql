--liquibase formatted sql

--changeset deseille:008-recherche-titres-section
--comment Titres des sections englobantes dans le vecteur de recherche (poids A, comme le numéro) : les mots d'un article de principe sont souvent dans le titre de sa section plutôt que dans son texte (« Congés payés », « Protection de la grossesse et de la maternité »). Colonne tenue à jour par l'ingestion (TitresSection) ; remplie ici pour les bases existantes.
ALTER TABLE article ADD COLUMN titres_section TEXT;

DROP INDEX idx_article_recherche;
ALTER TABLE article DROP COLUMN recherche;
ALTER TABLE article ADD COLUMN recherche tsvector GENERATED ALWAYS AS (
    setweight(to_tsvector('francais', coalesce(num, '')), 'A') ||
    setweight(to_tsvector('francais', coalesce(titres_section, '')), 'A') ||
    setweight(to_tsvector('francais', coalesce(contenu_texte, regexp_replace(coalesce(contenu_html, ''), '<[^>]+>', ' ', 'g'))), 'B')
) STORED;
CREATE INDEX idx_article_recherche ON article USING GIN (recherche);

WITH RECURSIVE chaine AS (
    SELECT s.id AS depart, s.parent_id, s.titre, 1 AS profondeur FROM section s
    UNION ALL
    SELECT c.depart, p.parent_id, p.titre, c.profondeur + 1 FROM chaine c JOIN section p ON p.id = c.parent_id
), titres AS (
    SELECT depart, string_agg(titre, ' > ' ORDER BY profondeur DESC) AS titres
    FROM chaine WHERE titre IS NOT NULL GROUP BY depart
)
UPDATE article a SET titres_section = t.titres FROM titres t WHERE a.section_id = t.depart;

--rollback DROP INDEX idx_article_recherche;
--rollback ALTER TABLE article DROP COLUMN recherche;
--rollback ALTER TABLE article ADD COLUMN recherche tsvector GENERATED ALWAYS AS (setweight(to_tsvector('francais', coalesce(num, '')), 'A') || setweight(to_tsvector('francais', coalesce(contenu_texte, regexp_replace(coalesce(contenu_html, ''), '<[^>]+>', ' ', 'g'))), 'B')) STORED;
--rollback CREATE INDEX idx_article_recherche ON article USING GIN (recherche);
--rollback ALTER TABLE article DROP COLUMN titres_section;
