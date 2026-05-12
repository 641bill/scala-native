#if defined(SCALANATIVE_COMPILE_ALWAYS) ||                                     \
    defined(__SCALANATIVE_MEMORY_SAFEZONE)
#include <stdio.h>
#include <stdlib.h>
#include <stdbool.h>
#include <stdint.h>
#include <memory.h>
#include <time.h>
#include "Zone.h"
#include "MemoryPool.h"

MemoryPool *scalanative_zone_default_pool = NULL;
LargeMemoryPool *scalanative_zone_default_largepool = NULL;

static bool scalanative_zone_trace_enabled = false;
static bool scalanative_zone_trace_initialized = false;

static unsigned long long scalanative_zone_trace_open_calls = 0;
static unsigned long long scalanative_zone_trace_close_calls = 0;
static unsigned long long scalanative_zone_trace_alloc_calls = 0;
static unsigned long long scalanative_zone_trace_alloc_fast_calls = 0;
static unsigned long long scalanative_zone_trace_alloc_slow_calls = 0;
static unsigned long long scalanative_zone_trace_small_claim_calls = 0;
static unsigned long long scalanative_zone_trace_large_claim_calls = 0;
static unsigned long long scalanative_zone_trace_reclaimed_small_pages = 0;
static unsigned long long scalanative_zone_trace_reclaimed_large_pages = 0;
static unsigned long long scalanative_zone_trace_alloc_bytes = 0;
static unsigned long long scalanative_zone_trace_alloc_ns = 0;
static unsigned long long scalanative_zone_trace_close_ns = 0;
static unsigned long long scalanative_zone_trace_close_scan_ns = 0;
static unsigned long long scalanative_zone_trace_close_reclaim_ns = 0;
static unsigned long long scalanative_zone_trace_close_free_ns = 0;

static unsigned long long scalanative_zone_now_ns() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (unsigned long long)ts.tv_sec * 1000000000ULL +
           (unsigned long long)ts.tv_nsec;
}

static unsigned long long scalanative_zone_count_pages(MemoryPage *head) {
    unsigned long long count = 0;
    while (head != NULL) {
        count += 1;
        head = head->next;
    }
    return count;
}

static inline size_t scalanative_zone_pad8(size_t addr) {
    return (addr + 7u) & ~(size_t)7u;
}

static void scalanative_zone_trace_report() {
    if (!scalanative_zone_trace_enabled) {
        return;
    }
    fprintf(stderr,
            "[SafeZoneTrace] open=%llu close=%llu alloc=%llu alloc_bytes=%llu\n",
            scalanative_zone_trace_open_calls,
            scalanative_zone_trace_close_calls,
            scalanative_zone_trace_alloc_calls,
            scalanative_zone_trace_alloc_bytes);
    fprintf(stderr,
            "[SafeZoneTrace] alloc_fast=%llu alloc_slow=%llu small_claims=%llu large_claims=%llu\n",
            scalanative_zone_trace_alloc_fast_calls,
            scalanative_zone_trace_alloc_slow_calls,
            scalanative_zone_trace_small_claim_calls,
            scalanative_zone_trace_large_claim_calls);
    fprintf(stderr,
            "[SafeZoneTrace] reclaimed_small_pages=%llu reclaimed_large_pages=%llu\n",
            scalanative_zone_trace_reclaimed_small_pages,
            scalanative_zone_trace_reclaimed_large_pages);
    fprintf(stderr, "[SafeZoneTrace] alloc_time_ms=%.3f close_time_ms=%.3f\n",
            (double)scalanative_zone_trace_alloc_ns / 1000000.0,
            (double)scalanative_zone_trace_close_ns / 1000000.0);
    fprintf(stderr,
            "[SafeZoneTrace] close_scan_time_ms=%.3f close_reclaim_time_ms=%.3f close_free_time_ms=%.3f\n",
            (double)scalanative_zone_trace_close_scan_ns / 1000000.0,
            (double)scalanative_zone_trace_close_reclaim_ns / 1000000.0,
            (double)scalanative_zone_trace_close_free_ns / 1000000.0);
}

static bool scalanative_zone_trace_is_enabled() {
    if (!scalanative_zone_trace_initialized) {
        const char *value = getenv("SAFEZONE_TRACE");
        scalanative_zone_trace_enabled =
            value != NULL && value[0] != '\0' &&
            !(value[0] == '0' && value[1] == '\0');
        if (scalanative_zone_trace_enabled) {
            atexit(scalanative_zone_trace_report);
        }
        scalanative_zone_trace_initialized = true;
    }
    return scalanative_zone_trace_enabled;
}

