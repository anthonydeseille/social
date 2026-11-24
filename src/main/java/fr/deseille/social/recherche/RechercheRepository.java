package fr.deseille.social.recherche;

import fr.deseille.social.corpus.EtatJuridique;
import fr.deseille.social.corpus.TypeTexte;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Recherche plein texte des articles (PostgreSQL) sur la colonne générée {@code recherche}
 * (changeset 007) : configuration {@code francais} insensible aux accents, syntaxe
 * {@code websearch_to_tsquery} (« expression exacte », or, -exclusion), classement par pertinence
 * et extrait surligné. Une requête sans terme positif (mots vides seuls, exclusion seule) ne
 * renvoie rien ({@code querytree} vaut {@code T}) plutôt que presque tout le corpus.
 * <p>
 * La « partie législative ancienne » et la « partie réglementaire ancienne » du Code du travail
 * (numérotation d'avant la recodification de 2008) sont écartées ({@link #HORS_PARTIE_ANCIENNE}) : la
 * DILA y laisse en vigueur quelques dizaines d'articles transitoires (L321-4-2, L143-11-4…) qui, sinon,
 * occupent des places parmi les articles transmis à la rédaction pour des questions d'aujourd'hui.
 */
@Repository
public class RechercheRepository {

    /**
     * Applicabilité à {@code :date}, reproduction SQL de {@code Article.estApplicable} : les
     * modifier ensemble.
     */
    static final String APPLICABLE = """
            a.etat in ('VIGUEUR', 'VIGUEUR_DIFF', 'VIGUEUR_ETEN', 'VIGUEUR_NON_ETEN', 'ABROGE_DIFF')
            and a.disparu_le is null
            and not (a.etat = 'VIGUEUR_DIFF' and a.date_debut is null)
            and (a.date_debut is null or a.date_debut <= :date)
            and (a.date_fin is null or :date < a.date_fin)
            """;

    /** Articles hors des parties anciennes du Code du travail, par le premier titre de section. */
    static final String HORS_PARTIE_ANCIENNE =
            "coalesce(a.titres_section, '') !~ '^Partie (législative|réglementaire) ancienne'";

    private static final String REQUETE = """
            select a.source_id, a.num, a.etat, a.date_debut, a.date_fin, a.disparu_le,
                   t.source_id as texte_source_id, t.type as texte_type, t.titre as texte_titre,
                   ts_rank_cd(a.recherche, q) as rang,
                   ts_headline('francais',
                       coalesce(a.contenu_texte, regexp_replace(coalesce(a.contenu_html, ''), '<[^>]+>', ' ', 'g')),
                       q, 'StartSel=<mark>, StopSel=</mark>, MaxWords=30, MinWords=12, MaxFragments=2, FragmentDelimiter=" … "')
                       as extrait
            from article a
            join texte t on t.id = a.texte_id,
                 websearch_to_tsquery('francais', :requete) q
            where a.recherche @@ q
              and querytree(q) <> 'T'
              and t.source_id in (:textes)
              and %s
              %s
            order by rang desc, a.num, a.id
            limit :limite
            """;

    private final JdbcClient jdbcClient;

    public RechercheRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<Ligne> rechercher(String requete, Collection<String> texteSourceIds, boolean seulementApplicables,
                                  LocalDate date, int limite) {
        return jdbcClient.sql(REQUETE.formatted(HORS_PARTIE_ANCIENNE, seulementApplicables ? "and " + APPLICABLE : ""))
                .param("requete", requete)
                .param("textes", texteSourceIds)
                .param("date", date)
                .param("limite", limite)
                .query((rs, i) -> new Ligne(
                        rs.getString("source_id"),
                        rs.getString("num"),
                        rs.getString("etat") == null ? null : EtatJuridique.valueOf(rs.getString("etat")),
                        rs.getObject("date_debut", LocalDate.class),
                        rs.getObject("date_fin", LocalDate.class),
                        instant(rs.getTimestamp("disparu_le")),
                        rs.getString("texte_source_id"),
                        TypeTexte.valueOf(rs.getString("texte_type")),
                        rs.getString("texte_titre"),
                        rs.getDouble("rang"),
                        rs.getString("extrait")))
                .list();
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /** Ligne brute de résultat, avant mise en forme par le service. */
    public record Ligne(String sourceId, String num, EtatJuridique etat, LocalDate dateDebut, LocalDate dateFin,
                        Instant disparuLe, String texteSourceId, TypeTexte texteType, String texteTitre,
                        double rang, String extrait) {
    }
}
