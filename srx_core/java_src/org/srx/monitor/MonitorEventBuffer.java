package org.srx.monitor;

import java.util.ArrayDeque;

/** Short connection gaps must not discard events or grow provider memory without a limit. */
public final class MonitorEventBuffer<T> {
  private final ArrayDeque<T> events = new ArrayDeque<>();
  private final int capacity;
  private long lost;

  public MonitorEventBuffer(int capacity) {
    this.capacity = capacity;
  }

  public synchronized boolean offer(T event) {
    if (events.size() == capacity) {
      lost++;
      return false;
    }
    events.addLast(event);
    return true;
  }

  public synchronized T peek() {
    return events.peekFirst();
  }

  public synchronized void delivered(T event) {
    if (events.peekFirst() == event)
      events.removeFirst();
  }

  public synchronized long lost() {
    return lost;
  }

  public synchronized void clear() {
    events.clear();
  }
}
