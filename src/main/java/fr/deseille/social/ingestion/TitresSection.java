package fr.deseille.social.ingestion;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tient à jour {@code article.titres_section} : titres des sections englobantes, de la racine à la
 * plus proche, séparés par « > ». La colonne entre dans le vecteur de recherche (changeset 008).
 * Recalculée pour tout le texte en fin de step, et pas à l'écriture de chaque article : le writer ne
 * réécrit que les articles modifiés, alors qu'un titre de section peut changer seul. Seules les lignes
 * dont les titres changent sont mises à jour.
 */
@Service
public class TitresSection {

    private static final Logger log = LoggerFactory.getLogger(TitresSection.class);

    private static final String MISE_A_JOUR = """
            with recursive chaine as (
                select s.id as depart, s.parent_id, s.titre, 1 as profondeur
                from section s where s.texte_id = :texte
                union all
                select c.depart, p.parent_id, p.titre, c.profondeur + 1
                from chaine c join section p on p.id = c.parent_id
            ), titres as (
                select depart, string_agg(titre, ' > ' order by profondeur desc) as titres
                from chaine where titre is not null group by depart
            )
            update article a set titres_section = t.titres
            from titres t
            where a.section_id = t.depart and a.texte_id = :texte
              and a.titres_section is distinct from t.titres
            """;

    private final JdbcClient jdbcClient;

    public TitresSection(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** @return nombre d'articles dont les titres ont changé */
    @Transactional
    public int mettreAJour(Long texteId) {
        int modifies = jdbcClient.sql(MISE_A_JOUR).param("texte", texteId).update();
        log.info("Titres de section : {} article(s) mis à jour", modifies);
        return modifies;
    }
}
