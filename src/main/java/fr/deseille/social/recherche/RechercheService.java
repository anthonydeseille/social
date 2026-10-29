package fr.deseille.social.recherche;

import fr.deseille.social.corpus.Article;
import fr.deseille.social.corpus.EtatJuridique;
import fr.deseille.social.corpus.Texte;
import fr.deseille.social.corpus.TexteRepository;
import fr.deseille.social.corpus.TypeTexte;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recherche dans les textes qui concernent le salarié : le Code du travail et, si l'IDCC est
 * fourni, sa convention collective.
 */
@Service
@Transactional(readOnly = true)
public class RechercheService {

    private final TexteRepository texteRepository;
    private final RechercheRepository rechercheRepository;
    private final Clock clock;

    public RechercheService(TexteRepository texteRepository, RechercheRepository rechercheRepository, Clock clock) {
        this.texteRepository = texteRepository;
        this.rechercheRepository = rechercheRepository;
        this.clock = clock;
    }

    /**
     * @param seulementApplicables ne retenir que les articles applicables aujourd'hui
     */
    public List<Resultat> rechercher(String requete, Integer idcc, boolean seulementApplicables, int limite) {
        LocalDate aujourdHui = LocalDate.now(clock);
        List<String> textes = new ArrayList<>();
        texteRepository.findByType(TypeTexte.CODE).stream().map(Texte::getSourceId).forEach(textes::add);
        if (idcc != null) {
            texteRepository.findByIdcc(idcc).stream().map(Texte::getSourceId).forEach(textes::add);
        }
        if (textes.isEmpty()) {
            return List.of();
        }
        return rechercheRepository.rechercher(requete, textes, seulementApplicables, aujourdHui, limite).stream()
                .map(l -> new Resultat(l.sourceId(), l.num(), l.etat(),
                        Article.estApplicable(l.etat(), l.dateDebut(), l.dateFin(), l.disparuLe(), aujourdHui),
                        l.texteSourceId(), l.texteType(), l.texteTitre(), l.extrait(), l.rang()))
                .toList();
    }

    /**
     * Article trouvé. L'extrait est du texte brut où seules les balises {@code <mark>} entourent
     * les termes trouvés : à échapper côté client hormis ces balises.
     */
    public record Resultat(String articleSourceId, String num, EtatJuridique etat, boolean applicable,
                           String texteSourceId, TypeTexte texteType, String texteTitre, String extrait,
                           double pertinence) {
    }
}