void *scalanative_zone_open() {
    const bool trace = scalanative_zone_trace_is_enabled();
    if (scalanative_zone_default_pool == NULL) {
        scalanative_zone_default_pool = MemoryPool_open();
    }
    if (scalanative_zone_default_largepool == NULL) {
        scalanative_zone_default_largepool = LargeMemoryPool_open();
    }
    Zone *zone = malloc(sizeof(Zone));
    zone->pool = scalanative_zone_default_pool;
    zone->page = NULL;
    zone->largePool = scalanative_zone_default_largepool;
    zone->largePage = NULL;
    zone->pageSize = MemoryPool_page_size();
    if (trace) {
        scalanative_zone_trace_open_calls += 1;
    }
    return (void *)zone;
}

void scalanative_zone_close(void *_zone) {
    const bool trace = scalanative_zone_trace_enabled;
    const unsigned long long startNs =
        trace ? scalanative_zone_now_ns() : 0ULL;
    Zone *zone = (Zone *)_zone;
    if (trace) {
        const unsigned long long scanStartNs = scalanative_zone_now_ns();
        scalanative_zone_trace_close_calls += 1;
        scalanative_zone_trace_reclaimed_small_pages +=
            scalanative_zone_count_pages(zone->page);
        scalanative_zone_trace_reclaimed_large_pages +=
            scalanative_zone_count_pages(zone->largePage);
        scalanative_zone_trace_close_scan_ns +=
            scalanative_zone_now_ns() - scanStartNs;
    }
    const unsigned long long reclaimStartNs =
        trace ? scalanative_zone_now_ns() : 0ULL;
    MemoryPool_reclaim(zone->pool, zone->page);
    LargeMemoryPool_reclaim(zone->largePool, zone->largePage);
    if (trace) {
        scalanative_zone_trace_close_reclaim_ns +=
            scalanative_zone_now_ns() - reclaimStartNs;
    }
    const unsigned long long freeStartNs =
        trace ? scalanative_zone_now_ns() : 0ULL;
    free(zone);
    if (trace) {
        scalanative_zone_trace_close_free_ns +=
            scalanative_zone_now_ns() - freeStartNs;
        scalanative_zone_trace_close_ns +=
            scalanative_zone_now_ns() - startNs;
    }
}

static MemoryPage *scalanative_zone_claim(Zone *zone, size_t size) {
    return (size <= zone->pageSize)
               ? MemoryPool_claim(zone->pool)
               : LargeMemoryPool_claim(zone->largePool,
                                       scalanative_zone_pad8(size));
}

void *scalanative_zone_alloc(void *_zone, void *info, size_t size) {
    Zone *zone = (Zone *)_zone;
    const bool trace = scalanative_zone_trace_enabled;
    const unsigned long long startNs =
        trace ? scalanative_zone_now_ns() : 0ULL;
    const size_t pageSize = zone->pageSize;
    const bool smallAlloc = size <= pageSize;
    bool usedSlowPath = false;
    MemoryPage *page = smallAlloc ? zone->page : zone->largePage;
    if (page == NULL) {
        usedSlowPath = true;
        page = scalanative_zone_claim(zone, size);
        if (trace) {
            if (smallAlloc) {
                scalanative_zone_trace_small_claim_calls += 1;
            } else {
                scalanative_zone_trace_large_claim_calls += 1;
            }
        }
    }
    size_t paddedOffset = scalanative_zone_pad8(page->offset);
    size_t resOffset = 0;
    if (paddedOffset + size <= page->size) {
        resOffset = paddedOffset;
    } else {
        MemoryPage *newPage = scalanative_zone_claim(zone, size);
        usedSlowPath = true;
        if (trace) {
            if (smallAlloc) {
                scalanative_zone_trace_small_claim_calls += 1;
            } else {
                scalanative_zone_trace_large_claim_calls += 1;
            }
        }
        newPage->next = page;
        page = newPage;
        resOffset = 0;
    }
    page->offset = resOffset + size;
    void *current = (void *)(page->start + resOffset);
    memset(current, 0, size);
    void **alloc = (void **)current;
    *alloc = info;
    if (size <= pageSize) {
        zone->page = page;
    } else {
        zone->largePage = page;
    }
    if (trace) {
        scalanative_zone_trace_alloc_calls += 1;
        scalanative_zone_trace_alloc_bytes += size;
        if (usedSlowPath) {
            scalanative_zone_trace_alloc_slow_calls += 1;
        } else {
            scalanative_zone_trace_alloc_fast_calls += 1;
        }
        scalanative_zone_trace_alloc_ns += scalanative_zone_now_ns() - startNs;
    }
    return (void *)alloc;
}
#endif
