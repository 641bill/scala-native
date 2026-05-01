#if defined(SCALANATIVE_COMPILE_ALWAYS) ||                                     \
    defined(__SCALANATIVE_MEMORY_SAFEZONE)
#include <stdio.h>
#include <stdlib.h>
#include <memory.h>
#include <stdbool.h>
#include <stdint.h>
#include <errno.h>
#include <limits.h>
#include <time.h>
#include "MemoryPool.h"
#include "shared/ScalaNativeGC.h"
#include "shared/MemoryMap.h"

static size_t memoryPoolPageSize = MEMORYPOOL_DEFAULT_PAGE_SIZE;
static bool memoryPoolPageSizeInitialized = false;
static int memoryPoolRootsMode = 0;
static bool memoryPoolRootsModeInitialized = false;

static bool memoryPoolTraceEnabled = false;
static bool memoryPoolTraceInitialized = false;
static unsigned long long memoryPoolTraceClaimCalls = 0;
static unsigned long long memoryPoolTraceReclaimCalls = 0;
static unsigned long long memoryPoolTraceClaimNs = 0;
static unsigned long long memoryPoolTraceReclaimNs = 0;
static unsigned long long memoryPoolTraceRootAddCalls = 0;
static unsigned long long memoryPoolTraceRootAddClaimCalls = 0;
static unsigned long long memoryPoolTraceRootAddChunkCalls = 0;
static unsigned long long memoryPoolTraceRootRemoveCalls = 0;
static unsigned long long memoryPoolTraceRootAddNs = 0;
static unsigned long long memoryPoolTraceRootRemoveNs = 0;
static unsigned long long memoryPoolTraceChunkAllocs = 0;
static unsigned long long memoryPoolTracePageAllocs = 0;
static unsigned long long memoryPoolTraceChunkAllocNs = 0;
static unsigned long long memoryPoolTracePageAllocNs = 0;
static unsigned long long memoryPoolTraceReclaimedPages = 0;
static unsigned long long memoryPoolTraceRootRemoveCoalescedPages = 0;
static unsigned long long memoryPoolTraceReclaimSortCalls = 0;
static unsigned long long memoryPoolTraceReclaimSortNs = 0;
static unsigned long long memoryPoolTraceReclaimBookkeepingNs = 0;
static unsigned long long memoryPoolTraceReclaimRuns = 0;

static unsigned long long MemoryPool_now_ns() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (unsigned long long)ts.tv_sec * 1000000000ULL +
           (unsigned long long)ts.tv_nsec;
}

static MemoryPage *MemoryPool_merge_sorted_pages(MemoryPage *a, MemoryPage *b) {
    MemoryPage *head = NULL;
    MemoryPage **tail = &head;
    while (a != NULL && b != NULL) {
        if ((uintptr_t)a->start <= (uintptr_t)b->start) {
            *tail = a;
            a = a->next;
        } else {
            *tail = b;
            b = b->next;
        }
        tail = &((*tail)->next);
    }
    *tail = (a != NULL) ? a : b;
    return head;
}

static MemoryPage *MemoryPool_sort_pages_by_start(MemoryPage *head) {
    if (head == NULL || head->next == NULL) {
        return head;
    }
    MemoryPage *slow = head;
    MemoryPage *fast = head->next;
    while (fast != NULL && fast->next != NULL) {
        slow = slow->next;
        fast = fast->next->next;
    }
    MemoryPage *mid = slow->next;
    slow->next = NULL;
    MemoryPage *left = MemoryPool_sort_pages_by_start(head);
    MemoryPage *right = MemoryPool_sort_pages_by_start(mid);
    return MemoryPool_merge_sorted_pages(left, right);
}

