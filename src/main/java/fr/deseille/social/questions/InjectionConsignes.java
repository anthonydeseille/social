package fr.deseille.social.questions;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Garde-fou d'entrée : reconnaît les tentatives de détourner le modèle (« ignore tes consignes »,
 * « révèle ton prompt système », balises de rôle…) dans le texte libre, avant tout appel payant.
 * Une question reconnue est refusée.
 * <p>
 * Les motifs visent des formules adressées au modèle (deuxième personne, « consignes précédentes »,
 * vocabulaire des modèles de langage), pour ne pas refuser une vraie question : « mon employeur
 * ignore les consignes de sécurité » passe. Ce filtre ne remplace pas les consignes de rédaction
 * (s'en tenir aux articles fournis) : il évite seulement de payer pour une requête hors de propos.
 */
public final class InjectionConsignes {

    private static final String CONSIGNES = "(?:instructions?|consignes?|prompt)";

    private static final List<Pattern> MOTIFS = List.of(
            // « ignore / oublie tes consignes », « ne tiens plus compte de vos instructions »
            Pattern.compile("\\b(?:ignore|oublie|ne tiens? (?:plus |pas )?compte d)\\w*\\b.{0,40}\\b(?:tes|vos|ton|votre)\\s+"
                    + CONSIGNES),
            // « ignore les consignes précédentes / ci-dessus / du système »
            Pattern.compile("\\b(?:ignore|oublie|ne tiens? (?:plus |pas )?compte d)\\w*\\b.{0,40}\\b" + CONSIGNES
                    + "\\s+(?:precedentes?|initiales?|ci-dessus|du systeme|systeme)"),
            // « révèle / affiche / répète tes consignes », « montre ton prompt »
            Pattern.compile("\\b(?:revele|affiche|repete|montre|recopie)\\w*\\b.{0,30}\\b(?:tes|vos|ton|votre)\\s+"
                    + CONSIGNES),
            Pattern.compile("\\bprompt (?:systeme|system)\\b|\\bsystem prompt\\b"),
            // Anglais
            Pattern.compile("\\b(?:ignore|disregard|forget)\\b.{0,30}\\b(?:previous|prior|above|all|your)\\b.{0,20}\\b"
                    + "(?:instructions|rules|prompt)\\b"),
            // Changement de rôle adressé au modèle ; « on m'a dit : tu es maintenant cadre » passe
            Pattern.compile("\\b(?:tu es|vous etes) (?:maintenant|desormais|a present) (?:un|une) "
                    + "(?:assistant|ia|intelligence artificielle|modele|chatbot|robot|agent)\\b"
                    + "|\\b(?:joue|jouez) le role d|\\bfais semblant d'etre\\b"
                    + "|\\bmode (?:developpeur|developer|dan)\\b|\\bjailbreak"),
            // Balises de rôle ou de format de conversation
            Pattern.compile("</?\\s*(?:system|systeme|assistant|instructions?)\\s*>|\\[/?inst\\]|<\\|im_(?:start|end)\\|>"));

    private InjectionConsignes() {
    }

    /** Extrait reconnu, s'il y en a un. */
    public static Optional<String> detecter(String texte) {
        if (texte == null || texte.isBlank()) {
            return Optional.empty();
        }
        String normalise = normaliser(texte);
        return MOTIFS.stream().map(m -> m.matcher(normalise)).filter(java.util.regex.Matcher::find)
                .map(java.util.regex.Matcher::group).findFirst();
    }

    /** Minuscules, sans accents, apostrophes typographiques ramenées à « ' ». */
    static String normaliser(String texte) {
        return Normalizer.normalize(texte, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('’', '\'').toLowerCase(Locale.ROOT);
    }
}
