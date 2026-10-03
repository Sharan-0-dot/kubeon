package com.sharan.kubeon;

import com.sharan.kubeon.kubernetes.ClusterConnectivityCheck;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
		"kubeon.watcher.enabled=false",
		"kubeon.connectivity-check.enabled=false"
})
class KubeonApplicationTests {

	@Autowired
	private ApplicationContext applicationContext;

	@Test
	void contextLoads() {
		assertThat(applicationContext).isNotNull();
		assertThat(applicationContext.getBeansOfType(ClusterConnectivityCheck.class)).isEmpty();
	}

}
