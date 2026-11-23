package fr.deseille.social.entreprise;

import fr.deseille.social.entreprise.EntrepriseService.Entreprise;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/entreprises?q=} : entreprises par nom, SIREN ou SIRET, avec leurs conventions
 * collectives ({@link EntrepriseService}) ; 503 si l'annuaire public ne répond pas.
 */
@RestController
@RequestMapping("/api/entreprises")
public class EntrepriseController {

    private final EntrepriseService service;

    public EntrepriseController(EntrepriseService service) {
        this.service = service;
    }

    @GetMapping
    public List<Entreprise> chercher(@RequestParam @NotBlank @Size(min = 3, max = 100) String q) {
        return service.chercher(q);
    }
}
