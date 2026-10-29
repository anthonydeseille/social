package fr.deseille.social.ingestion;

import fr.deseille.social.corpus.EtatJuridique;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Normalise les champs des nœuds unist, dont la forme diffère entre LEGI (Code) et KALI
 * (convention) : dates article en epoch-millis côté Code, absentes côté convention ;
 * contenu en {@code texte}/{@code texteHtml} côté Code, en {@code content} (HTML) côté
 * convention. Fournit aussi les empreintes servant à la détection de changement.
 */
@Component
public class MappeurNoeud {

    /** Séparateur des champs dans les empreintes : absent des contenus, évite les collisions par concaténation. */
    private static final char SEPARATEUR = '\u0000';

    private final ObjectMapper objectMapper;

    public MappeurNoeud(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String texte(JsonNode data, String champ) {
        JsonNode n = data == null ? null : data.get(champ);
        return n == null || n.isNull() ? null : n.asText();
    }

    /** Texte obligatoire : échoue explicitement plutôt que de laisser une contrainte SQL le faire. */
    public String texteObligatoire(JsonNode data, String champ, String contexte) {
        String valeur = texte(data, champ);
        if (valeur == null || valeur.isBlank()) {
            throw new IllegalStateException("Champ obligatoire « " + champ + " » absent : " + contexte);
        }
        return valeur;
    }

    public Integer entier(JsonNode data, String champ) {
        JsonNode n = data == null ? null : data.get(champ);
        return n == null || n.isNull() || !n.isNumber() ? null : n.asInt();
    }

    /**
     * État juridique, null si absent.
     *
     * @throws IllegalArgumentException si l'état n'est pas connu : le ramener à null écraserait
     *                                  l'état en base et produirait un faux changement d'état
     */
    public EtatJuridique etat(JsonNode data) {
        String valeur = texte(data, "etat");
        if (valeur == null || valeur.isBlank()) {
            return null;
        }
        try {
            return EtatJuridique.valueOf(valeur);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("État juridique inconnu : " + valeur
                    + " (à ajouter à " + EtatJuridique.class.getSimpleName() + ")", e);
        }
    }

    /**
     * Convertit une valeur de date : nombre = epoch millis (dates d'article LEGI),
     * texte = date ISO {@code yyyy-MM-dd} (dates de section). Null ou absent → null.
     *
     * @throws IllegalArgumentException si la valeur n'est pas une date reconnaissable
     */
    public LocalDate date(JsonNode data, String champ) {
        JsonNode n = data == null ? null : data.get(champ);
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isNumber()) {
            return Instant.ofEpochMilli(n.asLong()).atZone(ZoneOffset.UTC).toLocalDate();
        }
        String s = n.asText();
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Date invalide pour « " + champ + " » : " + s, e);
        }
    }

    /** Contenu brut en clair : {@code texte} (Code) sinon null. */
    public String contenuTexte(JsonNode data) {
        return texte(data, "texte");
    }

    /** Contenu HTML : {@code texteHtml} (Code) sinon {@code content} (convention). */
    public String contenuHtml(JsonNode data) {
        String html = texte(data, "texteHtml");
        return html != null ? html : texte(data, "content");
    }

    /**
     * Empreinte du contenu d'un article (texte, HTML, numéro, nota). L'état en est exclu : il est
     * comparé séparément pour distinguer un changement d'état d'une modification.
     */
    public String hashContenu(String contenuTexte, String contenuHtml, String num, String nota) {
        return empreinte(contenuTexte, contenuHtml, num, nota);
    }

    /**
     * Empreinte des métadonnées d'un article (cid, section, ordre, dates), hors contenu et état :
     * un déplacement ou une nouvelle date de fin est un changement à journaliser.
     */
    public String hashMetadonnees(String cid, String sectionSourceId, Integer intOrdre,
                                  LocalDate dateDebut, LocalDate dateFin) {
        return empreinte(cid, sectionSourceId, intOrdre, dateDebut, dateFin);
    }

    private static String empreinte(Object... valeurs) {
        StringBuilder base = new StringBuilder();
        for (Object valeur : valeurs) {
            base.append(valeur == null ? "" : valeur).append(SEPARATEUR);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(base.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    /** Sérialise le nœud {@code data} brut pour conservation en JSONB (provenance). */
    public String brut(JsonNode data) {
        return objectMapper.writeValueAsString(data);
    }
}
