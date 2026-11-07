package fr.deseille.social.questions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Consignes données au modèle, lues dans les fichiers {@code prompts/} du classpath (application :
 * {@code src/main/resources/prompts/}, juge de l'évaluation : {@code src/test/resources/prompts/}).
 * <p>
 * La version d'une consigne est l'empreinte (SHA-256, 8 caractères hexadécimaux) du texte exact
 * envoyé au modèle : elle change d'elle-même à chaque retouche, sans numéro à incrémenter, et relie
 * une réponse, un rapport ou une ligne d'historique aux consignes qui l'ont produite. Le texte d'un
 * fichier est lu en UTF-8, fins de ligne ramenées à {@code \n} et blancs finaux retirés, pour que
 * la version ne dépende ni du système ni de l'éditeur.
 */
public final class Consignes {

    private Consignes() {
    }

    /** Consigne du fichier {@code prompts/<nom>.md} ; une consigne absente empêche le démarrage. */
    public static String lire(String nom) {
        String ressource = "/prompts/" + nom + ".md";
        try (InputStream in = Consignes.class.getResourceAsStream(ressource)) {
            if (in == null) {
                throw new IllegalStateException("Consigne introuvable : " + ressource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").stripTrailing();
        } catch (IOException e) {
            throw new UncheckedIOException("Consigne illisible : " + ressource, e);
        }
    }

    /** Version d'un texte de consignes : les 8 premiers caractères de son empreinte SHA-256. */
    public static String version(String texte) {
        try {
            byte[] empreinte = MessageDigest.getInstance("SHA-256").digest(texte.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(empreinte).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
