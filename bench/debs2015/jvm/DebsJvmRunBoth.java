import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

public final class DebsJvmRunBoth {
  private DebsJvmRunBoth() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      System.err.println("usage: java DebsJvmRunBoth <input.csv> <q1.out> <q2.out>");
      System.exit(2);
    }

    GcStats gcStart = GcStats.capture();
    long started = System.nanoTime();
    Metrics metrics = run(Path.of(args[0]).toString(), args[1], args[2]);
    long elapsedNanos = System.nanoTime() - started;
    GcStats gcEnd = GcStats.capture();
    Runtime runtime = Runtime.getRuntime();
    long heapUsed = runtime.totalMemory() - runtime.freeMemory();

    System.out.printf(
        "DEBS_JVM_RUNBOTH_RESULT events=%d parsed=%d invalid=%d q1_outputs=%d q2_outputs=%d "
            + "elapsed_ms=%.3f throughput_eps=%.3f "
            + "phase_read_ns=%d phase_parse_ns=%d phase_q1_process_ns=%d "
            + "phase_q1_output_ns=%d phase_q2_process_ns=%d phase_q2_output_ns=%d "
            + "phase_close_ns=%d gc_collections=%d gc_time_ms=%d heap_used_bytes=%d%n",
        metrics.events,
        metrics.parsed,
        metrics.invalid,
        metrics.q1Outputs,
        metrics.q2Outputs,
        elapsedNanos / 1_000_000.0,
        metrics.events * 1_000_000_000.0 / Math.max(1L, elapsedNanos),
        metrics.readNanos,
        metrics.parseNanos,
        metrics.q1ProcessNanos,
        metrics.q1OutputNanos,
        metrics.q2ProcessNanos,
        metrics.q2OutputNanos,
        metrics.closeNanos,
        gcEnd.collections - gcStart.collections,
        gcEnd.millis - gcStart.millis,
        heapUsed);
  }

  private static Metrics run(String inputPath, String q1OutputPath, String q2OutputPath)
      throws IOException {
    Q1Engine q1 = new Q1Engine();
    Q2Engine q2 = new Q2Engine();
    CsvLineReader source = new CsvLineReader(inputPath);
    BufferedWriter q1Writer = new BufferedWriter(new FileWriter(q1OutputPath));
    BufferedWriter q2Writer = new BufferedWriter(new FileWriter(q2OutputPath));
    Trip trip = new Trip();
    Metrics metrics = new Metrics();
    long[] previousQ1 = new long[0];
    Q2Snapshot previousQ2 = Q2Snapshot.EMPTY;

    try {
      while (true) {
        long readStarted = System.nanoTime();
        boolean hasNext = source.nextLine();
        metrics.readNanos += System.nanoTime() - readStarted;
        if (!hasNext) break;

        long readAt = System.nanoTime();
        metrics.events += 1L;

        long parseStarted = System.nanoTime();
        boolean parsed = trip.parseInto(source.bytes, source.lineStart, source.lineEnd);
        long parseFinished = System.nanoTime();
        metrics.parseNanos += parseFinished - parseStarted;

        if (parsed) {
          metrics.parsed += 1L;

          long q1Started = System.nanoTime();
          RankedRoute[] q1Current = q1.process(trip);
          long q1Finished = System.nanoTime();
          metrics.q1ProcessNanos += q1Finished - q1Started;
          if (q1Current.length != 0 && q1Changed(previousQ1, q1Current)) {
            long q1OutputStarted = System.nanoTime();
            long delayMillis = (q1OutputStarted - readAt) / 1_000_000L;
            writeQ1Row(q1Writer, trip, q1Current, delayMillis);
            q1Writer.newLine();
            previousQ1 = q1Snapshot(q1Current);
            metrics.q1Outputs += 1L;
            metrics.q1OutputNanos += System.nanoTime() - q1OutputStarted;
          }

          long q2Started = System.nanoTime();
          ProfitableArea[] q2Current = q2.process(trip);
          long q2Finished = System.nanoTime();
          metrics.q2ProcessNanos += q2Finished - q2Started;
          if (q2Current.length != 0 && q2Changed(previousQ2, q2Current)) {
            long q2OutputStarted = System.nanoTime();
            long delayMillis = (q2OutputStarted - readAt) / 1_000_000L;
            writeQ2Row(q2Writer, trip, q2Current, delayMillis);
            q2Writer.newLine();
            previousQ2 = q2Snapshot(q2Current);
            metrics.q2Outputs += 1L;
            metrics.q2OutputNanos += System.nanoTime() - q2OutputStarted;
          }
        } else {
          metrics.invalid += 1L;
        }
      }
    } finally {
      long closeStarted = System.nanoTime();
      q2Writer.close();
      q1Writer.close();
      source.close();
      metrics.closeNanos = System.nanoTime() - closeStarted;
    }

    return metrics;
  }

  private static boolean q1Changed(long[] previous, RankedRoute[] current) {
    if (previous.length != current.length) return true;
    for (int i = 0; i < current.length; i++) {
      if (previous[i] != current[i].routeKey) return true;
    }
    return false;
  }

  private static long[] q1Snapshot(RankedRoute[] ranking) {
    long[] result = new long[ranking.length];
    for (int i = 0; i < ranking.length; i++) result[i] = ranking[i].routeKey;
    return result;
  }

  private static boolean q2Changed(Q2Snapshot previous, ProfitableArea[] current) {
    if (previous.cellKeys.length != current.length) return true;
    for (int i = 0; i < current.length; i++) {
      ProfitableArea area = current[i];
      if (previous.cellKeys[i] != area.cellKey
          || previous.emptyTaxis[i] != area.emptyTaxis
          || previous.medianProfits[i] != area.medianProfit
          || previous.profitabilities[i] != area.profitability) {
        return true;
      }
    }
    return false;
  }

  private static Q2Snapshot q2Snapshot(ProfitableArea[] ranking) {
    int[] cellKeys = new int[ranking.length];
    int[] emptyTaxis = new int[ranking.length];
    double[] medianProfits = new double[ranking.length];
    double[] profitabilities = new double[ranking.length];
    for (int i = 0; i < ranking.length; i++) {
      ProfitableArea area = ranking[i];
      cellKeys[i] = area.cellKey;
      emptyTaxis[i] = area.emptyTaxis;
      medianProfits[i] = area.medianProfit;
      profitabilities[i] = area.profitability;
    }
    return new Q2Snapshot(cellKeys, emptyTaxis, medianProfits, profitabilities);
  }

  private static void writeQ1Row(
      Writer writer, Trip trip, RankedRoute[] ranking, long delayMillis) throws IOException {
    trip.writePickupTimestamp(writer);
    writeComma(writer);
    trip.writeDropoffTimestamp(writer);
    for (int i = 0; i < 10; i++) {
      writeComma(writer);
      if (i < ranking.length) {
        long key = ranking[i].routeKey;
        writeCellId(writer, (int) ((key >>> 30) & ROUTE_PART_MASK), (int) ((key >>> 20) & ROUTE_PART_MASK));
        writeComma(writer);
        writeCellId(writer, (int) ((key >>> 10) & ROUTE_PART_MASK), (int) (key & ROUTE_PART_MASK));
      } else {
        writer.write("NULL,NULL");
      }
    }
    writeComma(writer);
    writeLong(writer, delayMillis);
  }

  private static void writeQ2Row(
      Writer writer, Trip trip, ProfitableArea[] ranking, long delayMillis) throws IOException {
    trip.writePickupTimestamp(writer);
    writeComma(writer);
    trip.writeDropoffTimestamp(writer);
    for (int i = 0; i < 10; i++) {
      writeComma(writer);
      if (i < ranking.length) {
        ProfitableArea area = ranking[i];
        writeCellId(writer, area.cellKey >>> CELL_PART_BITS, area.cellKey & CELL_PART_MASK);
        writeComma(writer);
        writeLong(writer, area.emptyTaxis);
        writeComma(writer);
        writeFixed(writer, area.medianProfit, 2);
        writeComma(writer);
        writeFixed(writer, area.profitability, 6);
      } else {
        writer.write("NULL,NULL,NULL,NULL");
      }
    }
    writeComma(writer);
    writeLong(writer, delayMillis);
  }

  private static final class Q1Engine {
    private static final long WINDOW_SECONDS = 30L * 60L;
    private final ArrayDeque<Q1Bucket> buckets = new ArrayDeque<>();
    private final RouteCounter routes = new RouteCounter();
    private Q1Bucket currentBucket;
    private long nextSeq;

    RankedRoute[] process(Trip trip) {
      evictBefore(trip.dropoffSeconds - WINDOW_SECONDS);
      int startKey = q1CellKeyOrZero(trip.pickupLongitude, trip.pickupLatitude);
      if (startKey != 0) {
        int endKey = q1CellKeyOrZero(trip.dropoffLongitude, trip.dropoffLatitude);
        if (endKey != 0) {
          long key = routeKey(startKey, endKey);
          long seq = nextSeq++;
          Q1Bucket bucket = bucketFor(trip.dropoffSeconds);
          bucket.head = new Q1Entry(key, bucket.head);
          routes.increment(key, trip.dropoffSeconds, seq);
        }
      }
      return routes.top10();
    }

    private Q1Bucket bucketFor(long dropoffSeconds) {
      if (currentBucket != null && currentBucket.startSeconds == dropoffSeconds) return currentBucket;
      Q1Bucket bucket = new Q1Bucket(dropoffSeconds);
      buckets.addLast(bucket);
      currentBucket = bucket;
      return bucket;
    }

    private void evictBefore(long cutoffSeconds) {
      while (!buckets.isEmpty() && buckets.peekFirst().startSeconds < cutoffSeconds) {
        Q1Bucket bucket = buckets.removeFirst();
        for (Q1Entry entry = bucket.head; entry != null; entry = entry.next) {
          routes.decrement(entry.routeKey);
        }
        if (currentBucket == bucket) currentBucket = null;
      }
    }
  }

  private static final class RouteCounter {
    private long[] keys = new long[1024];
    private int[] counts = new int[1024];
    private long[] latestSeconds = new long[1024];
    private long[] latestSeq = new long[1024];
    private RankedRoute[] rankBySlot = new RankedRoute[1024];
    private int[] rankIndexBySlot = new int[1024];
    private RankedRoute[] heapRanks = new RankedRoute[1024];
    private int[] heapSlots = new int[1024];
    private final int[] topCandidateHeap = new int[24];
    private final RankedRoute[][] resultArrays = new RankedRoute[11][];
    private int activeSize;
    private int usedSize;
    private int heapSize;

    void increment(long key, long seconds, long seq) {
      int slot = insertSlot(key);
      if (keys[slot] == key) {
        counts[slot] += 1;
        latestSeconds[slot] = seconds;
        latestSeq[slot] = seq;
        updateRank(slot);
      } else {
        if (keys[slot] == EMPTY_ROUTE_KEY) usedSize += 1;
        keys[slot] = key;
        counts[slot] = 1;
        latestSeconds[slot] = seconds;
        latestSeq[slot] = seq;
        activeSize += 1;
        updateRank(slot);
      }
    }

    void decrement(long key) {
      int slot = existingSlot(key);
      if (slot >= 0) {
        RankedRoute ranked = rankBySlot[slot];
        int nextCount = counts[slot] - 1;
        if (nextCount <= 0) {
          if (ranked != null) removeRankHeap(slot);
          deleteSlot(slot);
        } else {
          counts[slot] = nextCount;
          if (ranked != null) {
            ranked.count = nextCount;
            fixRankHeap(slot);
          }
        }
      }
    }

    RankedRoute[] top10() {
      int size = Math.min(10, heapSize);
      RankedRoute[] result = resultArray(size);
      if (size == 0) return result;
      int candidateCount = 1;
      topCandidateHeap[0] = 0;
      for (int i = 0; i < size; i++) {
        int candidateSlot = bestCandidate(candidateCount);
        int heapPosition = topCandidateHeap[candidateSlot];
        candidateCount -= 1;
        topCandidateHeap[candidateSlot] = topCandidateHeap[candidateCount];
        result[i] = heapRanks[heapPosition];
        int left = (heapPosition << 1) + 1;
        if (left < heapSize) topCandidateHeap[candidateCount++] = left;
        int right = left + 1;
        if (right < heapSize) topCandidateHeap[candidateCount++] = right;
      }
      return result;
    }

    private RankedRoute[] resultArray(int size) {
      if (size == 0) return EMPTY_RANKED_ROUTES;
      RankedRoute[] result = resultArrays[size];
      if (result == null) {
        result = new RankedRoute[size];
        resultArrays[size] = result;
      }
      return result;
    }

    private void updateRank(int slot) {
      RankedRoute existing = rankBySlot[slot];
      if (existing != null) {
        existing.count = counts[slot];
        existing.latestSeconds = latestSeconds[slot];
        existing.latestSeq = latestSeq[slot];
        fixRankHeap(slot);
      } else {
        RankedRoute created = new RankedRoute(keys[slot], counts[slot], latestSeconds[slot], latestSeq[slot]);
        rankBySlot[slot] = created;
        addRankHeap(slot, created);
      }
    }

    private int insertSlot(long key) {
      if ((usedSize + 1) * 4 >= keys.length * 3) {
        if (activeSize * 2 < usedSize) rehash(keys.length);
        else rehash(keys.length << 1);
      }
      int mask = keys.length - 1;
      int slot = hash(key) & mask;
      int firstDeleted = -1;
      while (true) {
        long current = keys[slot];
        if (current == key) return slot;
        if (current == EMPTY_ROUTE_KEY) return firstDeleted >= 0 ? firstDeleted : slot;
        if (current == DELETED_ROUTE_KEY && firstDeleted < 0) firstDeleted = slot;
        slot = (slot + 1) & mask;
      }
    }

    private int existingSlot(long key) {
      int mask = keys.length - 1;
      int slot = hash(key) & mask;
      while (true) {
        long current = keys[slot];
        if (current == key) return slot;
        if (current == EMPTY_ROUTE_KEY) return -1;
        slot = (slot + 1) & mask;
      }
    }

    private void rehash(int newCapacity) {
      long[] oldKeys = keys;
      int[] oldCounts = counts;
      long[] oldLatestSeconds = latestSeconds;
      long[] oldLatestSeq = latestSeq;
      RankedRoute[] oldRanks = rankBySlot;
      int[] oldRankIndexes = rankIndexBySlot;
      keys = new long[newCapacity];
      counts = new int[newCapacity];
      latestSeconds = new long[newCapacity];
      latestSeq = new long[newCapacity];
      rankBySlot = new RankedRoute[newCapacity];
      rankIndexBySlot = new int[newCapacity];
      activeSize = 0;
      usedSize = 0;
      for (int i = 0; i < oldKeys.length; i++) {
        long key = oldKeys[i];
        if (key != EMPTY_ROUTE_KEY && key != DELETED_ROUTE_KEY) {
          int slot = insertSlotWithoutRehash(key);
          keys[slot] = key;
          counts[slot] = oldCounts[i];
          latestSeconds[slot] = oldLatestSeconds[i];
          latestSeq[slot] = oldLatestSeq[i];
          rankBySlot[slot] = oldRanks[i];
          int rankIndex = oldRankIndexes[i];
          if (rankIndex != 0) {
            rankIndexBySlot[slot] = rankIndex;
            heapSlots[rankIndex - 1] = slot;
          }
          activeSize += 1;
          usedSize += 1;
        }
      }
    }

    private int insertSlotWithoutRehash(long key) {
      int mask = keys.length - 1;
      int slot = hash(key) & mask;
      while (keys[slot] != EMPTY_ROUTE_KEY) slot = (slot + 1) & mask;
      return slot;
    }

    private void deleteSlot(int slot) {
      keys[slot] = DELETED_ROUTE_KEY;
      counts[slot] = 0;
      latestSeconds[slot] = 0L;
      latestSeq[slot] = 0L;
      rankBySlot[slot] = null;
      rankIndexBySlot[slot] = 0;
      activeSize -= 1;
    }

    private void addRankHeap(int slot, RankedRoute ranked) {
      ensureRankCapacity(heapSize + 1);
      int index = heapSize++;
      heapRanks[index] = ranked;
      heapSlots[index] = slot;
      rankIndexBySlot[slot] = index + 1;
      siftRankUp(index);
    }

    private void removeRankHeap(int slot) {
      int index = rankHeapIndex(slot);
      if (index < 0) return;
      int last = heapSize - 1;
      rankIndexBySlot[slot] = 0;
      if (index != last) {
        heapRanks[index] = heapRanks[last];
        heapSlots[index] = heapSlots[last];
        rankIndexBySlot[heapSlots[index]] = index + 1;
      }
      heapRanks[last] = null;
      heapSlots[last] = 0;
      heapSize = last;
      if (index < heapSize) fixRankHeapAt(index);
    }

    private void fixRankHeap(int slot) {
      int index = rankHeapIndex(slot);
      if (index >= 0) fixRankHeapAt(index);
    }

    private void fixRankHeapAt(int index) {
      int moved = siftRankUp(index);
      siftRankDown(moved);
    }

    private int siftRankUp(int start) {
      int child = start;
      while (child > 0) {
        int parent = (child - 1) >>> 1;
        if (!betterHeapIndex(child, parent)) return child;
        swapRankHeap(child, parent);
        child = parent;
      }
      return child;
    }

    private void siftRankDown(int start) {
      int parent = start;
      while (true) {
        int left = (parent << 1) + 1;
        if (left >= heapSize) return;
        int right = left + 1;
        int best = left;
        if (right < heapSize && betterHeapIndex(right, left)) best = right;
        if (!betterHeapIndex(best, parent)) return;
        swapRankHeap(parent, best);
        parent = best;
      }
    }

    private void swapRankHeap(int left, int right) {
      RankedRoute leftRank = heapRanks[left];
      int leftSlot = heapSlots[left];
      heapRanks[left] = heapRanks[right];
      heapSlots[left] = heapSlots[right];
      heapRanks[right] = leftRank;
      heapSlots[right] = leftSlot;
      rankIndexBySlot[heapSlots[left]] = left + 1;
      rankIndexBySlot[heapSlots[right]] = right + 1;
    }

    private int bestCandidate(int candidateCount) {
      int best = 0;
      for (int i = 1; i < candidateCount; i++) {
        if (betterHeapIndex(topCandidateHeap[i], topCandidateHeap[best])) best = i;
      }
      return best;
    }

    private boolean betterHeapIndex(int leftIndex, int rightIndex) {
      RankedRoute left = heapRanks[leftIndex];
      RankedRoute right = heapRanks[rightIndex];
      if (left == right) return false;
      if (left.count != right.count) return left.count > right.count;
      if (left.latestSeconds != right.latestSeconds) return left.latestSeconds > right.latestSeconds;
      if (left.latestSeq != right.latestSeq) return left.latestSeq > right.latestSeq;
      return compareRouteKeysById(keys[heapSlots[leftIndex]], keys[heapSlots[rightIndex]]) < 0;
    }

    private int rankHeapIndex(int slot) {
      return rankIndexBySlot[slot] - 1;
    }

    private void ensureRankCapacity(int required) {
      if (required > heapRanks.length) {
        int capacity = heapRanks.length;
        while (required > capacity) capacity *= 2;
        heapRanks = Arrays.copyOf(heapRanks, capacity);
        heapSlots = Arrays.copyOf(heapSlots, capacity);
      }
    }
  }

  private static final class Q2Engine {
    private final ArrayDeque<ProfitBucket> profitBuckets = new ArrayDeque<>();
    private final ArrayDeque<EmptyBucket> emptyBuckets = new ArrayDeque<>();
    private final TaxiIds taxiIds = new TaxiIds();
    private final ProfitStats[] profitStatsByCell = new ProfitStats[CELL_KEY_CAPACITY];
    private final int[] emptyCounts = new int[CELL_KEY_CAPACITY];
    private final long[] latestByCell = new long[CELL_KEY_CAPACITY];
    private final ProfitableArea[] rankByCell = new ProfitableArea[CELL_KEY_CAPACITY];
    private final int[] heapIndexByCell = new int[CELL_KEY_CAPACITY];
    private ProfitableArea[] heapAreas = new ProfitableArea[1024];
    private int[] heapCellKeys = new int[1024];
    private final int[] topCandidateHeap = new int[24];
    private EmptyEntry[] latestEmptyByTaxi = new EmptyEntry[4096];
    private final ProfitableArea[][] resultArrays = new ProfitableArea[11][];
    private double[] medianScratch;
    private ProfitBucket currentProfitBucket;
    private EmptyBucket currentEmptyBucket;
    private long nextSeq;
    private int heapSize;

    ProfitableArea[] process(Trip trip) {
      evictProfitBefore(trip.dropoffSeconds - PROFIT_WINDOW_SECONDS);
      evictEmptyBefore(trip.dropoffSeconds - EMPTY_WINDOW_SECONDS);
      long seq = nextSeq++;
      int taxiKey = taxiIds.idFor(trip);
      EmptyEntry previousEmpty = removeLatestEmpty(taxiKey);
      if (previousEmpty != null) removeEmpty(previousEmpty);

      if (trip.fare >= 0.0 && trip.tip >= 0.0) {
        int pickupKey = q2CellKeyOrZero(trip.pickupLongitude, trip.pickupLatitude);
        if (pickupKey != 0) {
          ProfitBucket bucket = profitBucketFor(trip.dropoffSeconds);
          ProfitEntry entry = new ProfitEntry(pickupKey, trip.fare + trip.tip, bucket.head);
          bucket.head = entry;
          profitStatsOrCreate(pickupKey).add(entry);
          latestByCell[pickupKey] = seq;
          updateRank(pickupKey);
        }
      }

      int dropoffKey = q2CellKeyOrZero(trip.dropoffLongitude, trip.dropoffLatitude);
      if (dropoffKey != 0) {
        EmptyBucket bucket = emptyBucketFor(trip.dropoffSeconds);
        EmptyEntry entry = new EmptyEntry(seq, taxiKey, dropoffKey, bucket.head);
        bucket.head = entry;
        updateLatestEmpty(taxiKey, entry);
        emptyCounts[dropoffKey] += 1;
        latestByCell[dropoffKey] = seq;
        updateRank(dropoffKey);
      }
      return top10();
    }

    private void evictProfitBefore(long cutoffSeconds) {
      while (!profitBuckets.isEmpty() && profitBuckets.peekFirst().startSeconds < cutoffSeconds) {
        ProfitBucket bucket = profitBuckets.removeFirst();
        ProfitEntry expired = bucket.head;
        while (expired != null) {
          ProfitEntry next = expired.bucketNext;
          ProfitStats stats = profitStatsByCell[expired.cellKey];
          if (stats != null) {
            stats.remove(expired);
            if (stats.isEmpty()) profitStatsByCell[expired.cellKey] = null;
            updateRank(expired.cellKey);
          }
          expired = next;
        }
        if (currentProfitBucket == bucket) currentProfitBucket = null;
      }
    }

    private void evictEmptyBefore(long cutoffSeconds) {
      while (!emptyBuckets.isEmpty() && emptyBuckets.peekFirst().startSeconds < cutoffSeconds) {
        EmptyBucket bucket = emptyBuckets.removeFirst();
        EmptyEntry expired = bucket.head;
        while (expired != null) {
          EmptyEntry latest = latestEmpty(expired.taxiKey);
          if (latest != null && latest.seq == expired.seq) {
            clearLatestEmpty(expired.taxiKey);
            removeEmpty(expired);
          }
          expired = expired.next;
        }
        if (currentEmptyBucket == bucket) currentEmptyBucket = null;
      }
    }

    private void removeEmpty(EmptyEntry entry) {
      int previous = emptyCounts[entry.cellKey];
      emptyCounts[entry.cellKey] = previous <= 1 ? 0 : previous - 1;
      updateRank(entry.cellKey);
    }

    private void updateRank(int cellKey) {
      ProfitableArea existing = rankByCell[cellKey];
      ProfitStats profits = profitStatsByCell[cellKey];
      if (profits != null) {
        int empty = emptyCounts[cellKey];
        if (empty > 0 && !profits.isEmpty()) {
          double median =
              profits.needsMedianScratch()
                  ? profits.medianProfitWithScratch(ensureMedianScratch(profits.medianScratchSize()))
                  : profits.cachedMedianProfit();
          if (existing != null) {
            existing.emptyTaxis = empty;
            existing.medianProfit = median;
            existing.profitability = median / (double) empty;
            existing.latestSeq = latestByCell[cellKey];
            fixRankHeap(cellKey);
          } else {
            ProfitableArea created =
                new ProfitableArea(cellKey, empty, median, median / (double) empty, latestByCell[cellKey]);
            rankByCell[cellKey] = created;
            addRankHeap(cellKey, created);
          }
        } else {
          clearRank(cellKey);
        }
      } else {
        clearRank(cellKey);
      }
    }

    private ProfitableArea[] top10() {
      int size = Math.min(10, heapSize);
      ProfitableArea[] ranked = resultArray(size);
      if (size == 0) return ranked;
      int candidateCount = 1;
      topCandidateHeap[0] = 0;
      for (int i = 0; i < size; i++) {
        int candidateSlot = bestCandidate(candidateCount);
        int heapPosition = topCandidateHeap[candidateSlot];
        candidateCount -= 1;
        topCandidateHeap[candidateSlot] = topCandidateHeap[candidateCount];
        ranked[i] = heapAreas[heapPosition];
        int left = (heapPosition << 1) + 1;
        if (left < heapSize) topCandidateHeap[candidateCount++] = left;
        int right = left + 1;
        if (right < heapSize) topCandidateHeap[candidateCount++] = right;
      }
      return ranked;
    }

    private ProfitBucket profitBucketFor(long dropoffSeconds) {
      if (currentProfitBucket != null && currentProfitBucket.startSeconds == dropoffSeconds) return currentProfitBucket;
      ProfitBucket bucket = new ProfitBucket(dropoffSeconds);
      profitBuckets.addLast(bucket);
      currentProfitBucket = bucket;
      return bucket;
    }

    private EmptyBucket emptyBucketFor(long dropoffSeconds) {
      if (currentEmptyBucket != null && currentEmptyBucket.startSeconds == dropoffSeconds) return currentEmptyBucket;
      EmptyBucket bucket = new EmptyBucket(dropoffSeconds);
      emptyBuckets.addLast(bucket);
      currentEmptyBucket = bucket;
      return bucket;
    }

    private double[] ensureMedianScratch(int count) {
      if (medianScratch == null || medianScratch.length < count) {
        int capacity = 16;
        while (capacity < count) capacity *= 2;
        medianScratch = new double[capacity];
      }
      return medianScratch;
    }

    private ProfitStats profitStatsOrCreate(int cellKey) {
      ProfitStats stats = profitStatsByCell[cellKey];
      if (stats == null) {
        stats = new ProfitStats();
        profitStatsByCell[cellKey] = stats;
      }
      return stats;
    }

    private EmptyEntry latestEmpty(int taxiKey) {
      return taxiKey < latestEmptyByTaxi.length ? latestEmptyByTaxi[taxiKey] : null;
    }

    private void updateLatestEmpty(int taxiKey, EmptyEntry entry) {
      ensureTaxiCapacity(taxiKey);
      latestEmptyByTaxi[taxiKey] = entry;
    }

    private EmptyEntry removeLatestEmpty(int taxiKey) {
      if (taxiKey < latestEmptyByTaxi.length) {
        EmptyEntry entry = latestEmptyByTaxi[taxiKey];
        latestEmptyByTaxi[taxiKey] = null;
        return entry;
      }
      return null;
    }

    private void clearLatestEmpty(int taxiKey) {
      if (taxiKey < latestEmptyByTaxi.length) latestEmptyByTaxi[taxiKey] = null;
    }

    private void ensureTaxiCapacity(int taxiKey) {
      if (taxiKey >= latestEmptyByTaxi.length) {
        int capacity = latestEmptyByTaxi.length;
        while (taxiKey >= capacity) capacity *= 2;
        latestEmptyByTaxi = Arrays.copyOf(latestEmptyByTaxi, capacity);
      }
    }

    private ProfitableArea[] resultArray(int size) {
      if (size == 0) return EMPTY_PROFITABLE_AREAS;
      ProfitableArea[] result = resultArrays[size];
      if (result == null) {
        result = new ProfitableArea[size];
        resultArrays[size] = result;
      }
      return result;
    }

    private void clearRank(int cellKey) {
      removeRankHeap(cellKey);
      rankByCell[cellKey] = null;
    }

    private void addRankHeap(int cellKey, ProfitableArea area) {
      ensureRankCapacity(heapSize + 1);
      int index = heapSize++;
      heapAreas[index] = area;
      heapCellKeys[index] = cellKey;
      heapIndexByCell[cellKey] = index + 1;
      siftRankUp(index);
    }

    private void removeRankHeap(int cellKey) {
      int index = rankHeapIndex(cellKey);
      if (index < 0) return;
      int last = heapSize - 1;
      heapIndexByCell[cellKey] = 0;
      if (index != last) {
        heapAreas[index] = heapAreas[last];
        heapCellKeys[index] = heapCellKeys[last];
        heapIndexByCell[heapCellKeys[index]] = index + 1;
      }
      heapAreas[last] = null;
      heapCellKeys[last] = 0;
      heapSize = last;
      if (index < heapSize) fixRankHeapAt(index);
    }

    private void fixRankHeap(int cellKey) {
      int index = rankHeapIndex(cellKey);
      if (index >= 0) fixRankHeapAt(index);
    }

    private void fixRankHeapAt(int index) {
      if (index > 0 && betterHeapIndex(index, (index - 1) >>> 1)) siftRankUp(index);
      else siftRankDown(index);
    }

    private void siftRankUp(int start) {
      int child = start;
      while (child > 0) {
        int parent = (child - 1) >>> 1;
        if (!betterHeapIndex(child, parent)) return;
        swapRankHeap(child, parent);
        child = parent;
      }
    }

    private void siftRankDown(int start) {
      int parent = start;
      while (true) {
        int left = (parent << 1) + 1;
        if (left >= heapSize) return;
        int right = left + 1;
        int best = left;
        if (right < heapSize && betterHeapIndex(right, left)) best = right;
        if (!betterHeapIndex(best, parent)) return;
        swapRankHeap(parent, best);
        parent = best;
      }
    }

    private void swapRankHeap(int left, int right) {
      ProfitableArea leftArea = heapAreas[left];
      int leftCellKey = heapCellKeys[left];
      heapAreas[left] = heapAreas[right];
      heapCellKeys[left] = heapCellKeys[right];
      heapAreas[right] = leftArea;
      heapCellKeys[right] = leftCellKey;
      heapIndexByCell[heapCellKeys[left]] = left + 1;
      heapIndexByCell[heapCellKeys[right]] = right + 1;
    }

    private int bestCandidate(int candidateCount) {
      int best = 0;
      for (int i = 1; i < candidateCount; i++) {
        if (betterHeapIndex(topCandidateHeap[i], topCandidateHeap[best])) best = i;
      }
      return best;
    }

    private boolean betterHeapIndex(int leftIndex, int rightIndex) {
      return compareAreas(heapAreas[leftIndex], heapAreas[rightIndex]) < 0;
    }

    private int rankHeapIndex(int cellKey) {
      return heapIndexByCell[cellKey] - 1;
    }

    private void ensureRankCapacity(int required) {
      if (required > heapAreas.length) {
        int capacity = heapAreas.length;
        while (required > capacity) capacity *= 2;
        heapAreas = Arrays.copyOf(heapAreas, capacity);
        heapCellKeys = Arrays.copyOf(heapCellKeys, capacity);
      }
    }
  }

  private static final class TaxiIds {
    private TaxiIdEntry[] buckets = new TaxiIdEntry[4096];
    private int nextId;

    int idFor(Trip trip) {
      int hash = trip.taxiIdHash();
      int bucket = hash & (buckets.length - 1);
      TaxiIdEntry entry = buckets[bucket];
      while (entry != null) {
        if (entry.hash == hash && trip.taxiIdEquals(entry.taxiId)) return entry.id;
        entry = entry.next;
      }
      if ((nextId + 1) * 4 >= buckets.length * 3) {
        grow();
        bucket = hash & (buckets.length - 1);
      }
      int id = nextId++;
      byte[] taxiId = trip.copyTaxiId();
      buckets[bucket] = new TaxiIdEntry(hash, taxiId, id, buckets[bucket]);
      return id;
    }

    private void grow() {
      TaxiIdEntry[] old = buckets;
      buckets = new TaxiIdEntry[old.length << 1];
      for (TaxiIdEntry head : old) {
        TaxiIdEntry entry = head;
        while (entry != null) {
          TaxiIdEntry next = entry.next;
          int bucket = entry.hash & (buckets.length - 1);
          entry.next = buckets[bucket];
          buckets[bucket] = entry;
          entry = next;
        }
      }
    }
  }

  private static final class ProfitStats {
    private ProfitEntry head;
    private int count;
    private boolean dirty = true;
    private double cachedMedian;

    boolean isEmpty() {
      return count == 0;
    }

    void add(ProfitEntry entry) {
      entry.nextInCell = head;
      entry.previousInCell = null;
      if (head != null) head.previousInCell = entry;
      head = entry;
      count += 1;
      dirty = true;
    }

    void remove(ProfitEntry entry) {
      ProfitEntry previous = entry.previousInCell;
      ProfitEntry next = entry.nextInCell;
      if (previous == null) head = next;
      else previous.nextInCell = next;
      if (next != null) next.previousInCell = previous;
      entry.previousInCell = null;
      entry.nextInCell = null;
      count -= 1;
      dirty = true;
    }

    boolean needsMedianScratch() {
      return dirty;
    }

    int medianScratchSize() {
      return count;
    }

    double cachedMedianProfit() {
      return cachedMedian;
    }

    double medianProfitWithScratch(double[] sorted) {
      if (dirty) computeMedian(sorted);
      return cachedMedian;
    }

    private void computeMedian(double[] sorted) {
      ProfitEntry entry = head;
      int i = 0;
      while (entry != null) {
        sorted[i++] = entry.profit;
        entry = entry.nextInCell;
      }
      Arrays.sort(sorted, 0, count);
      if (count == 0) cachedMedian = 0.0;
      else if ((count & 1) == 1) cachedMedian = sorted[count / 2];
      else cachedMedian = (sorted[count / 2 - 1] + sorted[count / 2]) / 2.0;
      dirty = false;
    }
  }

  private static final class CsvLineReader {
    private static final int BUFFER_SIZE = 1024 * 1024;
    private final FileInputStream input;
    private final byte[] bytes = new byte[BUFFER_SIZE];
    private int scanStart;
    private int filled;
    private boolean eof;
    int lineStart;
    int lineEnd;

    CsvLineReader(String path) throws IOException {
      input = new FileInputStream(path);
    }

    boolean nextLine() throws IOException {
      while (true) {
        int i = scanStart;
        while (i < filled) {
          if (bytes[i] == '\n') {
            lineStart = scanStart;
            lineEnd = i > lineStart && bytes[i - 1] == '\r' ? i - 1 : i;
            scanStart = i + 1;
            return true;
          }
          i += 1;
        }
        if (eof) {
          if (scanStart < filled) {
            lineStart = scanStart;
            lineEnd = filled > lineStart && bytes[filled - 1] == '\r' ? filled - 1 : filled;
            scanStart = filled;
            return true;
          }
          return false;
        }
        compactIfNeeded();
        int read = input.read(bytes, filled, bytes.length - filled);
        if (read < 0) eof = true;
        else filled += read;
      }
    }

    void close() throws IOException {
      input.close();
    }

    private void compactIfNeeded() {
      if (scanStart > 0) {
        int remaining = filled - scanStart;
        System.arraycopy(bytes, scanStart, bytes, 0, remaining);
        scanStart = 0;
        filled = remaining;
      } else if (filled == bytes.length) {
        throw new IllegalArgumentException("CSV row exceeds input buffer");
      }
    }
  }

  private static final class Trip {
    private byte[] bytes;
    private int taxiStart;
    private int taxiEnd;
    private int pickupTimestampStart;
    private int pickupTimestampEnd;
    private int dropoffTimestampStart;
    private int dropoffTimestampEnd;
    long pickupSeconds;
    long dropoffSeconds;
    double pickupLongitude;
    double pickupLatitude;
    double dropoffLongitude;
    double dropoffLatitude;
    double fare;
    double tip;

    boolean parseInto(byte[] source, int from, int until) {
      int taxiStart = -1;
      int taxiEnd = -1;
      int pickupTsStart = -1;
      int pickupTsEnd = -1;
      int dropoffTsStart = -1;
      int dropoffTsEnd = -1;
      int pickupLonStart = -1;
      int pickupLonEnd = -1;
      int pickupLatStart = -1;
      int pickupLatEnd = -1;
      int dropoffLonStart = -1;
      int dropoffLonEnd = -1;
      int dropoffLatStart = -1;
      int dropoffLatEnd = -1;
      int fareStart = -1;
      int fareEnd = -1;
      int tipStart = -1;
      int tipEnd = -1;
      int field = 0;
      int start = from;
      for (int i = from; i <= until; i++) {
        if (i == until || source[i] == ',') {
          switch (field) {
            case 0 -> {
              taxiStart = start;
              taxiEnd = i;
            }
            case 2 -> {
              pickupTsStart = start;
              pickupTsEnd = i;
            }
            case 3 -> {
              dropoffTsStart = start;
              dropoffTsEnd = i;
            }
            case 6 -> {
              pickupLonStart = start;
              pickupLonEnd = i;
            }
            case 7 -> {
              pickupLatStart = start;
              pickupLatEnd = i;
            }
            case 8 -> {
              dropoffLonStart = start;
              dropoffLonEnd = i;
            }
            case 9 -> {
              dropoffLatStart = start;
              dropoffLatEnd = i;
            }
            case 11 -> {
              fareStart = start;
              fareEnd = i;
            }
            case 14 -> {
              tipStart = start;
              tipEnd = i;
            }
            default -> {}
          }
          field += 1;
          start = i + 1;
        }
      }
      if (field != 17
          || taxiStart < 0
          || pickupTsStart < 0
          || dropoffTsStart < 0
          || pickupLonStart < 0
          || pickupLatStart < 0
          || dropoffLonStart < 0
          || dropoffLatStart < 0
          || fareStart < 0
          || tipStart < 0) {
        return false;
      }
      try {
        this.bytes = source;
        this.taxiStart = taxiStart;
        this.taxiEnd = taxiEnd;
        this.pickupTimestampStart = pickupTsStart;
        this.pickupTimestampEnd = pickupTsEnd;
        this.dropoffTimestampStart = dropoffTsStart;
        this.dropoffTimestampEnd = dropoffTsEnd;
        pickupSeconds = parseTimestampAt(source, pickupTsStart, pickupTsEnd);
        dropoffSeconds = parseTimestampAt(source, dropoffTsStart, dropoffTsEnd);
        pickupLongitude = parseDoubleAt(source, pickupLonStart, pickupLonEnd);
        pickupLatitude = parseDoubleAt(source, pickupLatStart, pickupLatEnd);
        dropoffLongitude = parseDoubleAt(source, dropoffLonStart, dropoffLonEnd);
        dropoffLatitude = parseDoubleAt(source, dropoffLatStart, dropoffLatEnd);
        fare = parseDoubleAt(source, fareStart, fareEnd);
        tip = parseDoubleAt(source, tipStart, tipEnd);
        return true;
      } catch (RuntimeException ex) {
        return false;
      }
    }

    int taxiIdHash() {
      int hash = 0;
      for (int i = taxiStart; i < taxiEnd; i++) hash = 31 * hash + byteChar(bytes[i]);
      return hash;
    }

    byte[] copyTaxiId() {
      byte[] result = new byte[taxiEnd - taxiStart];
      for (int i = 0; i < result.length; i++) result[i] = bytes[taxiStart + i];
      return result;
    }

    boolean taxiIdEquals(byte[] value) {
      int len = taxiEnd - taxiStart;
      if (value.length != len) return false;
      for (int i = 0; i < len; i++) {
        if (bytes[taxiStart + i] != value[i]) return false;
      }
      return true;
    }

    void writePickupTimestamp(Writer writer) throws IOException {
      writeSlice(writer, pickupTimestampStart, pickupTimestampEnd);
    }

    void writeDropoffTimestamp(Writer writer) throws IOException {
      writeSlice(writer, dropoffTimestampStart, dropoffTimestampEnd);
    }

    private void writeSlice(Writer writer, int from, int until) throws IOException {
      for (int i = from; i < until; i++) writer.write(byteChar(bytes[i]));
    }
  }

  private static long parseTimestampAt(byte[] value, int from, int until) {
    if (until - from < 19) throw new NumberFormatException("bad timestamp");
    int year = intAt(value, from, from + 4);
    int month = intAt(value, from + 5, from + 7);
    int day = intAt(value, from + 8, from + 10);
    int hour = intAt(value, from + 11, from + 13);
    int minute = intAt(value, from + 14, from + 16);
    int second = intAt(value, from + 17, from + 19);
    int daysBeforeYear = 0;
    if (year >= 2013) {
      for (int y = 2013; y < year; y++) daysBeforeYear += daysInYear(y);
    } else {
      for (int y = year; y < 2013; y++) daysBeforeYear -= daysInYear(y);
    }
    int[] starts = isLeap(year) ? MONTH_STARTS_LEAP : MONTH_STARTS_NORMAL;
    int days = daysBeforeYear + starts[month - 1] + day - 1;
    return (((long) days * 24L + hour) * 60L + minute) * 60L + second;
  }

  private static double parseDoubleAt(byte[] value, int from, int until) {
    if (from >= until) throw new NumberFormatException("empty decimal");
    int i = from;
    double sign = 1.0;
    char first = byteChar(value[i]);
    if (first == '-' || first == '+') {
      if (first == '-') sign = -1.0;
      i += 1;
      if (i >= until) throw new NumberFormatException("bad decimal");
    }
    double whole = 0.0;
    double fraction = 0.0;
    double scale = 0.1;
    boolean seenDigit = false;
    while (i < until) {
      char ch = byteChar(value[i]);
      if (ch >= '0' && ch <= '9') {
        whole = whole * 10.0 + (double) (ch - '0');
        seenDigit = true;
        i += 1;
      } else if (ch == '.') {
        i += 1;
        while (i < until) {
          char digit = byteChar(value[i]);
          if (digit >= '0' && digit <= '9') {
            fraction += (double) (digit - '0') * scale;
            scale *= 0.1;
            seenDigit = true;
            i += 1;
          } else {
            return parseDoubleSlow(value, from, until);
          }
        }
      } else if (ch == 'e' || ch == 'E') {
        if (!seenDigit) throw new NumberFormatException("bad decimal");
        return sign * (whole + fraction) * Math.pow(10.0, parseExponent(value, i + 1, until));
      } else {
        return parseDoubleSlow(value, from, until);
      }
    }
    if (!seenDigit) throw new NumberFormatException("bad decimal");
    return sign * (whole + fraction);
  }

  private static int parseExponent(byte[] value, int from, int until) {
    if (from >= until) throw new NumberFormatException("bad decimal");
    int i = from;
    int sign = 1;
    char first = byteChar(value[i]);
    if (first == '-' || first == '+') {
      if (first == '-') sign = -1;
      i += 1;
      if (i >= until) throw new NumberFormatException("bad decimal");
    }
    int exponent = 0;
    boolean seenDigit = false;
    while (i < until) {
      int digit = byteChar(value[i]) - '0';
      if (digit < 0 || digit > 9) throw new NumberFormatException("bad decimal");
      exponent = exponent * 10 + digit;
      seenDigit = true;
      i += 1;
    }
    if (!seenDigit) throw new NumberFormatException("bad decimal");
    return sign * exponent;
  }

  private static double parseDoubleSlow(byte[] value, int from, int until) {
    return Double.parseDouble(new String(value, from, until - from));
  }

  private static int intAt(byte[] value, int from, int until) {
    int result = 0;
    for (int i = from; i < until; i++) {
      int digit = byteChar(value[i]) - '0';
      if (digit < 0 || digit > 9) throw new NumberFormatException("bad timestamp");
      result = result * 10 + digit;
    }
    return result;
  }

  private static int q1CellKeyOrZero(double longitude, double latitude) {
    return cellKeyOrZero(longitude, latitude, 300, Q1_LATITUDE_STEP, Q1_LONGITUDE_STEP);
  }

  private static int q2CellKeyOrZero(double longitude, double latitude) {
    return cellKeyOrZero(longitude, latitude, 600, Q1_LATITUDE_STEP / 2.0, Q1_LONGITUDE_STEP / 2.0);
  }

  private static int cellKeyOrZero(double longitude, double latitude, int size, double latStep, double lonStep) {
    double halfLat = latStep / 2.0;
    double halfLon = lonStep / 2.0;
    int east = (int) Math.floor((longitude - (ORIGIN_LONGITUDE - halfLon)) / lonStep) + 1;
    int south = (int) Math.floor(((ORIGIN_LATITUDE + halfLat) - latitude) / latStep) + 1;
    return east >= 1 && east <= size && south >= 1 && south <= size ? (east << CELL_PART_BITS) | south : 0;
  }

  private static long routeKey(int startKey, int endKey) {
    return ((long) startKey << (ROUTE_PART_BITS * 2)) | (long) endKey;
  }

  private static int hash(long key) {
    long x = key;
    x ^= x >>> 33;
    x *= 0xff51afd7ed558ccdL;
    x ^= x >>> 33;
    x *= 0xc4ceb9fe1a85ec53L;
    x ^= x >>> 33;
    return (int) x;
  }

  private static int compareAreas(ProfitableArea left, ProfitableArea right) {
    if (left == right) return 0;
    int byProfitability = Double.compare(right.profitability, left.profitability);
    if (byProfitability != 0) return byProfitability;
    if (left.latestSeq != right.latestSeq) return Long.compare(right.latestSeq, left.latestSeq);
    return compareCellKeysById(left.cellKey, right.cellKey);
  }

  private static int compareCellKeysById(int left, int right) {
    int east = compareDecimalLex(left >>> CELL_PART_BITS, right >>> CELL_PART_BITS);
    return east != 0 ? east : compareDecimalLex(left & CELL_PART_MASK, right & CELL_PART_MASK);
  }

  private static int compareRouteKeysById(long left, long right) {
    int startEast = compareDecimalLex((int) ((left >>> 30) & ROUTE_PART_MASK), (int) ((right >>> 30) & ROUTE_PART_MASK));
    if (startEast != 0) return startEast;
    int startSouth = compareDecimalLex((int) ((left >>> 20) & ROUTE_PART_MASK), (int) ((right >>> 20) & ROUTE_PART_MASK));
    if (startSouth != 0) return startSouth;
    int endEast = compareDecimalLex((int) ((left >>> 10) & ROUTE_PART_MASK), (int) ((right >>> 10) & ROUTE_PART_MASK));
    return endEast != 0 ? endEast : compareDecimalLex((int) (left & ROUTE_PART_MASK), (int) (right & ROUTE_PART_MASK));
  }

  private static int compareDecimalLex(int left, int right) {
    if (left == right) return 0;
    int leftDivisor = highestPowerOf10(left);
    int rightDivisor = highestPowerOf10(right);
    while (leftDivisor > 0 && rightDivisor > 0) {
      int leftDigit = (left / leftDivisor) % 10;
      int rightDigit = (right / rightDivisor) % 10;
      if (leftDigit != rightDigit) return Integer.compare(leftDigit, rightDigit);
      leftDivisor /= 10;
      rightDivisor /= 10;
    }
    if (leftDivisor == 0 && rightDivisor == 0) return 0;
    return leftDivisor == 0 ? -1 : 1;
  }

  private static int highestPowerOf10(int value) {
    int divisor = 1;
    while (value / divisor >= 10) divisor *= 10;
    return divisor;
  }

  private static void writeComma(Writer writer) throws IOException {
    writer.write(',');
  }

  private static void writeCellId(Writer writer, int east, int south) throws IOException {
    writeLong(writer, east);
    writer.write('.');
    writeLong(writer, south);
  }

  private static void writeFixed(Writer writer, double value, int digits) throws IOException {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      writer.write(Double.toString(value));
      return;
    }
    long factor = powerOf10(digits);
    long scaled = Math.round(value * (double) factor);
    if (scaled < 0L) {
      writer.write('-');
      scaled = -scaled;
    }
    writeLong(writer, scaled / factor);
    writer.write('.');
    long divisor = factor / 10L;
    long fraction = scaled % factor;
    while (divisor > 0L) {
      int digit = (int) ((fraction / divisor) % 10L);
      writer.write('0' + digit);
      divisor /= 10L;
    }
  }

  private static void writeLong(Writer writer, long value) throws IOException {
    writer.write(Long.toString(value));
  }

  private static long powerOf10(int digits) {
    long result = 1L;
    for (int i = 0; i < digits; i++) result *= 10L;
    return result;
  }

  private static char byteChar(byte value) {
    return (char) (value & 0xff);
  }

  private static boolean isLeap(int year) {
    return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
  }

  private static int daysInYear(int year) {
    return isLeap(year) ? 366 : 365;
  }

  private static final class Metrics {
    long events;
    long parsed;
    long invalid;
    long q1Outputs;
    long q2Outputs;
    long readNanos;
    long parseNanos;
    long q1ProcessNanos;
    long q1OutputNanos;
    long q2ProcessNanos;
    long q2OutputNanos;
    long closeNanos;
  }

  private static final class Q1Bucket {
    final long startSeconds;
    Q1Entry head;

    Q1Bucket(long startSeconds) {
      this.startSeconds = startSeconds;
    }
  }

  private static final class Q1Entry {
    final long routeKey;
    final Q1Entry next;

    Q1Entry(long routeKey, Q1Entry next) {
      this.routeKey = routeKey;
      this.next = next;
    }
  }

  private static final class RankedRoute {
    final long routeKey;
    int count;
    long latestSeconds;
    long latestSeq;

    RankedRoute(long routeKey, int count, long latestSeconds, long latestSeq) {
      this.routeKey = routeKey;
      this.count = count;
      this.latestSeconds = latestSeconds;
      this.latestSeq = latestSeq;
    }
  }

  private static final class ProfitBucket {
    final long startSeconds;
    ProfitEntry head;

    ProfitBucket(long startSeconds) {
      this.startSeconds = startSeconds;
    }
  }

  private static final class EmptyBucket {
    final long startSeconds;
    EmptyEntry head;

    EmptyBucket(long startSeconds) {
      this.startSeconds = startSeconds;
    }
  }

  private static final class ProfitEntry {
    final int cellKey;
    final double profit;
    final ProfitEntry bucketNext;
    ProfitEntry nextInCell;
    ProfitEntry previousInCell;

    ProfitEntry(int cellKey, double profit, ProfitEntry bucketNext) {
      this.cellKey = cellKey;
      this.profit = profit;
      this.bucketNext = bucketNext;
    }
  }

  private static final class EmptyEntry {
    final long seq;
    final int taxiKey;
    final int cellKey;
    final EmptyEntry next;

    EmptyEntry(long seq, int taxiKey, int cellKey, EmptyEntry next) {
      this.seq = seq;
      this.taxiKey = taxiKey;
      this.cellKey = cellKey;
      this.next = next;
    }
  }

  private static final class ProfitableArea {
    final int cellKey;
    int emptyTaxis;
    double medianProfit;
    double profitability;
    long latestSeq;

    ProfitableArea(int cellKey, int emptyTaxis, double medianProfit, double profitability, long latestSeq) {
      this.cellKey = cellKey;
      this.emptyTaxis = emptyTaxis;
      this.medianProfit = medianProfit;
      this.profitability = profitability;
      this.latestSeq = latestSeq;
    }
  }

  private static final class TaxiIdEntry {
    final int hash;
    final byte[] taxiId;
    final int id;
    TaxiIdEntry next;

    TaxiIdEntry(int hash, byte[] taxiId, int id, TaxiIdEntry next) {
      this.hash = hash;
      this.taxiId = taxiId;
      this.id = id;
      this.next = next;
    }
  }

  private static final class Q2Snapshot {
    static final Q2Snapshot EMPTY = new Q2Snapshot(new int[0], new int[0], new double[0], new double[0]);
    final int[] cellKeys;
    final int[] emptyTaxis;
    final double[] medianProfits;
    final double[] profitabilities;

    Q2Snapshot(int[] cellKeys, int[] emptyTaxis, double[] medianProfits, double[] profitabilities) {
      this.cellKeys = cellKeys;
      this.emptyTaxis = emptyTaxis;
      this.medianProfits = medianProfits;
      this.profitabilities = profitabilities;
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

  private static final long EMPTY_ROUTE_KEY = 0L;
  private static final long DELETED_ROUTE_KEY = -1L;
  private static final int ROUTE_PART_BITS = 10;
  private static final long ROUTE_PART_MASK = (1L << ROUTE_PART_BITS) - 1L;
  private static final int CELL_PART_BITS = 10;
  private static final int CELL_PART_MASK = (1 << CELL_PART_BITS) - 1;
  private static final int CELL_KEY_CAPACITY = (600 + 1) << CELL_PART_BITS;
  private static final long PROFIT_WINDOW_SECONDS = 15L * 60L;
  private static final long EMPTY_WINDOW_SECONDS = 30L * 60L;
  private static final double ORIGIN_LATITUDE = 41.474937;
  private static final double ORIGIN_LONGITUDE = -74.913585;
  private static final double Q1_LATITUDE_STEP = 0.004491556;
  private static final double Q1_LONGITUDE_STEP = 0.005986;
  private static final int[] MONTH_STARTS_NORMAL = {0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334};
  private static final int[] MONTH_STARTS_LEAP = {0, 31, 60, 91, 121, 152, 182, 213, 244, 274, 305, 335};
  private static final RankedRoute[] EMPTY_RANKED_ROUTES = new RankedRoute[0];
  private static final ProfitableArea[] EMPTY_PROFITABLE_AREAS = new ProfitableArea[0];
}
