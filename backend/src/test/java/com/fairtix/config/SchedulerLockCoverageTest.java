package com.fairtix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Every {@code @Scheduled} job carries a {@code @SchedulerLock} with its own name. A job
 * without one runs on every instance at once; two jobs sharing a name block each other.
 * The tests switch the lock advice off, so this check is what catches a new job added
 * without a lock. {@code MultiInstanceIT} covers the lock at runtime.
 */
class SchedulerLockCoverageTest {

  @Test
  void everyScheduledJobHasItsOwnLock() throws Exception {
    List<String> unlocked = new ArrayList<>();
    Set<String> names = new HashSet<>();
    List<String> duplicates = new ArrayList<>();
    List<String> badDurations = new ArrayList<>();
    int jobs = 0;

    for (Class<?> type : applicationClasses()) {
      for (Method method : type.getDeclaredMethods()) {
        if (!method.isAnnotationPresent(Scheduled.class)) {
          continue;
        }
        jobs++;
        String job = type.getSimpleName() + "." + method.getName();
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);
        if (lock == null) {
          unlocked.add(job);
          continue;
        }
        if (!names.add(lock.name())) {
          duplicates.add(lock.name());
        }
        if (isLiteral(lock.lockAtLeastFor()) && isLiteral(lock.lockAtMostFor())
            && Duration.parse(lock.lockAtLeastFor()).compareTo(Duration.parse(lock.lockAtMostFor())) > 0) {
          badDurations.add(job);
        }
      }
    }

    assertThat(jobs).as("@Scheduled jobs found").isGreaterThanOrEqualTo(12);
    assertThat(unlocked).as("@Scheduled jobs without @SchedulerLock").isEmpty();
    assertThat(duplicates).as("lock names used twice").isEmpty();
    assertThat(badDurations).as("lockAtLeastFor longer than lockAtMostFor").isEmpty();
  }

  private static boolean isLiteral(String duration) {
    return duration.startsWith("PT");
  }

  private static List<Class<?>> applicationClasses() throws ClassNotFoundException {
    ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter((reader, factory) -> true);
    List<Class<?>> classes = new ArrayList<>();
    for (BeanDefinition candidate : scanner.findCandidateComponents("com.fairtix")) {
      // Not initialised: static blocks (PostgresIT starts containers) must not run here.
      classes.add(Class.forName(candidate.getBeanClassName(), false,
          SchedulerLockCoverageTest.class.getClassLoader()));
    }
    return classes;
  }
}
