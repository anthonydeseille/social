package fr.deseille.social.questions;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Métriques du trajet des questions (Micrometer, exposées par l'Actuator et au format Prometheus).
 * Aucune donnée personnelle : ni question, ni profil, seulement des comptes, des durées et des
 * jetons, étiquetés par étape ou par issue.
 * <ul>
 *   <li>{@code droits.questions} (issue) : repondue, aucun_article, refus, tronquee, indisponible,
 *       invalide, erreur ;</li>
 *   <li>{@code droits.questions.etape} (etape) : durée de reformulation, recherche, rédaction ;</li>
 *   <li>{@code droits.assistant.appels} (fournisseur, etape, issue) : succes, refus, tronquee,
 *       illisible, erreur ;</li>
 *   <li>{@code droits.assistant.jetons} (fournisseur, etape, sens) : jetons en entrée et en sortie ;</li>
 *   <li>{@code droits.assistant.cout_usd} (fournisseur, etape) : coût des appels, en dollars
 *       ({@link AssistantClaude#PRIX}) ; {@code droits.assistant.depense_jour_usd} : dépense du jour ;
 *       {@code droits.questions.budget_atteint} : questions refusées par le budget journalier ;</li>
 *   <li>{@code droits.citations} (verification) : verifiee, non_verifiee ;</li>
 *   <li>{@code droits.chiffres_non_retrouves} : chiffres des réponses absents des sources ;</li>
 *   <li>{@code droits.garde_fous} (type) : injection, donnees_personnelles (une par donnée retirée),
 *       profil_incoherent ;</li>
 *   <li>{@code droits.questions.quota_atteint} : questions refusées par le quota journalier ;</li>
 *   <li>{@code droits.assistant.consignes} (modele, reformulation, redaction) : jauge à 1 portant les
 *       versions des consignes en service, à croiser avec les autres métriques (une version change
 *       avec l'application, donc au redémarrage).</li>
 * </ul>
 * Chaque étape est aussi une <b>observation</b> Micrometer : la même mesure donne la durée
 * ({@code droits.questions.etape}) et, si le traçage est configuré (OpenTelemetry), un span de la
 * trace de la requête, annoté par {@link #annoter} (modèle, jetons, coût, versions des consignes,
 * nombre d'articles ; jamais la question ni le profil).
 */
@Component
public class MetriquesQuestions {

    private final MeterRegistry registre;
    private final ObservationRegistry observations;

    @Autowired
    public MetriquesQuestions(MeterRegistry registre, ObservationRegistry observations) {
        this.registre = registre;
        this.observations = observations;
    }

    /** Hors Spring (tests) : observations mesurées dans {@code registre}, sans traçage. */
    public MetriquesQuestions(MeterRegistry registre) {
        this(registre, avecMesures(registre));
    }

    private static ObservationRegistry avecMesures(MeterRegistry registre) {
        ObservationRegistry r = ObservationRegistry.create();
        r.observationConfig().observationHandler(new DefaultMeterObservationHandler(registre));
        return r;
    }

    public <T> T chronometrer(String etape, Supplier<T> action) {
        return Observation.createNotStarted("droits.questions.etape", observations)
                .contextualName(etape)
                .lowCardinalityKeyValue("etape", etape)
                .observe(action);
    }

    /**
     * Attribut de l'étape en cours, pour sa trace (sans effet hors d'une étape ou sans traçage). Jamais
     * de donnée personnelle : pas de question, de profil ni de réponse.
     */
    public void annoter(String cle, Object valeur) {
        Observation courante = observations.getCurrentObservation();
        if (courante != null && valeur != null) {
            courante.highCardinalityKeyValue(cle, String.valueOf(valeur));
        }
    }

    public void question(String issue) {
        Counter.builder("droits.questions").description("Questions traitées, par issue")
                .tag("issue", issue).register(registre).increment();
    }

    /** @param fournisseur claude ou ollama */
    public void appelModele(String fournisseur, String etape, String issue) {
        Counter.builder("droits.assistant.appels").description("Appels au modèle de langage, par étape et par issue")
                .tag("fournisseur", fournisseur).tag("etape", etape).tag("issue", issue).register(registre).increment();
    }

    public void jetons(String fournisseur, String etape, long entree, long sortie) {
        Counter.builder("droits.assistant.jetons").description("Jetons consommés").tag("fournisseur", fournisseur)
                .tag("etape", etape).tag("sens", "entree").register(registre).increment(entree);
        Counter.builder("droits.assistant.jetons").description("Jetons consommés").tag("fournisseur", fournisseur)
                .tag("etape", etape).tag("sens", "sortie").register(registre).increment(sortie);
        annoter("fournisseur", fournisseur);
        annoter("jetons.entree", entree);
        annoter("jetons.sortie", sortie);
    }

    public void citations(int total, int nonVerifiees) {
        Counter.builder("droits.citations").description("Citations, selon leur vérification")
                .tag("verification", "verifiee").register(registre).increment(total - nonVerifiees);
        Counter.builder("droits.citations").description("Citations, selon leur vérification")
                .tag("verification", "non_verifiee").register(registre).increment(nonVerifiees);
    }

    public void consignes(String modele, Assistant.VersionsConsignes consignes) {
        Gauge.builder("droits.assistant.consignes", () -> 1)
                .description("Versions des consignes envoyées au modèle (empreintes des fichiers prompts/)")
                .tag("modele", modele).tag("reformulation", consignes.reformulation())
                .tag("redaction", consignes.redaction()).register(registre);
    }

    public void chiffresNonRetrouves(int nombre) {
        Counter.builder("droits.chiffres_non_retrouves").description("Chiffres des réponses absents des sources")
                .register(registre).increment(nombre);
    }

    public void gardeFou(String type, int nombre) {
        Counter.builder("droits.garde_fous").description("Interventions des garde-fous d'entrée")
                .tag("type", type).register(registre).increment(nombre);
    }

    /** @param dollars coût d'un appel ({@link AssistantClaude#PRIX}) ; ignoré s'il est inconnu */
    public void cout(String fournisseur, String etape, double dollars) {
        if (!Double.isNaN(dollars)) {
            Counter.builder("droits.assistant.cout_usd").description("Coût des appels au modèle (dollars)")
                    .tag("fournisseur", fournisseur).tag("etape", etape).register(registre).increment(dollars);
            annoter("cout_usd", String.format(java.util.Locale.ROOT, "%.5f", dollars));
        }
    }

    /** @param issue « fait » ou « impossible » (donnée du profil manquante) */
    public void calcul(String issue) {
        Counter.builder("droits.questions.calculs").description("Calculs faits par le code pour une question")
                .tag("issue", issue).register(registre).increment();
    }

    public void budgetAtteint() {
        Counter.builder("droits.questions.budget_atteint").description("Questions refusées par le budget journalier")
                .register(registre).increment();
    }

    public void jauge(String nom, String description, Supplier<Number> valeur) {
        Gauge.builder(nom, valeur).description(description).register(registre);
    }

    public void quotaAtteint() {
        Counter.builder("droits.questions.quota_atteint").description("Questions refusées par le quota journalier")
                .register(registre).increment();
    }
}
