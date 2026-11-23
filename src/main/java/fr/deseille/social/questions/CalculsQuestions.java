package fr.deseille.social.questions;

import fr.deseille.social.calcul.CalculIndemniteService;
import fr.deseille.social.calcul.Indemnite.Demande;
import fr.deseille.social.calcul.Indemnite.Motif;
import fr.deseille.social.calcul.Indemnite.Resultat;
import fr.deseille.social.profil.ProfilSalarie;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Calculs faits par le code pour une question, avant la rédaction. Pour l'instant, l'indemnité de
 * licenciement : une question qui la nomme (« indemnité de licenciement »), ou qui parle d'indemnité et
 * de licenciement sans nommer une autre indemnité (journalières, de préavis, de congés payés…),
 * déclenche le calculateur ({@link CalculIndemniteService}), gratuit, sans appel au modèle.
 * <p>
 * La question ne donne ni la date de notification, ni la fin du contrat : le calcul se fait à la
 * date de référence du profil, et ce choix est ajouté aux hypothèses du résultat. Le motif est lu dans
 * la question (faute grave ou lourde, économique), personnel sinon. Un salaire mensuel brut cité dans
 * la question (« je gagne 3 000 € brut ») donne un montant en euros ({@link #salaire}) ; sinon le
 * résultat est en mois de salaire.
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
    /** Montant en euros : « 3 000 € », « 3000 euros », « 2 850,50 € », « 2.850 € ». */
    private static final Pattern MONTANT = Pattern.compile(
            "(?<!\\d)(\\d{1,3}(?:[ .\u00a0\u202f]\\d{3})+|\\d+)(?:,(\\d{1,2}))?\\s*(?:€|euros?\\b|eur\\b)");
    /** Mots qui, juste avant le montant, en font un salaire. */
    private static final Pattern SALAIRE_AVANT = Pattern.compile(
            "\\b(?:salaire|remuneration|gagne|touche|percois|paye|payee)\\b[^.?!€\\d]{0,30}$");
    /** Mots qui, juste après le montant, en font un salaire mensuel brut. */
    private static final Pattern SALAIRE_APRES = Pattern.compile("^[^.?!€\\d]{0,12}\\b(?:brut|par mois|mensuel)");
    /** Somme qui n'est pas un salaire, nommée juste avant ou après le montant (« une prime de », « d'indemnité »). */
    private static final Pattern AUTRE_SOMME_AVANT = Pattern.compile(
            "\\b(?:indemnit|prime|rappel|cheque|dommages|solde|chomage|allocation)[^.?!€\\d]{0,20}$");
    private static final Pattern AUTRE_SOMME_APRES = Pattern.compile(
            "^\\s*(?:d'|de\\s+)?(?:indemnit|prime|rappel|dommages|solde|chomage|allocation)");
    /** « net » ou « annuel » juste après le montant, ou entre le mot « salaire » et le montant. */
    private static final Pattern NET = Pattern.compile("^[^.?!€\\d]{0,12}\\bnet\\b");
    private static final Pattern NET_AVANT = Pattern.compile("\\bnet\\b[^.?!€\\d]{0,25}$");
    private static final Pattern ANNUEL = Pattern.compile("^[^.?!€\\d]{0,20}\\b(?:par an|annuel|l'an\\b|/ ?an\\b)");
    private static final Pattern ANNUEL_AVANT = Pattern.compile("\\b(?:annuel|par an|l'annee)\\b[^.?!€\\d]{0,25}$");
    /** Bornes d'un salaire mensuel plausible : en dehors, le montant est sans doute autre chose. */
    private static final BigDecimal SALAIRE_MIN = new BigDecimal("300");
    private static final BigDecimal SALAIRE_MAX = new BigDecimal("50000");

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
        LectureSalaire salaire = salaire(texte);
        Demande demande = new Demande(profil, profil.dateReference(), profil.dateReference(), motif, salaire.mensuel());
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
        if (resultat.droit() && salaire.hypothese() != null) {
            hypotheses.add(salaire.hypothese());
        }
        List<String> avertissements = new ArrayList<>(resultat.avertissements());
        avertissements.add("Règles de calcul en cours de validation juridique.");
        return Optional.of(new CalculQuestion(INDEMNITE_LICENCIEMENT, new Resultat(resultat.droit(),
                resultat.absenceDroit(), resultat.anneesAnciennete(), resultat.moisAnciennete(), resultat.legale(),
                resultat.conventionnelle(), resultat.retenue(), resultat.moisRetenus(), resultat.montantBrut(),
                List.copyOf(hypotheses), List.copyOf(avertissements)), null));
    }

    /**
     * Salaire lu dans la question.
     *
     * @param mensuel   salaire mensuel brut retenu pour le calcul, {@code null} si aucun
     * @param hypothese ce qui a été lu et comment le résultat l'utilise, pour les hypothèses
     */
    record LectureSalaire(BigDecimal mensuel, String hypothese) {
    }

    /**
     * Salaire mensuel brut cité dans la question. Seul un montant en euros présenté comme un salaire
     * (« je gagne », « mon salaire est de », ou suivi de « brut », « par mois ») compte : une indemnité
     * proposée ou une prime ne sont pas des salaires. Un salaire annuel est divisé par 12 ; un salaire
     * net n'est pas utilisé (le calcul part du brut) ; plusieurs salaires différents, ou un montant hors
     * de 300 à 50 000 € par mois, non plus. Le salaire n'est ni stocké ni journalisé.
     *
     * @param texteNormalise question en minuscules sans accents ({@link #normaliser})
     */
    static LectureSalaire salaire(String texteNormalise) {
        java.util.Set<BigDecimal> mensuels = new java.util.LinkedHashSet<>();
        boolean net = false;
        boolean annuel = false;
        Matcher m = MONTANT.matcher(texteNormalise);
        while (m.find()) {
            String avant = texteNormalise.substring(Math.max(0, m.start() - 40), m.start());
            String apres = texteNormalise.substring(m.end(), Math.min(texteNormalise.length(), m.end() + 30));
            if (!SALAIRE_AVANT.matcher(avant).find() && !SALAIRE_APRES.matcher(apres).find()
                    || AUTRE_SOMME_AVANT.matcher(avant).find() || AUTRE_SOMME_APRES.matcher(apres).find()) {
                continue;
            }
            if (NET.matcher(apres).find() || NET_AVANT.matcher(avant).find()) {
                net = true;
                continue;
            }
            BigDecimal montant = new BigDecimal(m.group(1).replaceAll("[ .\u00a0\u202f]", "")
                    + (m.group(2) != null ? "." + m.group(2) : ""));
            if (ANNUEL.matcher(apres).find() || ANNUEL_AVANT.matcher(avant).find()) {
                annuel = true;
                montant = montant.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
            }
            mensuels.add(montant.stripTrailingZeros());
        }
        String enMois = "Montant exprimé en mois de salaire brut de référence";
        if (mensuels.size() > 1) {
            return new LectureSalaire(null, enMois + " : la question cite plusieurs salaires différents.");
        }
        if (mensuels.isEmpty()) {
            return new LectureSalaire(null, net
                    ? enMois + " : la question donne un salaire net, le calcul part du brut."
                    : enMois + " : le salaire n'est pas donné dans la question.");
        }
        BigDecimal mensuel = mensuels.iterator().next();
        if (mensuel.compareTo(SALAIRE_MIN) < 0 || mensuel.compareTo(SALAIRE_MAX) > 0) {
            return new LectureSalaire(null, enMois + " : le montant cité ne ressemble pas à un salaire mensuel.");
        }
        return new LectureSalaire(mensuel, "Salaire mensuel brut de " + euros(mensuel) + " € lu dans la question"
                + (annuel ? " (salaire annuel divisé par 12)" : "") + ", pris comme salaire de référence : la loi "
                + "retient la moyenne la plus favorable des 12 ou des 3 derniers mois, primes comprises.");
    }

    /** Écriture française : « 3 000 », « 2 850,5 ». */
    private static String euros(BigDecimal montant) {
        java.text.DecimalFormat format = new java.text.DecimalFormat("#,##0.##",
                java.text.DecimalFormatSymbols.getInstance(Locale.FRANCE));
        return format.format(montant).replace('\u202f', ' ').replace('\u00a0', ' ');
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
