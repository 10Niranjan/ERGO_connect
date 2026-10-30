package com.ergo.connect.quality.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

import com.ergo.connect.common.security.Role;

/** Package-private; {@code DeviationService} applies the record scope (SRS §8.2) for every read - same shape as
 * {@link NcrRepository}. */
interface DeviationRepository extends Repository<Deviation, Long> {

	Deviation save(Deviation deviation);

	Optional<Deviation> findById(Long id);

	/** ERGO Admin/Management/Quality: unrestricted (SRS §10.8 "V(all)"). */
	List<Deviation> findAllByOrderByIdDesc();

	/** The list page. The only Specification method on purpose (JpaSpecificationExecutor would bring bulk update/delete that skip @Version and audit). */
	org.springframework.data.domain.Page<Deviation> findAll(org.springframework.data.jpa.domain.Specification<Deviation> spec,
			org.springframework.data.domain.Pageable pageable);

	/** ERGO KAM's scope (SRS §8.2): every deviation tagged to one of their assigned customer accounts. */
	List<Deviation> findByCustomerCompanyIdInOrderByIdDesc(Collection<Long> customerCompanyIds);

	/** Every customer-side role's scope: own company, gated by {@code customerVisible}. */
	List<Deviation> findByCustomerCompanyIdAndCustomerVisibleTrueOrderByIdDesc(long customerCompanyId);

	/** Every supplier-side role's scope: own company, gated by {@code supplierVisible} (security review H3 fix -
	 * mirrors {@link #findByCustomerCompanyIdAndCustomerVisibleTrueOrderByIdDesc}). */
	List<Deviation> findBySupplierCompanyIdAndSupplierVisibleTrueOrderByIdDesc(long supplierCompanyId);

	/** ERGO NPD/OFP's scope (SRS §10.8 "RV"): deviations raised against their own module's records. */
	List<Deviation> findByLinkedModuleOrderByIdDesc(LinkedModule linkedModule);

	/** The expiry scheduler's own scope: every still-APPROVED deviation whose validity has passed. */
	List<Deviation> findByCurrentStatusAndValidityEndDateBefore(DeviationStatus status, LocalDate date);

}

interface DeviationApprovalRepository extends Repository<DeviationApproval, Long> {

	DeviationApproval save(DeviationApproval approval);

	List<DeviationApproval> findByDeviationIdOrderById(Long deviationId);

	/** At most one row per (deviation, role) ever exists (class comment) - the row the caller's own approver role may
	 * decide. */
	Optional<DeviationApproval> findByDeviationIdAndApproverRole(Long deviationId, Role approverRole);

}
