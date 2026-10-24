package org.srx.monitor;

import static org.junit.Assert.*;
import org.junit.Test;

public class MonitorEventBufferTest {
  @Test
  public void bootEventsWaitInOrderUntilTransportAcceptsThem() {
    MonitorEventBuffer<Object> buffer = new MonitorEventBuffer<>(8);
    Object first = new Object(), second = new Object();
    buffer.offer(first);
    buffer.offer(second);
    assertSame(first, buffer.peek());
    // A disconnected/failed transport does not acknowledge an event.
    assertSame(first, buffer.peek());
    buffer.delivered(first);
    assertSame(second, buffer.peek());
    buffer.delivered(second);
    assertNull(buffer.peek());
    assertEquals(0, buffer.lost());
  }

  @Test
  public void memoryLimitReportsLossWithoutReplacingEarlierEvents() {
    MonitorEventBuffer<Integer> buffer = new MonitorEventBuffer<>(2);
    assertTrue(buffer.offer(1));
    assertTrue(buffer.offer(2));
    assertFalse(buffer.offer(3));
    assertEquals(1, buffer.lost());
    assertEquals(Integer.valueOf(1), buffer.peek());
  }

  @Test
  public void clearDuringDeliveryDoesNotRemoveANewerEvent() {
    MonitorEventBuffer<Object> buffer = new MonitorEventBuffer<>(4);
    Object old = new Object(), next = new Object();
    buffer.offer(old);
    buffer.clear();
    buffer.offer(next);
    buffer.delivered(old);
    assertSame(next, buffer.peek());
  }
}
