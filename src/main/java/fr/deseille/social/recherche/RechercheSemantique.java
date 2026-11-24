package fr.deseille.social.recherche;

import fr.deseille.social.corpus.EtatJuridique;
import fr.deseille.social.corpus.TypeTexte;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Recherche sémantique : chaque article est représenté par un vecteur (embeddings, modèle local
 * bge-m3 par défaut, via Ollama), comparé à celui de la question par similarité cosinus (pgvector).
 * Elle retrouve les articles dont les mots diffèrent de ceux de la question (« maintien de salaire »
 * pour « indemnité complémentaire »), que la recherche plein texte manque.
 * <p>
 * Désactivée par défaut ({@code droits.semantique.actif}) : vectoriser le corpus prend plusieurs
 * heures sur un processeur sans carte graphique. Le texte vectorisé est court (titres des deux
 * dernières sections, numéro, début de l'article), et chaque vecteur est gardé dans un cache disque
 * indexé par l'empreinte du modèle et du texte : une base recréée, ou une nouvelle ingestion, ne
 * recalcule que les articles modifiés.
 */
@Component
public class RechercheSemantique {

    private static final Logger log = LoggerFactory.getLogger(RechercheSemantique.class);
    private static final int LOT = 16;
    /** Dimension des vecteurs (bge-m3), celle de la colonne {@code article_vecteur.vecteur} (changeset 009). */
    static final int DIMENSION = 1024;

    private static final String REQUETE = """
            select a.source_id, a.num, a.section_id, a.etat, a.date_debut, a.date_fin, a.disparu_le,
                   t.source_id as texte_source_id, t.type as texte_type, t.titre as texte_titre,
                   1 - (v.vecteur <=> cast(:vecteur as vector)) as rang
            from article_vecteur v
            join article a on a.id = v.article_id
            join texte t on t.id = a.texte_id
            where t.source_id in (:textes)
              and %s
            order by v.vecteur <=> cast(:vecteur as vector)
            limit :limite
            """.formatted(RechercheRepository.APPLICABLE);

    private final JdbcClient jdbc;
    private final ObjectProvider<EmbeddingModel> modeles;
    private final boolean actif;
    private final String modele;
    private final int caracteres;
    private final Path cache;

    public RechercheSemantique(JdbcClient jdbc, ObjectProvider<EmbeddingModel> modeles,
                               @Value("${droits.semantique.actif:false}") boolean actif,
                               @Value("${spring.ai.ollama.embedding.options.model:bge-m3}") String modele,
                               @Value("${droits.semantique.caracteres:500}") int caracteres,
                               @Value("${droits.semantique.cache-dir:${java.io.tmpdir}/droits-vecteurs}") Path cache) {
        this.jdbc = jdbc;
        this.modeles = modeles;
        this.actif = actif;
        this.modele = modele;
        this.caracteres = caracteres;
        this.cache = cache;
    }

    public boolean actif() {
        return actif && modeles.getIfAvailable() != null;
    }

    /**
     * Vectorise les articles applicables aujourd'hui des textes donnés qui n'ont pas de vecteur à jour.
     *
     * @return nombre d'articles vectorisés (calculés ou repris du cache)
     */
    public int vectoriser(Collection<String> texteSourceIds) {
        EmbeddingModel embeddings = modeles.getObject();
        List<AVectoriser> articles = jdbc.sql("""
                        select a.id, a.num, a.titres_section,
                               coalesce(a.contenu_texte, regexp_replace(coalesce(a.contenu_html, ''), '<[^>]+>', ' ', 'g')) as contenu,
                               v.empreinte
                        from article a
                        join texte t on t.id = a.texte_id
                        left join article_vecteur v on v.article_id = a.id
                        where t.source_id in (:textes) and %s
                        order by a.id
                        """.formatted(RechercheRepository.APPLICABLE))
                .param("textes", texteSourceIds).param("date", LocalDate.now())
                .query((rs, i) -> {
                    String texte = texte(rs.getString("num"), rs.getString("titres_section"), rs.getString("contenu"));
                    return new AVectoriser(rs.getLong("id"), texte, empreinte(texte), rs.getString("empreinte"));
                })
                .list().stream().filter(a -> !a.empreinte().equals(a.empreinteActuelle())).toList();
        log.info("Vectorisation : {} article(s) à mettre à jour ({})", articles.size(), modele);
        long debut = System.nanoTime();
        int faits = 0;
        for (int i = 0; i < articles.size(); i += LOT) {
            List<AVectoriser> lot = articles.subList(i, Math.min(i + LOT, articles.size()));
            List<float[]> vecteurs = new ArrayList<>();
            List<AVectoriser> aCalculer = new ArrayList<>();
            for (AVectoriser a : lot) {
                float[] enCache = lireCache(a.empreinte());
                vecteurs.add(enCache);
                if (enCache == null) {
                    aCalculer.add(a);
                }
            }
            if (!aCalculer.isEmpty()) {
                List<float[]> calcules = embeddings.embed(aCalculer.stream().map(AVectoriser::texte).toList());
                for (int k = 0, j = 0; k < lot.size(); k++) {
                    if (vecteurs.get(k) == null) {
                        vecteurs.set(k, calcules.get(j));
                        ecrireCache(aCalculer.get(j).empreinte(), calcules.get(j));
                        j++;
                    }
                }
            }
            for (int k = 0; k < lot.size(); k++) {
                jdbc.sql("""
                                insert into article_vecteur (article_id, empreinte, vecteur)
                                values (:id, :empreinte, cast(:vecteur as vector))
                                on conflict (article_id) do update set empreinte = excluded.empreinte, vecteur = excluded.vecteur
                                """)
                        .param("id", lot.get(k).id()).param("empreinte", lot.get(k).empreinte())
                        .param("vecteur", litteral(vecteurs.get(k))).update();
            }
            faits += lot.size();
            if (faits % (LOT * 20) == 0) {
                log.info("Vectorisation : {}/{} ({} s)", faits, articles.size(), (System.nanoTime() - debut) / 1_000_000_000);
            }
        }
        return faits;
    }

    /** Articles applicables les plus proches du texte, par similarité cosinus (de 0 à 1 environ). */
    public List<RechercheService.Resultat> rechercher(String texte, Collection<String> texteSourceIds, LocalDate date,
                                                      int limite) {
        String vecteur = litteral(modeles.getObject().embed(texte));
        return jdbc.sql(REQUETE).param("vecteur", vecteur).param("textes", texteSourceIds).param("date", date)
                .param("limite", limite)
                .query((rs, i) -> {
                    EtatJuridique etat = EtatJuridique.valueOf(rs.getString("etat"));
                    LocalDate dateDebut = rs.getObject("date_debut", LocalDate.class);
                    LocalDate dateFin = rs.getObject("date_fin", LocalDate.class);
                    Timestamp disparu = rs.getTimestamp("disparu_le");
                    Instant disparuLe = disparu == null ? null : disparu.toInstant();
                    return new RechercheService.Resultat(rs.getString("source_id"), rs.getString("num"), etat,
                            fr.deseille.social.corpus.Article.estApplicable(etat, dateDebut, dateFin, disparuLe, date),
                            rs.getString("texte_source_id"), TypeTexte.valueOf(rs.getString("texte_type")),
                            rs.getString("texte_titre"), null, rs.getDouble("rang"));
                })
                .list();
    }

    /** Texte vectorisé : deux derniers titres de section, numéro, puis début de l'article. */
    String texte(String num, String titresSection, String contenu) {
        StringBuilder t = new StringBuilder();
        if (titresSection != null) {
            String[] titres = titresSection.split(" > ");
            for (int i = Math.max(0, titres.length - 2); i < titres.length; i++) {
                t.append(titres[i]).append(". ");
            }
        }
        if (num != null) {
            t.append("Article ").append(num).append(" : ");
        }
        t.append(contenu == null ? "" : contenu.replaceAll("\\s+", " ").trim());
        return t.length() <= caracteres ? t.toString() : t.substring(0, caracteres);
    }

    private String empreinte(String texte) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest((modele + "\n" + caracteres + "\n" + texte).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    float[] lireCache(String empreinte) {
        Path f = cache.resolve(empreinte.substring(0, 2)).resolve(empreinte + ".f32");
        try {
            if (!Files.exists(f)) {
                return null;
            }
            byte[] octets = Files.readAllBytes(f);
            if (octets.length != DIMENSION * Float.BYTES) {
                return null; // fichier tronqué ou d'un autre modèle : recalculé
            }
            ByteBuffer b = ByteBuffer.wrap(octets).order(ByteOrder.LITTLE_ENDIAN);
            float[] v = new float[b.remaining() / Float.BYTES];
            b.asFloatBuffer().get(v);
            return v;
        } catch (IOException e) {
            return null;
        }
    }

    void ecrireCache(String empreinte, float[] vecteur) {
        Path f = cache.resolve(empreinte.substring(0, 2)).resolve(empreinte + ".f32");
        try {
            Files.createDirectories(f.getParent());
            ByteBuffer b = ByteBuffer.allocate(vecteur.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            b.asFloatBuffer().put(vecteur);
            // Écrit à côté puis renommé : un arrêt en cours d'écriture ne laisse pas de fichier tronqué
            Path temporaire = Files.createTempFile(f.getParent(), empreinte, ".tmp");
            Files.write(temporaire, b.array());
            Files.move(temporaire, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Cache des vecteurs indisponible ({}) : le vecteur sera recalculé", e.getClass().getSimpleName());
        }
    }

    private static String litteral(float[] v) {
        StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            s.append(i == 0 ? "" : ",").append(v[i]);
        }
        return s.append(']').toString();
    }

    private record AVectoriser(long id, String texte, String empreinte, String empreinteActuelle) {
    }
}
