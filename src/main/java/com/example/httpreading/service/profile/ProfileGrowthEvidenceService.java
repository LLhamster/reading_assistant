package com.example.httpreading.service.profile;

import java.util.List;
import java.util.Optional;

import com.example.httpreading.domain.profile.ProfileGrowthEvidence;
import com.example.httpreading.repository.ProfileGrowthEvidenceRepository;
import org.springframework.stereotype.Service;

@Service
public class ProfileGrowthEvidenceService {
    private final ProfileGrowthEvidenceRepository repository;
    private final BookCategoryService bookCategoryService;

    public ProfileGrowthEvidenceService(ProfileGrowthEvidenceRepository repository,
                                        BookCategoryService bookCategoryService) {
        this.repository = repository;
        this.bookCategoryService = bookCategoryService;
    }

    public ProfileGrowthEvidence saveEvidence(ProfileGrowthEvidence evidence) {
        if (!"style".equals(evidence.getEvidenceDomain()) && !"reading_understanding".equals(evidence.getEvidenceDomain())) {
            throw new IllegalArgumentException("unsupported evidenceDomain");
        }
        if ("reading_understanding".equals(evidence.getEvidenceDomain())) {
            evidence.setBookCategory(bookCategoryService.normalize(evidence.getBookCategory()));
        }
        if (evidence.getImportance() == null) {
            evidence.setImportance(0.5d);
        }
        return repository.save(evidence);
    }

    public List<ProfileGrowthEvidence> findByDomain(String userId, String evidenceDomain) {
        return repository.findByUserIdAndEvidenceDomainAndStatusOrderByCreatedAtDesc(userId, evidenceDomain, "active");
    }

    public List<ProfileGrowthEvidence> recentEvidence(String userId, int limit) {
        List<ProfileGrowthEvidence> all = repository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, "active");
        return all.stream().limit(limit <= 0 ? 20 : limit).toList();
    }

    public Optional<ProfileGrowthEvidence> findByAnnotationId(Long annotationId) {
        return repository.findByRelatedAnnotationId(annotationId);
    }

    public List<ProfileGrowthEvidence> recentReadingNotes(String userId, int limit) {
        List<ProfileGrowthEvidence> all =
            repository.findByUserIdAndEvidenceTypeAndStatusOrderByUpdatedAtDesc(userId, "reading_note", "active");
        return all.stream().limit(limit <= 0 ? 30 : limit).toList();
    }
}
