package fr.deseille.social.questions;

import fr.deseille.social.consultation.VuesConsultation.ArticleDetail;
import fr.deseille.social.profil.ProfilService.ContexteProfil;
import fr.deseille.social.profil.ProfilSalarie;
import fr.deseille.social.questions.Assistant.Passage;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Garde-fou de sortie : chaque chiffre de la réponse (durée, montant, pourcentage, seuil) doit se
 * retrouver dans les articles transmis, le profil ou la question. Un chiffre absent n'est pas
 * forcément faux (calcul fait à partir du profil, comme le demandent les consignes), mais il ne
 * vient pas d'une source : il est signalé « à vérifier », sans être retiré, comme une citation non
 * vérifiée. Les chiffres du calculateur ({@link CalculQuestion}) comptent comme connus : ils viennent
 * du code, pas du modèle.
 * <p>
 * Les textes juridiques écrivent souvent les nombres en lettres (« deux mois », « un quart de
 * mois ») : les nombres en lettres des articles, jusqu'à 999, et les fractions usuelles (quart → 4,
 * tiers → 3…) sont ramenés en chiffres. Les numéros d'articles (« L1234-9 ») et 0 et 1 ne sont pas
 * contrôlés.
 */
public final class VerificationChiffres {

    /** Nombre isolé : ni collé à une lettre (« L1234 »), ni partie d'un numéro « 1234-9 ». */
    private static final Pattern NOMBRE = Pattern.compile(
            "(?<![\\p{L}\\d.,-])\\d{1,3}(?:[   ]\\d{3})+(?:,\\d+)?(?![\\p{L}\\d-])"
                    + "|(?<![\\p{L}\\d.,-])\\d+(?:[.,]\\d+)?(?![\\p{L}\\d-])");

    private static final Map<String, Integer> UNITES = Map.ofEntries(
            Map.entry("zero", 0), Map.entry("un", 1), Map.entry("une", 1), Map.entry("deux", 2), Map.entry("trois", 3),
            Map.entry("quatre", 4), Map.entry("cinq", 5), Map.entry("six", 6), Map.entry("sept", 7), Map.entry("huit", 8),
            Map.entry("neuf", 9), Map.entry("dix", 10), Map.entry("onze", 11), Map.entry("douze", 12),
            Map.entry("treize", 13), Map.entry("quatorze", 14), Map.entry("quinze", 15), Map.entry("seize", 16),
            Map.entry("vingt", 20), Map.entry("vingts", 20), Map.entry("trente", 30), Map.entry("quarante", 40),
            Map.entry("cinquante", 50), Map.entry("soixante", 60), Map.entry("cent", 100), Map.entry("cents", 100),
            Map.entry("mille", 1000));

    /** Fractions et ordinaux : « un quart de mois » se lit 1/4 dans une réponse. */
    private static final Map<String, Integer> FRACTIONS = Map.ofEntries(
            Map.entry("demi", 2), Map.entry("moitie", 2), Map.entry("tiers", 3), Map.entry("quart", 4),
            Map.entry("quarts", 4), Map.entry("cinquieme", 5), Map.entry("sixieme", 6), Map.entry("dixieme", 10),
            Map.entry("douzieme", 12), Map.entry("premier", 1), Map.entry("premiere", 1), Map.entry("second", 2),
            Map.entry("seconde", 2), Map.entry("deuxieme", 2), Map.entry("troisieme", 3), Map.entry("quatrieme", 4));

    private static final Pattern MOT = Pattern.compile("[a-z]+(?:-[a-z]+)*");

    private VerificationChiffres() {
    }

    /** Chiffres de la réponse absents des sources, dans l'ordre d'apparition, sans doublon. */
    public static List<String> nonRetrouves(List<Passage> passages, List<ArticleDetail> articles,
                                            ContexteProfil contexte, String question, CalculQuestion calcul) {
        Set<String> connus = new HashSet<>();
        for (ArticleDetail a : articles) {
            String texte = AssistantClaude.contenu(a);
            connus.addAll(nombres(texte));
            connus.addAll(nombresEnLettres(texte));
            if (a.num() != null) {
                connus.addAll(nombres(a.num()));
            }
        }
        connus.addAll(nombres(question));
        connus.addAll(profil(contexte));
        if (calcul != null) {
            connus.addAll(nombres(calcul.chiffres()));
        }
        Set<String> absents = new LinkedHashSet<>();
        for (Passage p : passages) {
            Matcher m = NOMBRE.matcher(p.texte());
            while (m.find()) {
                BigDecimal n = valeur(m.group());
                if (n != null && n.compareTo(BigDecimal.ONE) > 0 && !connus.contains(cle(n))) {
                    absents.add(m.group().strip());
                }
            }
        }
        return List.copyOf(absents);
    }

