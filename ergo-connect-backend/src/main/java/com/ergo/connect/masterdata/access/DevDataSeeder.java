package com.ergo.connect.masterdata.access;

import static com.ergo.connect.common.security.CompanyType.CUSTOMER;
import static com.ergo.connect.common.security.CompanyType.ERGO;
import static com.ergo.connect.common.security.CompanyType.SUPPLIER;

import java.util.List;

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

/** Local demo data: two customers and two suppliers to see record isolation and role switching. Dev profile only. */
@Component
@Profile("dev")
class DevDataSeeder implements ApplicationRunner {

	static final String PASSWORD = "ErgoDev#2026";

	private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

	private final CompanyRepository companies;

	private final UserRepository users;

	private final PasswordEncoder passwordEncoder;

	DevDataSeeder(CompanyRepository companies, UserRepository users, PasswordEncoder passwordEncoder) {
		this.companies = companies;
		this.users = users;
		this.passwordEncoder = passwordEncoder;
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
		user(acme, "Alice", "Iyer", "buyer@acme.dev", hash, Role.CUSTOMER_RFQ, Role.CUSTOMER_QUALITY);
		user(bharat, "Bala", "Nair", "buyer@bharat.dev", hash, Role.CUSTOMER_RFQ);
		user(precision, "Prakash", "Shah", "sales@precision.dev", hash, Role.SUPPLIER_RFQ);
		user(shakti, "Sunil", "Patel", "sales@shakti.dev", hash, Role.SUPPLIER_RFQ);
		log.warn("Seeded dev demo data. Every demo user (e.g. admin@ergo.dev) has the password {}", PASSWORD);
	}

	private Company company(String code, String name, CompanyType type) {
		Company company = new Company();
		company.code = code;
		company.name = name;
		company.type = type;
		company.country = "India";
		return companies.save(company);
	}

	private void user(Company company, String firstName, String lastName, String email, String hash, Role... roles) {
		User user = new User();
		user.companyId = company.id;
		user.firstName = firstName;
		user.lastName = lastName;
		user.email = email;
		user.passwordHash = hash;
		user.mustChangePassword = false;
		user.roles.addAll(List.of(roles));
		user.defaultRole = roles[0];
		users.save(user);
	}

}
