package vn.thanhtuanle;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class SubmissionServiceApplicationTests {

	@Autowired ThreadPoolTaskScheduler scheduler;

	@Test
	void contextLoads() {
	}

	@Test
	void theSchedulerHasThreeThreads() {
		assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(3);
	}

}