static void MemoryPool_trace_report() {
    if (!memoryPoolTraceEnabled) {
        return;
    }
    fprintf(stderr,
            "[SafeZoneTracePool] claim_calls=%llu reclaim_calls=%llu reclaimed_pages=%llu\n",
            memoryPoolTraceClaimCalls, memoryPoolTraceReclaimCalls,
            memoryPoolTraceReclaimedPages);
    fprintf(stderr,
            "[SafeZoneTracePool] root_add_calls=%llu root_remove_calls=%llu\n",
            memoryPoolTraceRootAddCalls, memoryPoolTraceRootRemoveCalls);
    fprintf(stderr,
            "[SafeZoneTracePool] root_add_claim_calls=%llu root_add_chunk_calls=%llu\n",
            memoryPoolTraceRootAddClaimCalls, memoryPoolTraceRootAddChunkCalls);
    fprintf(stderr,
            "[SafeZoneTracePool] roots_mode=%d root_remove_coalesced_pages=%llu\n",
            memoryPoolRootsMode, memoryPoolTraceRootRemoveCoalescedPages);
    fprintf(stderr,
            "[SafeZoneTracePool] chunk_allocs=%llu page_allocs=%llu\n",
            memoryPoolTraceChunkAllocs, memoryPoolTracePageAllocs);
    fprintf(stderr,
            "[SafeZoneTracePool] claim_time_ms=%.3f reclaim_time_ms=%.3f\n",
            (double)memoryPoolTraceClaimNs / 1000000.0,
            (double)memoryPoolTraceReclaimNs / 1000000.0);
    fprintf(stderr,
            "[SafeZoneTracePool] root_add_time_ms=%.3f root_remove_time_ms=%.3f\n",
            (double)memoryPoolTraceRootAddNs / 1000000.0,
            (double)memoryPoolTraceRootRemoveNs / 1000000.0);
    fprintf(stderr,
            "[SafeZoneTracePool] reclaim_sort_calls=%llu reclaim_runs=%llu\n",
            memoryPoolTraceReclaimSortCalls, memoryPoolTraceReclaimRuns);
    fprintf(stderr,
            "[SafeZoneTracePool] reclaim_sort_time_ms=%.3f reclaim_bookkeeping_time_ms=%.3f\n",
            (double)memoryPoolTraceReclaimSortNs / 1000000.0,
            (double)memoryPoolTraceReclaimBookkeepingNs / 1000000.0);
    fprintf(stderr,
            "[SafeZoneTracePool] chunk_alloc_time_ms=%.3f page_alloc_time_ms=%.3f\n",
            (double)memoryPoolTraceChunkAllocNs / 1000000.0,
            (double)memoryPoolTracePageAllocNs / 1000000.0);
}

static bool MemoryPool_trace_enabled() {
    if (!memoryPoolTraceInitialized) {
        const char *value = getenv("SAFEZONE_TRACE");
        memoryPoolTraceEnabled =
            value != NULL && value[0] != '\0' &&
            !(value[0] == '0' && value[1] == '\0');
        if (memoryPoolTraceEnabled) {
            atexit(MemoryPool_trace_report);
        }
        memoryPoolTraceInitialized = true;
    }
    return memoryPoolTraceEnabled;
}

static bool MemoryPool_parse_page_size(const char *value, size_t *parsed) {
    if (value == NULL || *value == '\0') {
        return false;
    }
    char *end = NULL;
    errno = 0;
    unsigned long long raw = strtoull(value, &end, 10);
    if (errno != 0 || end == value || *end != '\0' || raw == 0ULL ||
        raw > (unsigned long long)SIZE_MAX) {
        return false;
    }
    *parsed = (size_t)raw;
    return true;
}

static bool MemoryPool_parse_roots_mode(const char *value, int *parsed) {
    if (value == NULL || *value == '\0') {
        return false;
    }
    char *end = NULL;
    errno = 0;
    long raw = strtol(value, &end, 10);
    if (errno != 0 || end == value || *end != '\0' || raw < 0L ||
        raw > MEMORYPOOL_ROOTS_UNSAFE_NO_ROOTS) {
        return false;
    }
    *parsed = (int)raw;
    return true;
}

size_t MemoryPool_page_size() {
    if (!memoryPoolPageSizeInitialized) {
        const char *env = getenv("SAFEZONE_PAGE_SIZE");
        size_t parsed = 0;
        if (MemoryPool_parse_page_size(env, &parsed)) {
            memoryPoolPageSize = parsed;
            fprintf(stderr,
                    "[SafeZone] Using page size from environment: %zu bytes\n",
                    memoryPoolPageSize);
        } else if (env != NULL && *env != '\0') {
            fprintf(stderr,
                    "[SafeZone] Ignoring invalid SAFEZONE_PAGE_SIZE='%s', using default %zu bytes\n",
                    env, memoryPoolPageSize);
        }
        memoryPoolPageSizeInitialized = true;
    }
    return memoryPoolPageSize;
}

