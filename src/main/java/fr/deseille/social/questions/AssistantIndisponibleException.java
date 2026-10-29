package fr.deseille.social.questions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Le service du modèle de langage ne répond pas (clé absente, quota, panne, délai dépassé) : rendu en
 * 503 au format ProblemDetail, sans exposer le détail technique au client.
 */
public class AssistantIndisponibleException extends ErrorResponseException {

    public AssistantIndisponibleException(Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Le service de réponse est momentanément indisponible. Réessayez plus tard."), cause);
    }
}
