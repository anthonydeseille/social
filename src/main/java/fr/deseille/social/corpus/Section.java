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
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * Section de la hiérarchie unist (LEGISCTA / KALISCTA), rattachée à un texte et
 * éventuellement à une section parente.
 */
@Entity
@Table(name = "section")
public class Section {

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
    @JoinColumn(name = "parent_id")
    private Section parent;

    @Column
    private String titre;

    @Enumerated(EnumType.STRING)
    @Column
    private EtatJuridique etat;

    @Column(name = "int_ordre")
    private Integer intOrdre;

    @Column(name = "date_debut")
    private LocalDate dateDebut;

    @Column(name = "date_fin")
    private LocalDate dateFin;

    protected Section() {
        // requis par JPA
    }

    public Section(String sourceId, Texte texte) {
        this.sourceId = sourceId;
        this.texte = texte;
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

    public Section getParent() {
        return parent;
    }

    public void setParent(Section parent) {
        this.parent = parent;
    }

    public String getTitre() {
        return titre;
    }

    public void setTitre(String titre) {
        this.titre = titre;
    }

    public EtatJuridique getEtat() {
        return etat;
    }

    public void setEtat(EtatJuridique etat) {
        this.etat = etat;
    }

    public Integer getIntOrdre() {
        return intOrdre;
    }

    public void setIntOrdre(Integer intOrdre) {
        this.intOrdre = intOrdre;
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
}