int MemoryPool_roots_mode() {
    if (!memoryPoolRootsModeInitialized) {
        const char *env = getenv("SAFEZONE_ROOTS_MODE");
        int parsed = 0;
        if (MemoryPool_parse_roots_mode(env, &parsed)) {
            memoryPoolRootsMode = parsed;
            fprintf(stderr,
                    "[SafeZone] Using roots mode from environment: %d\n",
                    memoryPoolRootsMode);
        } else if (env != NULL && *env != '\0') {
            fprintf(stderr,
                    "[SafeZone] Ignoring invalid SAFEZONE_ROOTS_MODE='%s', using default %d\n",
                    env, memoryPoolRootsMode);
        }
        memoryPoolRootsModeInitialized = true;
    }
    return memoryPoolRootsMode;
}

MemoryPool *MemoryPool_open() {
    (void)MemoryPool_page_size();
    (void)MemoryPool_roots_mode();
    (void)MemoryPool_trace_enabled();
    MemoryPool *pool = malloc(sizeof(MemoryPool));
    pool->chunkPageCount = MEMORYPOOL_MIN_CHUNK_COUNT;
    pool->chunk = NULL;
    pool->page = NULL;
    return pool;
}

void MemoryPool_alloc_chunk(MemoryPool *pool) {
    const bool trace = memoryPoolTraceEnabled;
    const unsigned long long startNs = trace ? MemoryPool_now_ns() : 0ULL;
    const size_t pageSize = MemoryPool_page_size();
    const int rootsMode = MemoryPool_roots_mode();
    MemoryChunk *chunk = malloc(sizeof(MemoryChunk));
    chunk->size = pool->chunkPageCount * pageSize;
    chunk->offset = 0;
    chunk->start = memoryMapOrExitOnError(chunk->size);
    if (rootsMode == MEMORYPOOL_ROOTS_CHUNK) {
        const unsigned long long rootStartNs =
            trace ? MemoryPool_now_ns() : 0ULL;
        scalanative_GC_add_roots(chunk->start, chunk->start + chunk->size);
        if (trace) {
            memoryPoolTraceRootAddCalls += 1;
            memoryPoolTraceRootAddChunkCalls += 1;
            memoryPoolTraceRootAddNs += MemoryPool_now_ns() - rootStartNs;
        }
    }
    chunk->next = pool->chunk;
    pool->chunk = chunk;
    if (pool->chunkPageCount < MEMORYPOOL_MAX_CHUNK_COUNT) {
        pool->chunkPageCount *= 2;
    }
    if (trace) {
        memoryPoolTraceChunkAllocs += 1;
        memoryPoolTraceChunkAllocNs += MemoryPool_now_ns() - startNs;
    }
}

void MemoryPool_alloc_page(MemoryPool *pool) {
    const bool trace = memoryPoolTraceEnabled;
    const unsigned long long startNs = trace ? MemoryPool_now_ns() : 0ULL;
    const size_t pageSize = MemoryPool_page_size();
    if (pool->chunk == NULL || pool->chunk->offset >= pool->chunk->size) {
        MemoryPool_alloc_chunk(pool);
    }
    MemoryPage *page = malloc(sizeof(MemoryPage));
    page->start = pool->chunk->start + pool->chunk->offset;
    page->offset = 0;
    page->size = pageSize;
    page->next = pool->page;
    pool->chunk->offset += page->size;
    pool->page = page;
    if (trace) {
        memoryPoolTracePageAllocs += 1;
        memoryPoolTracePageAllocNs += MemoryPool_now_ns() - startNs;
    }
}

MemoryPage *MemoryPool_claim(MemoryPool *pool) {
    const bool trace = memoryPoolTraceEnabled;
    const unsigned long long startNs = trace ? MemoryPool_now_ns() : 0ULL;
    const int rootsMode = MemoryPool_roots_mode();
    if (pool->page == NULL) {
        MemoryPool_alloc_page(pool);
    }
    MemoryPage *result = pool->page;
    pool->page = result->next;
    result->next = NULL;
    result->offset = 0;
    if (rootsMode != MEMORYPOOL_ROOTS_CHUNK &&
        rootsMode != MEMORYPOOL_ROOTS_UNSAFE_NO_ROOTS) {
        const unsigned long long rootStartNs =
            trace ? MemoryPool_now_ns() : 0ULL;
        scalanative_GC_add_roots(result->start, result->start + result->size);
        if (trace) {
            memoryPoolTraceRootAddCalls += 1;
            memoryPoolTraceRootAddClaimCalls += 1;
            memoryPoolTraceRootAddNs += MemoryPool_now_ns() - rootStartNs;
        }
    }
    if (trace) {
        memoryPoolTraceClaimCalls += 1;
        memoryPoolTraceClaimNs += MemoryPool_now_ns() - startNs;
    }
    return result;
}

