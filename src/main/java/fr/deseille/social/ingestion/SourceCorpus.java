package fr.deseille.social.ingestion;

import fr.deseille.social.corpus.TypeTexte;

/**
 * Description d'une source de corpus à ingérer, épinglée à une révision git précise
 * pour garantir une ingestion reproductible et traçable.
 *
 * @param cle     identifiant logique de la source (sert de nom de step), ex. {@code code} ou {@code syntec}
 * @param type    nature du texte ({@link TypeTexte})
 * @param repo    dépôt GitHub, ex. {@code SocialGouv/legi-data} (vide en test fichier local)
 * @param ref     révision git épinglée (SHA de commit) ou branche
 * @param fichier chemin du fichier JSON dans le dépôt, ex. {@code data/LEGITEXT000006072050.json}
 */
public record SourceCorpus(String cle, TypeTexte type, String repo, String ref, String fichier) {

    /**
     * Construit l'URL complète en concaténant base / repo / ref / fichier, en ignorant
     * les segments vides (permet un {@code baseUrl} de type {@code file:/…} en test).
     */
    public String urlComplete(String baseUrl) {
        StringBuilder sb = new StringBuilder(trimTrailingSlash(baseUrl));
        for (String segment : new String[] {repo, ref, fichier}) {
            if (segment != null && !segment.isBlank()) {
                sb.append('/').append(segment);
            }
        }
        return sb.toString();
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
