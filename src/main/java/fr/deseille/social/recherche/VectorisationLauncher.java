package fr.deseille.social.recherche;

import fr.deseille.social.corpus.Texte;
import fr.deseille.social.corpus.TexteRepository;
import fr.deseille.social.ingestion.IngestionLauncher;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Vectorise au démarrage tous les textes en base si {@code droits.semantique.vectoriser=true}, après
 * l'ingestion quand elle est demandée dans le même lancement ({@link IngestionLauncher}). Seuls les
 * articles sans vecteur à jour sont traités, et le cache disque ({@code droits.semantique.cache-dir})
 * évite de recalculer ceux déjà vectorisés ailleurs.
 */
@Component
@Order(IngestionLauncher.ORDRE + 1)
@ConditionalOnProperty(name = "droits.semantique.vectoriser", havingValue = "true")
public class VectorisationLauncher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(VectorisationLauncher.class);

    private final RechercheSemantique semantique;
    private final TexteRepository texteRepository;

    public VectorisationLauncher(RechercheSemantique semantique, TexteRepository texteRepository) {
        this.semantique = semantique;
        this.texteRepository = texteRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!semantique.modeleDisponible()) {
            // Fait échouer le démarrage plutôt que de laisser croire que les vecteurs sont prêts
            throw new IllegalStateException(
                    "Vectorisation demandée sans modèle de vecteurs : ajouter --droits.semantique.fournisseur=ollama");
        }
        List<String> textes = texteRepository.findAll().stream().map(Texte::getSourceId).toList();
        int faits = semantique.vectoriser(textes);
        log.info("Vectorisation terminée : {} texte(s), {} article(s) mis à jour", textes.size(), faits);
    }
}
