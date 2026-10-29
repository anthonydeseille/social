package fr.deseille.social.consultation;

import fr.deseille.social.consultation.VuesConsultation.ArticleDetail;
import fr.deseille.social.consultation.VuesConsultation.ArticleResume;
import fr.deseille.social.consultation.VuesConsultation.Historique;
import fr.deseille.social.consultation.VuesConsultation.Sommaire;
import fr.deseille.social.consultation.VuesConsultation.TexteResume;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de consultation du corpus (lecture seule).
 * <ul>
 *   <li>{@code GET /api/textes} : textes disponibles (Code, conventions)</li>
 *   <li>{@code GET /api/textes/{id}/sommaire[?section=]} : un niveau du sommaire</li>
 *   <li>{@code GET /api/textes/{id}/articles?num=} : versions d'un article par numéro</li>
 *   <li>{@code GET /api/articles/{id}} : article complet et sa place dans le sommaire</li>
 *   <li>{@code GET /api/articles/{id}/historique} : versions et journal des changements</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class ConsultationController {

    private final ConsultationService service;

    public ConsultationController(ConsultationService service) {
        this.service = service;
    }

    @GetMapping("/textes")
    public List<TexteResume> textes() {
        return service.textes();
    }

    @GetMapping("/textes/{texteId}/sommaire")
    public Sommaire sommaire(@PathVariable String texteId, @RequestParam(required = false) String section) {
        return service.sommaire(texteId, section);
    }

    @GetMapping("/textes/{texteId}/articles")
    public List<ArticleResume> articlesParNumero(@PathVariable String texteId, @RequestParam String num) {
        return service.articlesParNumero(texteId, num);
    }

    @GetMapping("/articles/{articleId}")
    public ArticleDetail article(@PathVariable String articleId) {
        return service.article(articleId);
    }

    @GetMapping("/articles/{articleId}/historique")
    public Historique historique(@PathVariable String articleId) {
        return service.historique(articleId);
    }
}
