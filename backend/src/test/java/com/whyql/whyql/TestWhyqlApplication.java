package com.whyql.whyql;

import org.springframework.boot.SpringApplication;

public class TestWhyqlApplication {

	public static void main(String[] args) {
		SpringApplication.from(WhyqlApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
