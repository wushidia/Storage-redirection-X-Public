package org.srx.monitor;

public final class MonitorRecord {
  public final long timestamp;
  public final int uid;
  public final int pid;
  public final String writer;
  public final String actor;
  public final String path;
  public final String operation;
  public final int status;
  public final int flags;

  public MonitorRecord(long timestamp, int uid, int pid, String writer, String actor, String path,
      String operation, int status, int flags) {
    this.timestamp = timestamp;
    this.uid = uid;
    this.pid = pid;
    this.writer = writer;
    this.actor = actor;
    this.path = path;
    this.operation = operation;
    this.status = status;
    this.flags = flags;
  }
}
