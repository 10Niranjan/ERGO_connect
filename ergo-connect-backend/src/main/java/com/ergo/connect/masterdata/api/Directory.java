package com.ergo.connect.masterdata.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.ergo.connect.common.security.CompanyType;

/**
 * What other modules may ask about companies and users. Implemented inside masterdata (whose repositories are
 * package-private). Callers apply their own record scope before showing anything.
 */
public interface Directory {

	record CompanyRef(long id, String code, String name, CompanyType type, boolean active) {
	}

	record UserRef(long id, String name, long companyId) {
	}

	Optional<CompanyRef> company(long id);

	Map<Long, CompanyRef> companies(Collection<Long> ids);

	Map<Long, UserRef> users(Collection<Long> ids);

	/** Customer accounts assigned to an ERGO Key Account Manager (SRS §13.2.5). */
	Set<Long> accountsOf(long userId);

}
