package com.ergo.connect.npd.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

/** Package-private; NpdService applies the record scope for every read. */
interface NpdProjectRepository extends Repository<NpdProject, Long> {

	NpdProject save(NpdProject project);

	Optional<NpdProject> findById(Long id);

	List<NpdProject> findAllByOrderByIdDesc();

	List<NpdProject> findByCustomerCompanyIdInOrderByIdDesc(Collection<Long> customerCompanyIds);

	/** Defense in depth: the DB's UNIQUE constraint is the real guarantee. */
	boolean existsBySourceRfqId(Long sourceRfqId);

}

interface NpdActivityRepository extends Repository<NpdActivity, Long> {

	NpdActivity save(NpdActivity activity);

	Optional<NpdActivity> findById(Long id);

	List<NpdActivity> findByNpdProjectIdOrderById(Long npdProjectId);

	/** The activities that gate Close Stage for one stage of one project. */
	List<NpdActivity> findByNpdProjectIdAndPhaseNameAndMandatoryFlagTrue(Long npdProjectId, NpdStage phaseName);

}

interface NpdStageHistoryRepository extends Repository<NpdStageHistoryEntry, Long> {

	NpdStageHistoryEntry save(NpdStageHistoryEntry entry);

	List<NpdStageHistoryEntry> findByNpdProjectIdOrderById(Long npdProjectId);

}
