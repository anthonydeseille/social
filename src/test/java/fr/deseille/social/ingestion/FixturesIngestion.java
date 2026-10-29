package fr.deseille.social.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Outils communs aux tests d'intégration de l'ingestion : les fixtures sont copiées dans un
 * répertoire temporaire et servies en {@code file:} à la place des dépôts GitHub.
 */
final class FixturesIngestion {

    private FixturesIngestion() {
    }

    /** Déclare les sources {@code code} et {@code syntec} pointant sur deux fichiers du répertoire. */
    static void declarerSources(DynamicPropertyRegistry registry, Path repertoire,
                                String fichierCode, String fichierSyntec) {
        registry.add("droits.ingestion.base-url", () -> repertoire.toUri().toString());
        registry.add("droits.ingestion.cache-dir", () -> repertoire.resolve("cache").toString());
        ajouterSource(registry, 0, "code", "CODE", fichierCode);
        ajouterSource(registry, 1, "syntec", "CONVENTION_COLLECTIVE", fichierSyntec);
    }

    private static void ajouterSource(DynamicPropertyRegistry registry, int i, String cle,
                                      String type, String fichier) {
        registry.add("droits.ingestion.sources[" + i + "].cle", () -> cle);
        registry.add("droits.ingestion.sources[" + i + "].type", () -> type);
        registry.add("droits.ingestion.sources[" + i + "].repo", () -> "");
        registry.add("droits.ingestion.sources[" + i + "].ref", () -> "");
        registry.add("droits.ingestion.sources[" + i + "].fichier", () -> fichier);
    }

    static void copier(String ressource, Path cible) throws IOException {
        try (InputStream in = FixturesIngestion.class.getResourceAsStream(ressource)) {
            assertThat(in).as("fixture %s", ressource).isNotNull();
            Files.copy(in, cible, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
