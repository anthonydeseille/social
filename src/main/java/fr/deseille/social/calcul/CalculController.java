package fr.deseille.social.calcul;

import fr.deseille.social.calcul.Indemnite.Demande;
import fr.deseille.social.calcul.Indemnite.Resultat;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/calculs/indemnite-licenciement} : indemnité légale et conventionnelle, montant le
 * plus favorable, détail du calcul, sources et hypothèses. Gratuit (aucun appel au modèle). Comme
 * pour les questions, la demande n'est ni stockée ni journalisée.
 */
@RestController
@RequestMapping("/api/calculs")
public class CalculController {

    private final CalculIndemniteService service;

    public CalculController(CalculIndemniteService service) {
        this.service = service;
    }

    @PostMapping("/indemnite-licenciement")
    public Resultat indemniteLicenciement(@Valid @RequestBody Demande demande) {
        return service.calculer(demande);
    }
}
