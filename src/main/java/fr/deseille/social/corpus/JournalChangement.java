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
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Entrée du journal des changements détectés sur un article lors d'une ingestion.
 * Traçable : porte l'identifiant d'exécution batch et la révision git du dump source.
 */
@Entity
@Table(name = "journal_changement")
public class JournalChangement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "article_source_id", nullable = false)
    private String articleSourceId;

    @Column
    private String cid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "texte_id", nullable = false)
    private Texte texte;

    @Enumerated(EnumType.STRING)
    @Column(name = "type_changement", nullable = false)
    private TypeChangement typeChangement;

    @Enumerated(EnumType.STRING)
    @Column(name = "etat_avant")
    private EtatJuridique etatAvant;

    @Enumerated(EnumType.STRING)
    @Column(name = "etat_apres")
    private EtatJuridique etatApres;

    @Column(name = "hash_avant")
    private String hashAvant;

    @Column(name = "hash_apres")
    private String hashApres;

    @Column(name = "detecte_le", nullable = false)
    private Instant detecteLe;

    @Column(name = "execution_id")
    private Long executionId;

    @Column(name = "revision_source")
    private String revisionSource;

    protected JournalChangement() {
        // requis par JPA
    }

    public JournalChangement(String articleSourceId, Texte texte, TypeChangement typeChangement) {
        this.articleSourceId = articleSourceId;
        this.texte = texte;
        this.typeChangement = typeChangement;
    }

    @PrePersist
    void onCreate() {
        this.detecteLe = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getArticleSourceId() {
        return articleSourceId;
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

    public TypeChangement getTypeChangement() {
        return typeChangement;
    }

    public EtatJuridique getEtatAvant() {
        return etatAvant;
    }

    public void setEtatAvant(EtatJuridique etatAvant) {
        this.etatAvant = etatAvant;
    }

    public EtatJuridique getEtatApres() {
        return etatApres;
    }

    public void setEtatApres(EtatJuridique etatApres) {
        this.etatApres = etatApres;
    }

    public String getHashAvant() {
        return hashAvant;
    }

    public void setHashAvant(String hashAvant) {
        this.hashAvant = hashAvant;
    }

    public String getHashApres() {
        return hashApres;
    }

    public void setHashApres(String hashApres) {
        this.hashApres = hashApres;
    }

    public Long getExecutionId() {
        return executionId;
    }

    public void setExecutionId(Long executionId) {
        this.executionId = executionId;
    }

    public String getRevisionSource() {
        return revisionSource;
    }

    public void setRevisionSource(String revisionSource) {
        this.revisionSource = revisionSource;
    }
}
