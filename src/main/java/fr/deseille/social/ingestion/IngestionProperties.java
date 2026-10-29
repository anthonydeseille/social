package fr.deseille.social.ingestion;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration de l'ingestion (préfixe {@code droits.ingestion}).
 *
 * @param baseUrl base des URL sources (ex. {@code https://raw.githubusercontent.com}, ou
 *                un {@code file:/…} pointant des fixtures en test)
 * @param cacheDir répertoire de cache local des fichiers téléchargés
 * @param sources  sources de corpus à ingérer, dans l'ordre
 * @param tailleChunk nombre de nœuds écrits par transaction (500 par défaut)
 */
@ConfigurationProperties(prefix = "droits.ingestion")
public record IngestionProperties(String baseUrl, String cacheDir, List<SourceCorpus> sources,
                                  @DefaultValue("500") int tailleChunk) {
}
