package fr.deseille.social.corpus;

import java.time.Instant;

/**
 * Projection légère d'un article, suffisante pour classer un changement à l'ingestion sans
 * charger l'entité complète (contenu HTML, données brutes JSONB).
 */
public record EtatArticle(String sourceId, String cid, EtatJuridique etat, String contenuHash,
                          String metaHash, Instant disparuLe) {
}
