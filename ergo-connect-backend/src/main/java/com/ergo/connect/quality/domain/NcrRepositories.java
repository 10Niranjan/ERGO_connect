package com.ergo.connect.quality.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

/** Package-private; NcrService applies the record scope (SRS §8.2) for every read - same shape as
 * {@link InspectionRepository}/{@link InspectionDefectRepository}. */
interface NcrRepository extends Repository<Ncr, Long> {

	Ncr save(Ncr ncr);

	Optional<Ncr> findById(Long id);

	/** ERGO Admin/Management/Quality: unrestricted (SRS §10.8 "V(all)"). Used by the facade; the list is paged. */
	List<Ncr> findAllByOrderByIdDesc();

	/** The list page. The only Specification method on purpose (JpaSpecificationExecutor would bring bulk update/delete that skip @Version and audit). */
	org.springframework.data.domain.Page<Ncr> findAll(org.springframework.data.jpa.domain.Specification<Ncr> spec,
			org.springframework.data.domain.Pageable pageable);

	/** ERGO KAM's scope (SRS §8.2): every NCR tagged to one of their assigned customer accounts. */
	List<Ncr> findByCustomerCompanyIdInOrderByIdDesc(Collection<Long> customerCompanyIds);

	/** Every customer-side role's scope: own company, gated by {@code customer_visible} (security review H2, same
	 * shape as {@code InspectionRepository.findByCustomerCompanyIdAndCustomerVisibleTrueOrderByIdDesc}) - an ERGO
	 * editor must review and flip this on before a customer sees an NCR tagged to it. */
	List<Ncr> findByCustomerCompanyIdAndCustomerVisibleTrueOrderByIdDesc(long customerCompanyId);

	/** Every supplier-side role's scope: own company, direct {@code supplierCompanyId} match. */
	List<Ncr> findBySupplierCompanyIdOrderByIdDesc(long supplierCompanyId);

	/** ERGO NPD/OFP's scope (SRS §10.8 "RV"): NCRs raised against their own module's records. */
	List<Ncr> findByLinkedModuleOrderByIdDesc(LinkedModule linkedModule);

	/** The overdue scan. */
	List<Ncr> findByTargetClosureDateBeforeAndCurrentStatusNot(java.time.LocalDate date, NcrStatus status);

}

interface CapaActionRepository extends Repository<CapaAction, Long> {

	CapaAction save(CapaAction capaAction);

	Optional<CapaAction> findById(Long id);

	List<CapaAction> findByNcrIdOrderById(Long ncrId);

	/** The overdue scan. */
	List<CapaAction> findByTargetDateBeforeAndStatusNot(java.time.LocalDate date, CapaActionStatus status);

	/** Reassignment clears already-authored CAPA actions (security review M6 precedent), same as {@code
	 * InspectionDefectRepository.deleteByInspectionId}. */
	void deleteByNcrId(Long ncrId);

}
