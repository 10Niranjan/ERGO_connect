package com.ergo.connect;

import org.springframework.boot.SpringApplication;

public class TestErgoConnectApplication {

	public static void main(String[] args) {
		SpringApplication.from(ErgoConnectApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