void MemoryPool_reclaim(MemoryPool *pool, MemoryPage *head) {
    const bool trace = memoryPoolTraceEnabled;
    const unsigned long long startNs = trace ? MemoryPool_now_ns() : 0ULL;
    const int rootsMode = MemoryPool_roots_mode();
    MemoryPage *reclaimHead = head;
    MemoryPage *page = reclaimHead, *tail = NULL;
    if (rootsMode == MEMORYPOOL_ROOTS_IMPROVED) {
        if (reclaimHead != NULL && reclaimHead->next != NULL) {
            const unsigned long long sortStartNs =
                trace ? MemoryPool_now_ns() : 0ULL;
            reclaimHead = MemoryPool_sort_pages_by_start(reclaimHead);
            if (trace) {
                memoryPoolTraceReclaimSortCalls += 1;
                memoryPoolTraceReclaimSortNs +=
                    MemoryPool_now_ns() - sortStartNs;
            }
        }
        page = reclaimHead;
    }

    while (page != NULL) {
        const unsigned long long opStartNs =
            trace ? MemoryPool_now_ns() : 0ULL;
        MemoryPage *runTail = page;
        char *rangeStart = (char *)page->start;
        char *rangeEnd = rangeStart + page->size;
        unsigned long long pagesInRun = 1;

        if (rootsMode == MEMORYPOOL_ROOTS_IMPROVED) {
            while (runTail->next != NULL &&
                   rangeEnd == (char *)runTail->next->start) {
                runTail = runTail->next;
                rangeEnd = (char *)runTail->start + runTail->size;
                pagesInRun += 1;
            }
        }

        unsigned long long rootDurationNs = 0ULL;
        if (rootsMode != MEMORYPOOL_ROOTS_CHUNK &&
            rootsMode != MEMORYPOOL_ROOTS_UNSAFE_NO_ROOTS) {
            const unsigned long long rootStartNs =
                trace ? MemoryPool_now_ns() : 0ULL;
            scalanative_GC_remove_roots(rangeStart, rangeEnd);
            if (trace) {
                rootDurationNs = MemoryPool_now_ns() - rootStartNs;
                memoryPoolTraceRootRemoveCalls += 1;
                memoryPoolTraceRootRemoveNs += rootDurationNs;
            }
        }

        if (trace) {
            memoryPoolTraceReclaimRuns += 1;
            memoryPoolTraceReclaimedPages += pagesInRun;
            if (rootsMode == MEMORYPOOL_ROOTS_IMPROVED && pagesInRun > 1) {
                memoryPoolTraceRootRemoveCoalescedPages += pagesInRun - 1;
            }
            const unsigned long long opDurationNs =
                MemoryPool_now_ns() - opStartNs;
            if (opDurationNs > rootDurationNs) {
                memoryPoolTraceReclaimBookkeepingNs +=
                    opDurationNs - rootDurationNs;
            }
        }

        tail = runTail;
        page = runTail->next;
    }

    if (tail != NULL) {
        tail->next = pool->page;
        pool->page = reclaimHead;
    }
    if (trace) {
        memoryPoolTraceReclaimCalls += 1;
        memoryPoolTraceReclaimNs += MemoryPool_now_ns() - startNs;
    }
}

void MemoryPool_close(MemoryPool *pool) {
    const int rootsMode = MemoryPool_roots_mode();
    MemoryChunk *chunk = pool->chunk, *preChunk = NULL;
    while (chunk != NULL) {
        preChunk = chunk;
        chunk = chunk->next;
        if (rootsMode == MEMORYPOOL_ROOTS_CHUNK) {
            scalanative_GC_remove_roots(preChunk->start,
                                        preChunk->start + preChunk->size);
        }
        memoryUnmapOrExitOnError(preChunk->start, preChunk->size);
        free(preChunk);
    }

    MemoryPage *page = pool->page, *prePage = NULL;
    while (page != NULL) {
        prePage = page;
        page = page->next;
        free(prePage);
    }
    free(pool);
}
#endif
