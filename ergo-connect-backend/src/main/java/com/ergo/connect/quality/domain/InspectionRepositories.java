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

	/** ERGO KAM's scope (SRS §8.2): every inspection tagged to one of their assigned customer accounts, regardless
	 * of {@code customerVisible} - that flag gates the customer's own view, not ERGO's. */
	List<Inspection> findByCustomerCompanyIdInOrderByIdDesc(Collection<Long> customerCompanyIds);

	/** Every customer-side role's scope (SRS §8.2): own company, and only what ERGO has marked visible to them. */
	List<Inspection> findByCustomerCompanyIdAndCustomerVisibleTrueOrderByIdDesc(long customerCompanyId);

	/** Every supplier-side role's scope (SRS §8.2): own company, unfiltered by {@code customerVisible}. */
	List<Inspection> findBySupplierCompanyIdOrderByIdDesc(long supplierCompanyId);

	/** ERGO NPD/OFP's scope (SRS §10.8 "RV"): inspections raised against their own module's records. */
	List<Inspection> findByLinkedModuleOrderByIdDesc(LinkedModule linkedModule);

}

interface InspectionDefectRepository extends Repository<InspectionDefect, Long> {

	InspectionDefect save(InspectionDefect defect);

	List<InspectionDefect> findByInspectionIdOrderById(Long inspectionId);

	void deleteByInspectionId(Long inspectionId);

}
