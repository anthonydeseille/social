package fr.deseille.social.ingestion;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Récupère le fichier JSON d'une source vers un cache local.
 * <p>
 * Supporte les URL {@code http(s)} (téléchargement) et {@code file:} (lecture en place,
 * utilisée par les tests). Le cache est indexé par la révision git épinglée : si le
 * fichier existe déjà pour ce {@code ref}, il n'est pas re-téléchargé — l'ingestion
 * reste donc relançable sans trafic réseau inutile.
 */
@Component
public class CorpusDownloader {

    private static final Logger log = LoggerFactory.getLogger(CorpusDownloader.class);

    private final IngestionProperties properties;
    private final HttpClient httpClient;

    public CorpusDownloader(IngestionProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Rend disponible localement le fichier de la source et renvoie son chemin.
     */
    public Path recuperer(SourceCorpus source) {
        URI uri = URI.create(source.urlComplete(properties.baseUrl()));
        String scheme = uri.getScheme();
        if (scheme != null && scheme.startsWith("file")) {
            return lireFichierLocal(uri);
        }
        return telecharger(source, uri);
    }

    private Path lireFichierLocal(URI uri) {
        Path chemin = Paths.get(uri);
        if (!Files.isReadable(chemin)) {
            throw new IllegalStateException("Fichier source introuvable : " + chemin);
        }
        return chemin;
    }

    private Path telecharger(SourceCorpus source, URI uri) {
        Path cible = Paths.get(properties.cacheDir(), source.ref(), nomFichier(source));
        if (Files.exists(cible)) {
            log.info("Source {} déjà en cache : {}", source.cle(), cible);
            return cible;
        }
        try {
            Files.createDirectories(cible.getParent());
            log.info("Téléchargement source {} depuis {}", source.cle(), uri);
            HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Téléchargement de " + uri + " en échec : HTTP " + response.statusCode());
            }
            Path temp = Files.createTempFile(cible.getParent(), "dl-", ".tmp");
            try (InputStream body = response.body()) {
                Files.copy(body, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temp, cible, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            log.info("Source {} téléchargée ({} octets) : {}", source.cle(), Files.size(cible), cible);
            return cible;
        } catch (IOException e) {
            throw new IllegalStateException("Échec de récupération de " + uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Téléchargement interrompu : " + uri, e);
        }
    }

    private static String nomFichier(SourceCorpus source) {
        String fichier = source.fichier();
        int slash = fichier.lastIndexOf('/');
        return slash >= 0 ? fichier.substring(slash + 1) : fichier;
    }
}
