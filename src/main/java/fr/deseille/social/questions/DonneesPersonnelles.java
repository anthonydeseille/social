package fr.deseille.social.questions;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Garde-fou d'entrée : retire du texte libre (question, classification) les données personnelles
 * qu'un salarié y glisse parfois et dont la réponse n'a pas besoin, avant tout envoi au modèle.
 * Chacune est remplacée par sa nature entre crochets, pour que la phrase reste lisible. Les
 * données du profil structuré (dates, âge, situations) ne sont pas concernées : elles servent la
 * réponse.
 * <p>
 * Ordre des motifs : l'IBAN et le numéro de sécurité sociale avant le téléphone, dont le motif
 * reconnaîtrait sinon une partie de leurs chiffres.
 */
public final class DonneesPersonnelles {

    private static final Map<String, Pattern> MOTIFS = new LinkedHashMap<>();

    static {
        MOTIFS.put("IBAN", Pattern.compile("\\b[A-Z]{2}\\d{2}(?:[ ]?[A-Z0-9]{4}){3,7}(?:[ ]?[A-Z0-9]{1,3})?\\b"));
        // 13 chiffres (sexe, année, mois, département dont 2A/2B, commune, ordre) et clé facultative
        MOTIFS.put("numéro de sécurité sociale", Pattern.compile(
                "\\b[12][ .]?\\d{2}[ .]?(?:0[1-9]|1[0-2]|[2-9]\\d)[ .]?(?:\\d{2}|2[AaBb])[ .]?\\d{3}[ .]?\\d{3}(?:[ .]?\\d{2})?\\b"));
        MOTIFS.put("e-mail", Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+"));
        MOTIFS.put("téléphone", Pattern.compile("(?:(?:\\+|00)33[ .-]?|\\b0)[1-9](?:[ .-]?\\d{2}){4}\\b"));
    }

    /**
     * @param texte    texte à envoyer au modèle
     * @param retraits nombre de données retirées, par nature (vide si aucune)
     */
    public record Masquage(String texte, Map<String, Integer> retraits) {

        public boolean aucun() {
            return retraits.isEmpty();
        }
    }

    private DonneesPersonnelles() {
    }

    public static Masquage masquer(String texte) {
        if (texte == null) {
            return new Masquage(null, Map.of());
        }
        String resultat = texte;
        Map<String, Integer> retraits = new LinkedHashMap<>();
        for (Map.Entry<String, Pattern> motif : MOTIFS.entrySet()) {
            Matcher m = motif.getValue().matcher(resultat);
            int nombre = 0;
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                nombre++;
                m.appendReplacement(sb, Matcher.quoteReplacement("[" + motif.getKey() + "]"));
            }
            m.appendTail(sb);
            if (nombre > 0) {
                retraits.put(motif.getKey(), nombre);
                resultat = sb.toString();
            }
        }
        return new Masquage(resultat, java.util.Collections.unmodifiableMap(retraits));
    }
}
