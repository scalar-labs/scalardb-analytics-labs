/*
 * Copyright Scalar, Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.scalar.db.analytics.labs.semantic;

import com.scalar.db.analytics.labs.semantic.model.LogEntry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One commit being built. Each appended record is applied to the state at once, so judgement later
 * in the same commit sees it; the records are written together or not at all.
 */
final class Commit {
  final long number;
  final String operation;
  final String principal;
  private final CatalogState state;
  private final List<LogRecord> records = new ArrayList<>();
  private final Set<String> facts = new LinkedHashSet<>();
  private final Set<String> addresses = new LinkedHashSet<>();

  Commit(CatalogState state, String operation, String principal) {
    this.state = state;
    this.number = state.commit() + 1;
    this.operation = operation;
    this.principal = principal;
  }

  /** The identifier the next appended record will have. */
  String nextId(int offset) {
    return "c" + number + "." + (records.size() + 1 + offset);
  }

  String append(LogEntry entry) {
    var ordinal = records.size() + 1;
    var id = "c" + number + "." + ordinal;
    state.apply(id, number, entry);
    records.add(new LogRecord(number, ordinal, entry));
    switch (entry) {
      case LogEntry.Accept accept -> track(accept.fact());
      case LogEntry.ProposeRetire proposal -> track(proposal.fact());
      case LogEntry.EvaluatePolicy evaluation -> track(evaluation.fact());
      case LogEntry.Answer answer -> track(answer.fact());
      case LogEntry.Confirm confirm -> track(confirm.fact());
      case LogEntry.Reject reject -> track(reject.fact());
      case LogEntry.Retire retire -> track(retire.fact());
      case LogEntry.Select select -> addresses.add(select.address());
      case LogEntry.Observe ignored -> {}
      case LogEntry.PolicyVersion ignored -> {}
    }
    return id;
  }

  private void track(String fact) {
    facts.add(fact);
    addresses.add(state.requireFact(fact).address);
  }

  boolean empty() {
    return records.isEmpty();
  }

  List<LogRecord> records() {
    return records;
  }

  Set<String> facts() {
    return facts;
  }

  Set<String> addresses() {
    return addresses;
  }

  /** Closes the commit in the state, deriving the selections it changed. */
  void close() {
    state.endCommit(number);
  }

  record LogRecord(long commit, int ordinal, LogEntry entry) {
    String id() {
      return "c" + commit + "." + ordinal;
    }
  }
}
