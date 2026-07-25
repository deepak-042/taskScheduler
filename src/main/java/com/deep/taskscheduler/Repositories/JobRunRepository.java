package com.deep.taskscheduler.Repositories;

import com.deep.taskscheduler.entities.JobRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface JobRunRepository extends JpaRepository<JobRun,Long> {
    List<JobRun> findTop50ByJobIdOrderByStartedAtDesc(Long jobId);
    List<JobRun> findTop100ByOrderByStartedAtDesc();
}
