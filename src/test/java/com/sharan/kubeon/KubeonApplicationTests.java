package com.sharan.kubeon;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "kubeon.watcher.enabled=false")
class KubeonApplicationTests {

	@Test
	void contextLoads() {
	}

}
