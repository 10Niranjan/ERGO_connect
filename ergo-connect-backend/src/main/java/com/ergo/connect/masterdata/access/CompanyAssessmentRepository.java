package com.ergo.connect.masterdata.access;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

/** Package-private: scope is checked by CompanyAssessmentService through CompanyService. */
interface CompanyAssessmentRepository extends Repository<CompanyAssessment, Long> {

	CompanyAssessment save(CompanyAssessment assessment);

	List<CompanyAssessment> findByCompanyIdOrderByAssessmentDateDescIdDesc(long companyId);

	Optional<CompanyAssessment> findByIdAndCompanyId(long id, long companyId);

	boolean existsByCompanyIdAndKind(long companyId, CompanyAssessmentDtos.AssessmentKind kind);

}
