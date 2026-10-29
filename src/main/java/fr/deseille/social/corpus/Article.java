package fr.deseille.social.corpus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Article versionné (LEGIARTI / KALIARTI).
 * <p>
 * {@code sourceId} identifie une <em>version</em> d'article (clé d'upsert) ; {@code cid}
 * est l'identité stable qui regroupe toutes les versions d'un même article. On n'écrase
 * jamais une version : une nouvelle version arrive avec un nouveau {@code sourceId}.
 */
@Entity
@Table(name = "article")
public class Article {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_id", nullable = false, unique = true)
    private String sourceId;

    @Column
    private String cid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "texte_id", nullable = false)
    private Texte texte;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "section_id")
    private Section section;

    @Column
    private String num;

    @Enumerated(EnumType.STRING)
    @Column
    private EtatJuridique etat;

    @Column(name = "date_debut")
    private LocalDate dateDebut;

    @Column(name = "date_fin")
    private LocalDate dateFin;

    @Column(name = "int_ordre")
    private Integer intOrdre;

    @Column(name = "contenu_texte", columnDefinition = "text")
    private String contenuTexte;

    @Column(name = "contenu_html", columnDefinition = "text")
    private String contenuHtml;

    @Column(columnDefinition = "text")
    private String nota;

    @Column(name = "contenu_hash", nullable = false)
    private String contenuHash;

    /** Empreinte des métadonnées (section, ordre, dates, cid), hors contenu et état. */
    @Column(name = "meta_hash")
    private String metaHash;

    /** Date à laquelle l'article a disparu du dump source ; null tant qu'il y est présent. */
    @Column(name = "disparu_le")
    private Instant disparuLe;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "donnees_brutes", columnDefinition = "jsonb")
    private String donneesBrutes;

    @Column(name = "ingere_le", nullable = false)
    private Instant ingereLe;

    @Column(name = "maj_le", nullable = false)
    private Instant majLe;

    protected Article() {
        // requis par JPA
    }

    public Article(String sourceId, Texte texte) {
        this.sourceId = sourceId;
        this.texte = texte;
    }

    @PrePersist
    void onCreate() {
        this.ingereLe = Instant.now();
        this.majLe = this.ingereLe;
    }

    @PreUpdate
    void onUpdate() {
        this.majLe = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getCid() {
        return cid;
    }

    public void setCid(String cid) {
        this.cid = cid;
    }

    public Texte getTexte() {
        return texte;
    }

    public Section getSection() {
        return section;
    }

    public void setSection(Section section) {
        this.section = section;
    }

    public String getNum() {
        return num;
    }

    public void setNum(String num) {
        this.num = num;
    }

    public EtatJuridique getEtat() {
        return etat;
    }

    public void setEtat(EtatJuridique etat) {
        this.etat = etat;
    }

    public LocalDate getDateDebut() {
        return dateDebut;
    }

    public void setDateDebut(LocalDate dateDebut) {
        this.dateDebut = dateDebut;
    }

    public LocalDate getDateFin() {
        return dateFin;
    }

    public void setDateFin(LocalDate dateFin) {
        this.dateFin = dateFin;
    }

    public Integer getIntOrdre() {
        return intOrdre;
    }

    public void setIntOrdre(Integer intOrdre) {
        this.intOrdre = intOrdre;
    }

    public String getContenuTexte() {
        return contenuTexte;
    }

    public void setContenuTexte(String contenuTexte) {
        this.contenuTexte = contenuTexte;
    }

    public String getContenuHtml() {
        return contenuHtml;
    }

    public void setContenuHtml(String contenuHtml) {
        this.contenuHtml = contenuHtml;
    }

    public String getNota() {
        return nota;
    }

    public void setNota(String nota) {
        this.nota = nota;
    }

    public String getContenuHash() {
        return contenuHash;
    }

    public void setContenuHash(String contenuHash) {
        this.contenuHash = contenuHash;
    }

    public String getMetaHash() {
        return metaHash;
    }

    public void setMetaHash(String metaHash) {
        this.metaHash = metaHash;
    }

    public Instant getDisparuLe() {
        return disparuLe;
    }

    public void setDisparuLe(Instant disparuLe) {
        this.disparuLe = disparuLe;
    }

    public String getDonneesBrutes() {
        return donneesBrutes;
    }

    public void setDonneesBrutes(String donneesBrutes) {
        this.donneesBrutes = donneesBrutes;
    }

    public Instant getIngereLe() {
        return ingereLe;
    }

    /** Mis à jour par {@link PreUpdate}, ou explicitement par les mises à jour en masse. */
    public Instant getMajLe() {
        return majLe;
    }
}
