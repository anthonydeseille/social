package fr.deseille.social.corpus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Texte source ingéré : un Code (LEGITEXT) ou une convention collective (KALICONT).
 * Le {@code sourceId} est l'identifiant stable DILA et sert de clé d'upsert.
 */
@Entity
@Table(name = "texte")
public class Texte {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_id", nullable = false, unique = true)
    private String sourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TypeTexte type;

    /** Identifiant de convention collective (IDCC), null pour un Code. */
    @Column
    private Integer idcc;

    @Column(nullable = false)
    private String titre;

    @Enumerated(EnumType.STRING)
    @Column
    private EtatJuridique etat;

    @Column(name = "date_debut")
    private LocalDate dateDebut;

    @Column(name = "date_fin")
    private LocalDate dateFin;

    @Column(name = "cree_le", nullable = false)
    private Instant creeLe;

    @Column(name = "maj_le", nullable = false)
    private Instant majLe;

    protected Texte() {
        // requis par JPA
    }

    public Texte(String sourceId, TypeTexte type, String titre) {
        this.sourceId = sourceId;
        this.type = type;
        this.titre = titre;
    }

    @PrePersist
    void onCreate() {
        this.creeLe = Instant.now();
        this.majLe = this.creeLe;
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

    public TypeTexte getType() {
        return type;
    }

    public Integer getIdcc() {
        return idcc;
    }

    public void setIdcc(Integer idcc) {
        this.idcc = idcc;
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

    public Instant getCreeLe() {
        return creeLe;
    }

    public Instant getMajLe() {
        return majLe;
    }
}
