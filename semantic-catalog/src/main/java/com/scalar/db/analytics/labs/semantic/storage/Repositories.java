/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic.storage;

import com.scalar.db.analytics.labs.semantic.storage.Rows.BuildTaskRow;
import com.scalar.db.analytics.labs.semantic.storage.Rows.CallRow;
import com.scalar.db.analytics.labs.semantic.storage.Rows.DomainRow;
import com.scalar.db.analytics.labs.semantic.storage.Rows.PresentationRow;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/** The Spring Data JDBC repositories of the records kept outside the log. */
public final class Repositories {
  private Repositories() {}

  public interface Domains extends CrudRepository<DomainRow, String> {}

  public interface BuildTasks extends CrudRepository<BuildTaskRow, String> {
    List<BuildTaskRow> findByDomainOrderBySeq(String domain);
  }

  public interface Presentations extends CrudRepository<PresentationRow, String> {
    List<PresentationRow> findByDomainOrderBySeq(String domain);
  }

  public interface Calls extends CrudRepository<CallRow, String> {
    @Modifying
    @Query("DELETE FROM semantic_catalog.call_records WHERE recorded_at < :before")
    void deleteRecordedBefore(@Param("before") Instant before);
  }
}
