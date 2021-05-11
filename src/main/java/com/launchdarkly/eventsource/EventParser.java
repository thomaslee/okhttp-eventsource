package com.launchdarkly.eventsource;

import java.net.URI;
import java.time.Duration;

/**
 * Adapted from https://github.com/aslakhellesoy/eventsource-java/blob/master/src/main/java/com/github/eventsource/client/impl/EventStreamParser.java
 */
public class EventParser {
  private static final String DATA = "data";
  private static final String ID = "id";
  private static final String EVENT = "event";
  private static final String RETRY = "retry";

  private static final String DEFAULT_EVENT = "message";

  private final EventHandler handler;
  private final ConnectionHandler connectionHandler;
  private final Logger logger;
  private final URI origin;

  private StringBuilder data = new StringBuilder();
  private String lastEventId;
  private String eventName = DEFAULT_EVENT;

  EventParser(URI origin, EventHandler handler, ConnectionHandler connectionHandler, Logger logger) {
    this.handler = handler;
    this.origin = origin;
    this.connectionHandler = connectionHandler;
    this.logger = logger;
  }

  /**
   * Accepts a single line of input and updates the parser state. If this completes a valid event,
   * the event is sent to the {@link EventHandler}.
   * @param line an input line
   */
  public void line(String line) {
    logger.debug("Parsing line: {}", line);
    int colonIndex;
    if (line.trim().isEmpty()) {
      dispatchEvent();
    } else if (line.startsWith(":")) {
      processComment(line.substring(1).trim());
    } else if ((colonIndex = line.indexOf(":")) != -1) {
      int fieldStart = 0;
      int fieldEnd = colonIndex;
      int valueStart = colonIndex + 1;
      int valueEnd = line.length();
      if (valueStart != valueEnd && line.charAt(valueStart) == ' ') {
        valueStart++;
      }
      processField(line, fieldStart, fieldEnd, valueStart, valueEnd);
    } else {
      String trimmed = line.trim();
      processField(trimmed, 0, trimmed.length(), 0, 0); // The spec doesn't say we need to trim the line, but I assume that's an oversight.
    }
  }

  private void processComment(String comment) {
    try {
      handler.onComment(comment);
    } catch (Exception e) {
      handler.onError(e);
    }
  }

  private void processField(String line, int fieldStart, final int fieldEnd, int valueStart, int valueEnd) {
    if (substrEquals(DATA, 0, DATA.length(), line, fieldStart, fieldEnd)) {
      data.append(line, valueStart, valueEnd).append("\n");
    } else if (substrEquals(ID, 0, ID.length(), line, fieldStart, fieldEnd)) {
      lastEventId = line.substring(valueStart, valueEnd);
    } else if (substrEquals(EVENT, 0, EVENT.length(), line, fieldStart, fieldEnd)) {
      eventName = line.substring(valueStart, valueEnd);
    } else {
      if (substrEquals(RETRY, 0, RETRY.length(), line, fieldStart, fieldEnd) && isNumber(line, valueStart, valueEnd)) {
        connectionHandler.setReconnectionTime(Duration.ofMillis(parseLong(line, valueStart, valueEnd)));
      }
    }
  }

  private static boolean substrEquals(String a, int aStart, int aEnd, String b, int bStart, int bEnd) {
    int aSize = aEnd - aStart;
    int bSize = bEnd - aStart;
    if (aSize != bSize) {
      return false;
    }
    for (int i = 0; i < aSize; i++) {
      if (a.charAt(aStart + i) != b.charAt(bStart + i)) {
        return false;
      }
    }
    return true;
  }

  private boolean isNumber(String value, int start, int end) {
    for (int i = start; i < end; i++) {
      if (!Character.isDigit(value.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private long parseLong(String value, int start, int end) {
    long total = 0;
    for (int i = start; i < end; i++) {
      total *= 10;
      total += Character.digit(value.charAt(i), 10);
    }
    return total;
  }

  private void dispatchEvent() {
    if (data.length() == 0) {
      return;
    }
    if (data.charAt(data.length()-1) == '\n') {
      data.setLength(data.length()-1);
    }
    String dataString = data.toString();
    MessageEvent message = new MessageEvent(dataString, lastEventId, origin);
    connectionHandler.setLastEventId(lastEventId);
    try {
      logger.debug("Dispatching message: \"{}\", {}", eventName, message);
      handler.onMessage(eventName, message);
    } catch (Exception e) {
      logger.warn("Message handler threw an exception: " + e.toString());
      logger.debug("Stack trace: {}", new LazyStackTrace(e));
      handler.onError(e);
    }
    data = new StringBuilder();
    eventName = DEFAULT_EVENT;
  }
}