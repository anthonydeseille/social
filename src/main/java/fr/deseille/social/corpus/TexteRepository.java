package fr.deseille.social.corpus;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TexteRepository extends JpaRepository<Texte, Long> {

    Optional<Texte> findBySourceId(String sourceId);

    List<Texte> findByType(TypeTexte type);

    List<Texte> findByIdcc(Integer idcc);

    List<Texte> findByIdccIn(java.util.Collection<Integer> idcc);
}
