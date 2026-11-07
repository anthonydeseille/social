package fr.deseille.social.profil;

import fr.deseille.social.consultation.VuesConsultation.TexteResume;
import fr.deseille.social.corpus.Texte;
import fr.deseille.social.corpus.TexteRepository;
import fr.deseille.social.corpus.TypeTexte;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Établit le contexte juridique d'un profil : ancienneté à la date de référence, textes qui
 * s'appliquent (Code du travail et convention collective) et avertissements sur ce qui manque
 * pour une réponse complète.
 */
@Service
@Transactional(readOnly = true)
public class ProfilService {

    private final TexteRepository texteRepository;
    private final Clock clock;

    public ProfilService(TexteRepository texteRepository, Clock clock) {
        this.texteRepository = texteRepository;
        this.clock = clock;
    }

    public ContexteProfil contexte(ProfilSalarie profil) {
        ProfilSalarie normalise = profil.dateReference() == null
                ? profil.avecDateReference(LocalDate.now(clock))
                : profil;
        List<String> avertissements = new ArrayList<>();
        List<TexteResume> textes = new ArrayList<>();

        LocalDate date = normalise.dateReference();
        List<Texte> codes = applicables(texteRepository.findByType(TypeTexte.CODE), date);
        if (codes.isEmpty()) {
            avertissements.add("Le Code du travail n'est pas encore intégré : aucun droit ne peut être évalué.");
        }
        codes.stream().map(TexteResume::de).forEach(textes::add);

        boolean conventionDisponible = false;
        if (normalise.idcc() == null) {
            avertissements.add("Convention collective non renseignée : seules les dispositions du Code du travail "
                    + "sont prises en compte. Son numéro (IDCC) figure sur la fiche de paie.");
        } else {
            List<Texte> integrees = texteRepository.findByIdcc(normalise.idcc());
            List<Texte> conventions = applicables(integrees, date);
            conventionDisponible = !conventions.isEmpty();
            if (integrees.isEmpty()) {
                avertissements.add("La convention collective IDCC " + normalise.idcc() + " n'est pas encore intégrée : "
                        + "seules les dispositions du Code du travail sont prises en compte, alors que la convention "
                        + "peut prévoir des droits plus favorables.");
            } else if (conventions.isEmpty()) {
                avertissements.add("La convention collective IDCC " + normalise.idcc() + " n'est pas applicable au "
                        + date + " (état : " + integrees.getFirst().getEtat() + ") : seules les dispositions du Code "
                        + "du travail sont prises en compte.");
            }
            conventions.stream().map(TexteResume::de).forEach(textes::add);
        }

        return new ContexteProfil(normalise, anciennete(normalise), textes, conventionDisponible, avertissements);
    }

    /** Textes applicables à la date, dans un ordre stable. */
    private static List<Texte> applicables(List<Texte> textes, LocalDate date) {
        return textes.stream()
                .filter(t -> t.estApplicableLe(date))
                .sorted(Comparator.comparing(Texte::getSourceId))
                .toList();
    }

    /** Ancienneté à la date de référence, ou null si la date d'entrée n'est pas connue. */
    private static Anciennete anciennete(ProfilSalarie profil) {
        if (profil.dateEntree() == null) {
            return null;
        }
        if (profil.dateEntree().isAfter(profil.dateReference())) {
            throw new ProfilIncoherentException("La date d'entrée (" + profil.dateEntree()
                    + ") est postérieure à la date de référence (" + profil.dateReference() + ")");
        }
        Period periode = Period.between(profil.dateEntree(), profil.dateReference());
        // On ne travaille pas avant 14 ans (âge minimal des jeunes travailleurs, hors spectacle)
        if (profil.age() != null && periode.getYears() > profil.age() - 14) {
            throw new ProfilIncoherentException("Une ancienneté de " + periode.getYears() + " ans est incompatible avec un "
                    + "âge de " + profil.age() + " ans : vérifiez la date d'entrée et l'âge.");
        }
        return new Anciennete(periode.getYears(), periode.getMonths(), periode.toTotalMonths());
    }

    /** Ancienneté en années et mois révolus. */
    public record Anciennete(int annees, int mois, long totalMois) {
    }

    /**
     * @param profil               profil normalisé (date de référence renseignée)
     * @param anciennete           null si la date d'entrée n'est pas connue
     * @param textesApplicables    Code du travail, puis convention collective si intégrée
     * @param conventionDisponible la convention du profil est intégrée au corpus
     */
    public record ContexteProfil(ProfilSalarie profil, Anciennete anciennete, List<TexteResume> textesApplicables,
                                 boolean conventionDisponible, List<String> avertissements) {
    }
}
