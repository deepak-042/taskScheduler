package com.deep.taskscheduler.Repositories;

import com.deep.taskscheduler.entities.ScheduledJob;
import com.deep.taskscheduler.entities.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ScheduledJobRepository extends JpaRepository<ScheduledJob,Long> {
    @Query("SELECT j FROM ScheduledJob j WHERE j.status = 'ACTIVE' AND j.nextRunAt <= :now")
    List<ScheduledJob> findDueJobs(@Param("now") LocalDateTime now);
}
