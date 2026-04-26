#if defined(SCALANATIVE_GC_IMMIX) || defined(SCALANATIVE_GC_COMMIX) ||         \
    defined(SCALANATIVE_GC_BOEHM)

#include "jmx.h"
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

// The total (accumulated) number of GC runs
static size_t GC_STATS_COLLECTION_TOTAL = 0L;

// The total (accumulated) elapsed time in nanos of GC runs
static size_t GC_STATS_COLLECTION_DURATION_TOTAL = 0L;

static _Atomic(size_t) GC_STATS_ALLOCATION_TOTAL = 0L;
static _Atomic(size_t) GC_STATS_ALLOCATION_BYTES_TOTAL = 0L;
static _Atomic(size_t) GC_STATS_ALLOCATION_DURATION_TOTAL = 0L;
static int GC_STATS_ALLOCATION_ENABLED = 0;

size_t jmx_stats_get_collection_total() { return GC_STATS_COLLECTION_TOTAL; }

size_t jmx_stats_get_collection_duration_total() {
    return GC_STATS_COLLECTION_DURATION_TOTAL;
}

void jmx_stats_record_collection(size_t start_ns, size_t end_ns) {
    GC_STATS_COLLECTION_TOTAL++;
    GC_STATS_COLLECTION_DURATION_TOTAL += (end_ns - start_ns);
}

void jmx_stats_init_allocation() {
    const char *env = getenv("SCALANATIVE_GC_ALLOC_STATS");
    GC_STATS_ALLOCATION_ENABLED =
        env != NULL && env[0] != '\0' && strcmp(env, "0") != 0;
}

int jmx_stats_allocation_enabled() { return GC_STATS_ALLOCATION_ENABLED; }

void jmx_stats_record_allocation(size_t bytes, size_t start_ns, size_t end_ns) {
    if (!GC_STATS_ALLOCATION_ENABLED) return;

    atomic_fetch_add_explicit(&GC_STATS_ALLOCATION_TOTAL, 1,
                              memory_order_relaxed);
    atomic_fetch_add_explicit(&GC_STATS_ALLOCATION_BYTES_TOTAL, bytes,
                              memory_order_relaxed);
    if (end_ns >= start_ns) {
        atomic_fetch_add_explicit(&GC_STATS_ALLOCATION_DURATION_TOTAL,
                                  end_ns - start_ns, memory_order_relaxed);
    }
}

size_t jmx_stats_get_allocation_total() {
    return atomic_load_explicit(&GC_STATS_ALLOCATION_TOTAL,
                                memory_order_relaxed);
}

size_t jmx_stats_get_allocation_bytes_total() {
    return atomic_load_explicit(&GC_STATS_ALLOCATION_BYTES_TOTAL,
                                memory_order_relaxed);
}

size_t jmx_stats_get_allocation_duration_total() {
    return atomic_load_explicit(&GC_STATS_ALLOCATION_DURATION_TOTAL,
                                memory_order_relaxed);
}

#endif // defined(SCALANATIVE_GC_IMMIX) || defined(SCALANATIVE_GC_COMMIX) ||
       // defined(SCALANATIVE_GC_BOEHM)
