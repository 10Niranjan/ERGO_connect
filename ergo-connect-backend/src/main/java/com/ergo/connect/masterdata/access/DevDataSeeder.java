package com.ergo.connect.masterdata.access;

import static com.ergo.connect.common.security.CompanyType.CUSTOMER;
import static com.ergo.connect.common.security.CompanyType.ERGO;
import static com.ergo.connect.common.security.CompanyType.SUPPLIER;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ergo.connect.common.security.CompanyType;
import com.ergo.connect.common.security.Role;
import com.ergo.connect.masterdata.lookup.MasterDataService;
import com.ergo.connect.masterdata.lookup.MasterDataService.EntryRequest;
import com.ergo.connect.masterdata.lookup.MasterList;

/** Local demo data: two customers and two suppliers to see record isolation, roles and approvals. Dev profile only. */
@Component
@Profile("dev")
class DevDataSeeder implements ApplicationRunner {

	static final String PASSWORD = "ErgoDev#2026";

	private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

	private final CompanyRepository companies;

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	private final MasterDataService masterData;

	DevDataSeeder(CompanyRepository companies, UserRepository users, PasswordEncoder passwordEncoder,
			MasterDataService masterData) {
		this.companies = companies;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.masterData = masterData;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (companies.count() > 0) {
			return;
		}
		String hash = passwordEncoder.encode(PASSWORD);
		Company ergo = company("ERGO", "ERGO", ERGO);
		Company acme = company("ACME-AUTO", "Acme Automotive", CUSTOMER);
		Company bharat = company("BHARAT-MOTORS", "Bharat Motors", CUSTOMER);
		Company precision = company("PRECISION-CAST", "Precision Castings", SUPPLIER);
		Company shakti = company("SHAKTI-FORGE", "Shakti Forgings", SUPPLIER);
		user(ergo, "Asha", "Admin", "admin@ergo.dev", hash, Role.ERGO_ADMIN);
		user(ergo, "Mohan", "Management", "management@ergo.dev", hash, Role.ERGO_MANAGEMENT);
		user(ergo, "Ravi", "Rao", "rfq@ergo.dev", hash, Role.ERGO_RFQ, Role.ERGO_NPD); // two roles: role selection
		user(ergo, "Kiran", "Kumar", "quality@ergo.dev", hash, Role.ERGO_QUALITY);
		user(ergo, "Kavya", "Menon", "kam@ergo.dev", hash, Role.ERGO_KAM).accountIds.add(acme.id); // sees Acme only
		user(acme, "Alice", "Iyer", "buyer@acme.dev", hash, Role.CUSTOMER_RFQ, Role.CUSTOMER_QUALITY);
		user(bharat, "Bala", "Nair", "buyer@bharat.dev", hash, Role.CUSTOMER_RFQ);
		user(precision, "Prakash", "Shah", "sales@precision.dev", hash, Role.SUPPLIER_RFQ);
		user(shakti, "Sunil", "Patel", "sales@shakti.dev", hash, Role.SUPPLIER_RFQ);

		entry(MasterList.MATERIALS, "EN8", "EN8 carbon steel", Map.of("specification", "BS 970 080M40", "grade", "EN8"));
		entry(MasterList.MATERIALS, "SS304", "Stainless steel 304", Map.of("specification", "ASTM A240", "grade", "304"));
		entry(MasterList.MATERIALS, "AL6061", "Aluminium 6061", Map.of("grade", "6061-T6"));
		entry(MasterList.PROCESSES, "CNC-TURN", "CNC turning", Map.of("processType", "INHOUSE"));
		entry(MasterList.PROCESSES, "FORGING", "Hot forging", Map.of("processType", "OUTSOURCED"));
		entry(MasterList.PROCESSES, "HEAT-TREAT", "Heat treatment", Map.of("processType", "MIXED"));
		entry(MasterList.COATINGS, "ZN-PLATE", "Zinc plating", Map.of("specification", "Fe/Zn 8"));
		entry(MasterList.COATINGS, "POWDER", "Powder coating", Map.of());
		entry(MasterList.PAYMENT_TERMS, "NET30", "Net 30 days", Map.of("description", "Payment 30 days from invoice"));
		entry(MasterList.PAYMENT_TERMS, "ADV50", "50% advance, 50% before dispatch", Map.of());
		log.warn("Seeded dev demo data. Every demo user (e.g. admin@ergo.dev) has the password {}", PASSWORD);
	}

	private Company company(String code, String name, CompanyType type) {
		Company company = new Company();
		company.code = code;
		company.name = name;
		company.type = type;
		company.status = CompanyStatus.ACTIVE;
		company.country = "India";
		return companies.save(company);
	}

	private User user(Company company, String firstName, String lastName, String email, String hash, Role... roles) {
		User user = new User();
		user.companyId = company.id;
		user.firstName = firstName;
		user.lastName = lastName;
		user.email = email;
		user.passwordHash = hash;
		user.mustChangePassword = false;
		user.roles.addAll(List.of(roles));
		user.defaultRole = roles[0];
		return users.save(user);
	}

	private void entry(MasterList list, String code, String name, Map<String, String> fields) {
		masterData.create(list, new EntryRequest(code, name, true, fields));
	}

}
