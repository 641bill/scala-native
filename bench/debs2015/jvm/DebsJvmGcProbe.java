import java.io.BufferedReader;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;

public final class DebsJvmGcProbe {
  private static final long WINDOW_SECONDS = 30L * 60L;

  private DebsJvmGcProbe() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 1 || args.length > 2) {
      System.err.println("usage: java DebsJvmGcProbe <joined-debs.csv> [churn|window]");
      System.exit(2);
    }

    Path input = Path.of(args[0]);
    String mode = args.length == 2 ? args[1] : "window";
    if (!mode.equals("churn") && !mode.equals("window")) {
      throw new IllegalArgumentException("unknown mode: " + mode);
    }

    GcStats gcStart = GcStats.capture();
    long started = System.nanoTime();
    Result result =
        mode.equals("window") ? runWindow(input) : runChurn(input);
    long elapsedNanos = System.nanoTime() - started;
    GcStats gcEnd = GcStats.capture();
    Runtime runtime = Runtime.getRuntime();
    long heapUsed = runtime.totalMemory() - runtime.freeMemory();

    System.out.printf(
        "DEBS_JVM_GC_RESULT mode=%s events=%d checksum=%d max_window=%d taxi_ids=%d "
            + "elapsed_ms=%.3f throughput_eps=%.3f gc_collections=%d gc_time_ms=%d "
            + "heap_used_bytes=%d%n",
        mode,
        result.events,
        result.checksum,
        result.maxWindow,
        result.taxiIds,
        elapsedNanos / 1_000_000.0,
        result.events * 1_000_000_000.0 / Math.max(1L, elapsedNanos),
        gcEnd.collections - gcStart.collections,
        gcEnd.millis - gcStart.millis,
        heapUsed);
  }

  private static Result runChurn(Path input) throws IOException {
    long events = 0L;
    long checksum = 0L;
    try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        TripObject trip = parseTrip(line);
        if (trip != null) {
          checksum += trip.checksum();
          events += 1L;
        }
      }
    }
    return new Result(events, checksum, 0, 0);
  }

  private static Result runWindow(Path input) throws IOException {
    ArrayDeque<TripObject> window = new ArrayDeque<>();
    HashMap<String, Integer> taxiIds = new HashMap<>();
    long events = 0L;
    long checksum = 0L;
    int maxWindow = 0;

    try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        TripObject trip = parseTrip(line);
        if (trip != null) {
          long cutoff = trip.dropoffSeconds - WINDOW_SECONDS;
          while (!window.isEmpty() && window.peekFirst().dropoffSeconds < cutoff) {
            TripObject evicted = window.removeFirst();
            checksum -= evicted.pickupCellKey;
          }

          window.addLast(trip);
          taxiIds.computeIfAbsent(trip.taxiId, ignored -> taxiIds.size());
          maxWindow = Math.max(maxWindow, window.size());
          checksum += trip.checksum() + window.size();
          events += 1L;
        }
      }
    }

    return new Result(events, checksum, maxWindow, taxiIds.size());
  }

  private static TripObject parseTrip(String line) {
    String[] fields = line.split(",", -1);
    if (fields.length < 17) return null;
    try {
      String taxiId = fields[0];
      String pickupTimestamp = fields[2];
      String dropoffTimestamp = fields[3];
      long pickupSeconds = parseTimestampSeconds(pickupTimestamp);
      long dropoffSeconds = parseTimestampSeconds(dropoffTimestamp);
      double pickupLongitude = parseDouble(fields[6]);
      double pickupLatitude = parseDouble(fields[7]);
      double dropoffLongitude = parseDouble(fields[8]);
      double dropoffLatitude = parseDouble(fields[9]);
      double fare = parseDouble(fields[11]);
      double tip = parseDouble(fields[14]);
      int pickupCellKey = cellKey(pickupLongitude, pickupLatitude);
      int dropoffCellKey = cellKey(dropoffLongitude, dropoffLatitude);
      return new TripObject(
          taxiId,
          pickupTimestamp,
          dropoffTimestamp,
          pickupSeconds,
          dropoffSeconds,
          pickupCellKey,
          dropoffCellKey,
          fare + tip);
    } catch (RuntimeException ex) {
      return null;
    }
  }

  private static double parseDouble(String value) {
    if (value.isEmpty()) return 0.0;
    return Double.parseDouble(value);
  }

  private static long parseTimestampSeconds(String value) {
    int day = twoDigits(value, 8);
    int hour = twoDigits(value, 11);
    int minute = twoDigits(value, 14);
    int second = twoDigits(value, 17);
    return (((long) day * 24L + hour) * 60L + minute) * 60L + second;
  }

  private static int twoDigits(String value, int offset) {
    return (value.charAt(offset) - '0') * 10 + (value.charAt(offset + 1) - '0');
  }

  private static int cellKey(double longitude, double latitude) {
    int east = (int) Math.floor((longitude + 75.0) * 100.0);
    int south = (int) Math.floor((latitude - 40.0) * 100.0);
    return (east << 10) ^ south;
  }

  private static final class TripObject {
    final String taxiId;
    final String pickupTimestamp;
    final String dropoffTimestamp;
    final long pickupSeconds;
    final long dropoffSeconds;
    final int pickupCellKey;
    final int dropoffCellKey;
    final double profit;

    TripObject(
        String taxiId,
        String pickupTimestamp,
        String dropoffTimestamp,
        long pickupSeconds,
        long dropoffSeconds,
        int pickupCellKey,
        int dropoffCellKey,
        double profit) {
      this.taxiId = taxiId;
      this.pickupTimestamp = pickupTimestamp;
      this.dropoffTimestamp = dropoffTimestamp;
      this.pickupSeconds = pickupSeconds;
      this.dropoffSeconds = dropoffSeconds;
      this.pickupCellKey = pickupCellKey;
      this.dropoffCellKey = dropoffCellKey;
      this.profit = profit;
    }

    long checksum() {
      return taxiId.length()
          + pickupTimestamp.length()
          + dropoffTimestamp.length()
          + pickupSeconds
          + dropoffSeconds
          + pickupCellKey
          + dropoffCellKey
          + (long) profit;
    }
  }

  private static final class Result {
    final long events;
    final long checksum;
    final int maxWindow;
    final int taxiIds;

    Result(long events, long checksum, int maxWindow, int taxiIds) {
      this.events = events;
      this.checksum = checksum;
      this.maxWindow = maxWindow;
      this.taxiIds = taxiIds;
    }
  }

  private static final class GcStats {
    final long collections;
    final long millis;

    GcStats(long collections, long millis) {
      this.collections = collections;
      this.millis = millis;
    }

    static GcStats capture() {
      long collections = 0L;
      long millis = 0L;
      List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
      for (GarbageCollectorMXBean bean : beans) {
        long count = bean.getCollectionCount();
        long time = bean.getCollectionTime();
        if (count > 0L) collections += count;
        if (time > 0L) millis += time;
      }
      return new GcStats(collections, millis);
    }
  }
}
