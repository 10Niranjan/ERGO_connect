package com.ergo.connect.documents.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@code ergo.storage.type} picks exactly one {@link DocumentStorage}; anything else leaves none, so the app fails to
 * start instead of guessing (Oracle production runs prod + postgres, ARCHITECTURE.md §8 2026-11-05).
 */
class DocumentStorageSelectionTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
		.withUserConfiguration(PostgresDocumentStorage.class);

	@Test
	void postgresSelectsThePostgresStoreEvenUnderProd() {
		runner.withPropertyValues("ergo.storage.type=postgres", "spring.profiles.active=prod")
			.run(ctx -> assertThat(ctx).hasSingleBean(DocumentStorage.class)
				.hasSingleBean(PostgresDocumentStorage.class));
	}

	@Test
	void azureOrAnyOtherValueLeavesNoPostgresStore() {
		for (String value : new String[] { "azure", "s3", "", "postgresql" }) {
			runner.withPropertyValues("ergo.storage.type=" + value)
				.run(ctx -> assertThat(ctx).doesNotHaveBean(PostgresDocumentStorage.class));
		}
		runner.run(ctx -> assertThat(ctx).doesNotHaveBean(DocumentStorage.class));
	}

	@Test
	void unknownValueSelectsNoStoreAtAll() {
		runner.withUserConfiguration(AzureBlobDocumentStorage.class)
			.withPropertyValues("ergo.storage.type=s3")
			.run(ctx -> assertThat(ctx).doesNotHaveBean(DocumentStorage.class));
	}

}