    static Set<String> nombres(String texte) {
        Set<String> resultat = new HashSet<>();
        if (texte == null) {
            return resultat;
        }
        Matcher m = NOMBRE.matcher(texte);
        while (m.find()) {
            BigDecimal n = valeur(m.group());
            if (n != null) {
                resultat.add(cle(n));
            }
        }
        return resultat;
    }

    /**
     * Nombres écrits en lettres (« vingt-cinq », « trois cents », « quatre-vingt-dix »), jusqu'à 999,
     * et dénominateurs des fractions usuelles.
     */
    static Set<String> nombresEnLettres(String texte) {
        Set<String> resultat = new HashSet<>();
        if (texte == null) {
            return resultat;
        }
        String normalise = Normalizer.normalize(texte, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        Matcher m = MOT.matcher(normalise);
        Integer total = null;
        Integer courant = null;
        int fin = 0;
        while (m.find()) {
            // Une ponctuation entre deux mots clôt le nombre en cours : « soixante et onze, deux mille »
            if (courant != null && !normalise.substring(fin, m.start()).isBlank()) {
                resultat.add(cle((total == null ? 0 : total) + courant));
                total = null;
                courant = null;
            }
            fin = m.end();
            // « et » ne lie qu'une dizaine à son unité (« vingt et un », « soixante et onze ») :
            // « trois et quatre » reste deux nombres, et non 7
            if (m.group().equals("et")) {
                if (courant != null && courant >= 20 && courant % 10 == 0 && courant < 100) {
                    continue;
                }
                if (courant != null) {
                    resultat.add(cle((total == null ? 0 : total) + courant));
                }
                total = null;
                courant = null;
                continue;
            }
            String[] parties = m.group().split("-");
            boolean nombre = true;
            for (String partie : parties) {
                if (FRACTIONS.containsKey(partie)) {
                    resultat.add(cle(FRACTIONS.get(partie)));
                }
                if (!UNITES.containsKey(partie) && !partie.equals("et")) {
                    nombre = false;
                }
            }
            if (!nombre) {
                if (courant != null) {
                    resultat.add(cle((total == null ? 0 : total) + courant));
                }
                total = null;
                courant = null;
                continue;
            }
            for (String partie : parties) {
                if (partie.equals("et")) {
                    continue;
                }
                int v = UNITES.get(partie);
                if (v == 100) {
                    courant = (courant == null || courant == 0 ? 1 : courant) * 100;
                } else if (v == 1000) {
                    total = ((total == null ? 0 : total) + (courant == null || courant == 0 ? 1 : courant)) * 1000;
                    courant = 0;
                } else if (v == 20 && courant != null && courant == 4) {
                    courant = 80;
                } else {
                    courant = (courant == null ? 0 : courant) + v;
                }
                resultat.add(cle(v));
            }
        }
        if (courant != null) {
            resultat.add(cle((total == null ? 0 : total) + courant));
        }
        return resultat;
    }

    /** Ancienneté, âge, convention, dates et classification : ce que la réponse peut reprendre du profil. */
    private static Set<String> profil(ContexteProfil contexte) {
        Set<String> resultat = new HashSet<>();
        ProfilSalarie p = contexte.profil();
        if (contexte.anciennete() != null) {
            resultat.add(cle(contexte.anciennete().annees()));
            resultat.add(cle(contexte.anciennete().mois()));
            resultat.add(cle(contexte.anciennete().totalMois()));
        }
        if (p.age() != null) {
            resultat.add(cle(p.age()));
        }
        if (p.idcc() != null) {
            resultat.add(cle(p.idcc()));
        }
        for (LocalDate date : new LocalDate[] {p.dateEntree(), p.dateReference()}) {
            if (date != null) {
                resultat.add(cle(date.getYear()));
                resultat.add(cle(date.getDayOfMonth()));
            }
        }
        resultat.addAll(nombres(p.classification()));
        return resultat;
    }

    /** Forme canonique d'un nombre : « 90 », « 1500.5 » (une même valeur, une même clé, quelle que soit l'échelle). */
    static String cle(BigDecimal n) {
        return n.stripTrailingZeros().toPlainString();
    }

    static String cle(long n) {
        return String.valueOf(n);
    }

    /** « 1 500,50 » → 1500.5 ; « 4,0 » → 4 ; null si illisible. */
    static BigDecimal valeur(String nombre) {
        try {
            return new BigDecimal(nombre.replaceAll("[   ]", "").replace(',', '.')).stripTrailingZeros();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
