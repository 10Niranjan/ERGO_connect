package com.ergo.connect.quality.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

/** Package-private; InspectionService applies the record scope (SRS §8.2) for every read. */
interface InspectionRepository extends Repository<Inspection, Long> {

	Inspection save(Inspection inspection);

	Optional<Inspection> findById(Long id);

	/** ERGO Admin/Management/Quality: unrestricted (SRS §10.8 "V(all)"). */
	List<Inspection> findAllByOrderByIdDesc();

	/** The list page. The only Specification method on purpose (JpaSpecificationExecutor would bring bulk update/delete that skip @Version and audit). */
	org.springframework.data.domain.Page<Inspection> findAll(org.springframework.data.jpa.domain.Specification<Inspection> spec,
			org.springframework.data.domain.Pageable pageable);

	/** ERGO KAM's scope (SRS §8.2): every inspection tagged to one of their assigned customer accounts, regardless
	 * of {@code customerVisible} - that flag gates the customer's own view, not ERGO's. */
	List<Inspection> findByCustomerCompanyIdInOrderByIdDesc(Collection<Long> customerCompanyIds);

	/** Every customer-side role's scope (SRS §8.2): own company, and only what ERGO has marked visible to them. */
	List<Inspection> findByCustomerCompanyIdAndCustomerVisibleTrueOrderByIdDesc(long customerCompanyId);

	/** Every supplier-side role's scope (SRS §8.2): own company, unfiltered by {@code customerVisible}. */
	List<Inspection> findBySupplierCompanyIdOrderByIdDesc(long supplierCompanyId);

	/** ERGO NPD/OFP's scope (SRS §10.8 "RV"): inspections raised against their own module's records. */
	List<Inspection> findByLinkedModuleOrderByIdDesc(LinkedModule linkedModule);

	/** Phase 5d: every inspection against one specific NPD/OFP record, for {@code QualityApiService}'s dispatch/
	 * stage-gate reads - narrower than {@link #findByLinkedModuleOrderByIdDesc}, which is every record of that
	 * module. */
	List<Inspection> findByLinkedModuleAndLinkedRecordIdOrderById(LinkedModule linkedModule, long linkedRecordId);

}

interface InspectionDefectRepository extends Repository<InspectionDefect, Long> {

	InspectionDefect save(InspectionDefect defect);

	List<InspectionDefect> findByInspectionIdOrderById(Long inspectionId);

	void deleteByInspectionId(Long inspectionId);

}
