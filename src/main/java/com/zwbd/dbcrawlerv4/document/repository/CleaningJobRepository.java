package com.zwbd.dbcrawlerv4.document.repository;

import com.zwbd.dbcrawlerv4.document.entity.CleaningJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * @Author: wnli
 * @Date: 2025/12/9 10:54
 * @Desc:
 */
@Repository
public interface CleaningJobRepository extends JpaRepository<CleaningJob, Long> {}
