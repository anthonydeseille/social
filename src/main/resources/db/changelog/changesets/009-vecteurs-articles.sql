--liquibase formatted sql

--changeset deseille:009-vecteurs-articles
--comment Recherche sémantique : vecteur de chaque article (embeddings, modèle local par défaut), pour retrouver les articles dont les mots diffèrent de ceux de la question. Extension pgvector (image pgvector/pgvector). L'empreinte (modèle et texte vectorisé) permet de ne recalculer que les articles modifiés.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE article_vecteur (
    article_id BIGINT PRIMARY KEY REFERENCES article (id) ON DELETE CASCADE,
    empreinte VARCHAR(64) NOT NULL,
    vecteur vector(1024) NOT NULL
);
CREATE INDEX idx_article_vecteur ON article_vecteur USING hnsw (vecteur vector_cosine_ops);
--rollback DROP TABLE article_vecteur;
