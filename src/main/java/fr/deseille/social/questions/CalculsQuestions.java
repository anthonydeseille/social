package fr.deseille.social.questions;

import fr.deseille.social.calcul.CalculIndemniteService;
import fr.deseille.social.calcul.Indemnite.Demande;
import fr.deseille.social.calcul.Indemnite.Motif;
import fr.deseille.social.calcul.Indemnite.Resultat;
import fr.deseille.social.profil.ProfilSalarie;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Calculs faits par le code pour une question, avant la rédaction. Pour l'instant, l'indemnité de
 * licenciement : une question qui la nomme (« indemnité de licenciement »), ou qui parle d'indemnité et
 * de licenciement sans nommer une autre indemnité (journalières, de préavis, de congés payés…),
 * déclenche le calculateur ({@link CalculIndemniteService}), gratuit, sans appel au modèle.
 * <p>
 * La question ne donne ni la date de notification, ni la fin du contrat, ni le salaire : le calcul se
 * fait à la date de référence du profil, en mois de salaire, et ces choix sont ajoutés aux hypothèses
 * du résultat. Le motif est lu dans la question (faute grave ou lourde, économique), personnel sinon.
 */
@Component
public class CalculsQuestions {

    static final String INDEMNITE_LICENCIEMENT = "indemnité de licenciement";

    private static final Pattern INDEMNITE = Pattern.compile("\\bindemnit");
    private static final Pattern LICENCIEMENT = Pattern.compile("\\blicenci");
    private static final Pattern INDEMNITE_DE_LICENCIEMENT =
            Pattern.compile("\\bindemnit\\w*\\s+(?:legale\\s+|conventionnelle\\s+)?de\\s+licenci");
    /**
     * Une autre indemnité nommée : la question ne porte pas sur l'indemnité de licenciement. Le préavis
     * et les congés n'excluent que nommés comme indemnité (« indemnité de préavis », « indemnité pour
     * mes congés », « au titre du préavis ») : « ai-je droit à une indemnité et à un préavis ? » porte
     * bien sur l'indemnité de licenciement.
     */
    private static final Pattern AUTRE_INDEMNITE = Pattern.compile("\\b(?:journalier|chomage|compensatrice|"
            + "indemnit\\w*\\s+(?:d'\\s*|(?:de|du|pour|sur|au titre d\\w*)\\s+)(?:(?:le|la|les|mes|mon|ma|un|une)\\s+)?"
            + "(?:preavis|conges)|rupture conventionnelle|fin de contrat|precarite|"
            + "transactionnel|prud)");
    /**
     * « faute grave » niée : « sans faute grave », « pas pour une faute grave », « pas commis de faute
     * lourde ». Seuls quelques petits mots peuvent s'intercaler, pour ne pas lire « sans préavis pour
     * faute grave » comme une négation.
     */
    private static final Pattern FAUTE_NIEE = Pattern.compile("\\b(?:sans|pas|hors|non|aucune)\\s+"
            + "(?:(?:pour|de|d'|une|un|la|commis|eu)\\s*){0,3}faute\\s+(?:grave|lourde)");
    private static final Pattern FAUTE_GRAVE = Pattern.compile("\\bfaute\\s+grave");
    private static final Pattern FAUTE_LOURDE = Pattern.compile("\\bfaute\\s+lourde");
    private static final Pattern ECONOMIQUE = Pattern.compile("\\beconomique");

    private final CalculIndemniteService indemnite;

    public CalculsQuestions(CalculIndemniteService indemnite) {
        this.indemnite = indemnite;
    }

    /** Calcul utile à la question, s'il y en a un ; vide pour une question sans calcul. */
    public Optional<CalculQuestion> pour(String question, ContexteProfil contexte) {
        String texte = normaliser(question);
        if (!concerneIndemniteLicenciement(texte)) {
            return Optional.empty();
        }
        ProfilSalarie profil = contexte.profil();
        if (profil.dateEntree() == null) {
            return Optional.of(new CalculQuestion(INDEMNITE_LICENCIEMENT, null,
                    "la date d'entrée dans l'entreprise n'est pas renseignée dans le profil"));
        }
        Motif motif = motif(texte);
        Demande demande = new Demande(profil, profil.dateReference(), profil.dateReference(), motif, null);
        Resultat resultat;
        try {
            resultat = indemnite.calculer(demande);
        } catch (ResponseStatusException e) {
            return Optional.of(new CalculQuestion(INDEMNITE_LICENCIEMENT, null,
                    e.getReason() != null ? e.getReason() : "données du profil incohérentes pour le calcul"));
        }
        List<String> hypotheses = new ArrayList<>(resultat.hypotheses());
        hypotheses.add(0, "Calcul à la date de référence du profil (" + profil.dateReference() + "), prise comme date de "
                + "notification et de fin du contrat : si le préavis se termine plus tard, l'ancienneté et l'indemnité "
                + "peuvent être un peu plus élevées.");
        hypotheses.add(1, switch (motif) {
            case LICENCIEMENT_PERSONNEL -> "Licenciement supposé pour motif personnel, hors faute grave ou lourde (motif "
                    + "non précisé dans la question).";
            case LICENCIEMENT_ECONOMIQUE -> "Motif lu dans la question : licenciement économique (même indemnité qu'un "
                    + "licenciement pour motif personnel).";
            case FAUTE_GRAVE -> "Motif lu dans la question : faute grave.";
            case FAUTE_LOURDE -> "Motif lu dans la question : faute lourde.";
        });
        if (resultat.droit() && resultat.montantBrut() == null) {
            hypotheses.add("Montant exprimé en mois de salaire brut de référence : le salaire n'est pas demandé.");
        }
        List<String> avertissements = new ArrayList<>(resultat.avertissements());
        avertissements.add("Règles de calcul en cours de validation juridique.");
        return Optional.of(new CalculQuestion(INDEMNITE_LICENCIEMENT, new Resultat(resultat.droit(),
                resultat.absenceDroit(), resultat.anneesAnciennete(), resultat.moisAnciennete(), resultat.legale(),
                resultat.conventionnelle(), resultat.retenue(), resultat.moisRetenus(), resultat.montantBrut(),
                List.copyOf(hypotheses), List.copyOf(avertissements)), null));
    }

    static boolean concerneIndemniteLicenciement(String texteNormalise) {
        if (INDEMNITE_DE_LICENCIEMENT.matcher(texteNormalise).find()) {
            return true;
        }
        return INDEMNITE.matcher(texteNormalise).find() && LICENCIEMENT.matcher(texteNormalise).find()
                && !AUTRE_INDEMNITE.matcher(texteNormalise).find();
    }

    static Motif motif(String texteNormalise) {
        String texte = FAUTE_NIEE.matcher(texteNormalise).replaceAll(" ");
        if (FAUTE_LOURDE.matcher(texte).find()) {
            return Motif.FAUTE_LOURDE;
        }
        if (FAUTE_GRAVE.matcher(texte).find()) {
            return Motif.FAUTE_GRAVE;
        }
        return ECONOMIQUE.matcher(texte).find() ? Motif.LICENCIEMENT_ECONOMIQUE : Motif.LICENCIEMENT_PERSONNEL;
    }

    /** Minuscules sans accents : « Indemnité » et « indemnite » se valent. */
    static String normaliser(String texte) {
        return Normalizer.normalize(texte, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
