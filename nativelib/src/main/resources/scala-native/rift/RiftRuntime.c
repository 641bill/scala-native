#if defined(SCALANATIVE_COMPILE_ALWAYS) || defined(__SCALANATIVE_MEMORY_RIFT)
#include "rift/RiftRuntime.h"

#include <stdatomic.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#if defined(__APPLE__) && defined(__MACH__)
#include <mach/mach_time.h>
#else
#include <time.h>
#endif

#ifndef MAP_POPULATE
#define MAP_POPULATE 0
#endif

#ifndef MAP_ANONYMOUS
#define MAP_ANONYMOUS MAP_ANON
#endif

#define SCALANATIVE_RIFT_SLAB_FLAG_HUGE 0x1u
#define SCALANATIVE_RIFT_SLAB_FLAG_ZEROED 0x2u
#define SCALANATIVE_RIFT_SLAB_FLAG_SMALL 0x4u

/* Streaming child buckets are often sparse. Use a page-sized first slab for
 * streaming regions, then fall back to regular 32 KiB slabs on overflow.
 */
#define SCALANATIVE_RIFT_SMALL_SLAB_SIZE (4 * 1024)

/* Keep enough closed slabs for reuse, but do not let streaming runs retain
 * hundreds of MiB in the global pool after old windows have been closed.
 */
#define SCALANATIVE_RIFT_POOL_MAX_BYTES (128 * 1024 * 1024)

typedef struct scalanative_rift_slab {
    struct scalanative_rift_slab *next;
    size_t mapped_size;
    uint32_t flags;
    uint32_t reserved;
    uint8_t data[];
} scalanative_rift_slab;

typedef struct scalanative_rift_region {
    uint8_t *bump;
    uint8_t *end;
    scalanative_rift_slab *current;
    scalanative_rift_slab *head;
    size_t alloc_raw_count;
    size_t alloc_object_count;
    size_t alloc_slow_count;
    size_t alloc_raw_bytes;
    uint32_t kind;
    uint32_t family;
    uint32_t slab_count;
    uint32_t alloc_stats_enabled;
    uint32_t current_slab_zeroed;
} scalanative_rift_region;

#define SCALANATIVE_RIFT_SLAB_DATA_SIZE                                         \
    (SCALANATIVE_RIFT_SLAB_SIZE - sizeof(scalanative_rift_slab))

static _Atomic(scalanative_rift_slab *) scalanative_rift_pool_head = NULL;
static _Atomic(size_t) scalanative_rift_pool_size = 0;
static _Atomic(scalanative_rift_slab *) scalanative_rift_small_pool_head = NULL;
static _Atomic(size_t) scalanative_rift_small_pool_size = 0;

static _Atomic(size_t) scalanative_rift_stats_region_open_total_value = 0;
static _Atomic(size_t) scalanative_rift_stats_region_close_total_value = 0;
static _Atomic(size_t) scalanative_rift_stats_region_reset_total_value = 0;
static _Atomic(size_t) scalanative_rift_stats_alloc_raw_total_value = 0;
static _Atomic(size_t) scalanative_rift_stats_alloc_raw_bytes_total_value = 0;
static _Atomic(size_t) scalanative_rift_stats_alloc_object_total_value = 0;
static _Atomic(size_t) scalanative_rift_stats_alloc_slow_total_value = 0;
static _Atomic(size_t) scalanative_rift_stats_mmap_slab_total_value = 0;
static _Atomic(size_t) scalanative_rift_stats_mmap_bytes_total_value = 0;
static _Atomic(size_t) scalanative_rift_stats_mmap_slab_current_value = 0;
static _Atomic(size_t) scalanative_rift_stats_mmap_slab_peak_value = 0;
static _Atomic(size_t) scalanative_rift_stats_mmap_bytes_current_value = 0;
static _Atomic(size_t) scalanative_rift_stats_mmap_bytes_peak_value = 0;
static _Atomic(size_t) scalanative_rift_stats_active_slab_current_value = 0;
static _Atomic(size_t) scalanative_rift_stats_active_slab_peak_value = 0;
static _Atomic(size_t) scalanative_rift_stats_active_bytes_current_value = 0;
static _Atomic(size_t) scalanative_rift_stats_active_bytes_peak_value = 0;
static _Atomic(size_t) scalanative_rift_stats_active_alloc_bytes_current_value =
    0;
static _Atomic(size_t) scalanative_rift_stats_active_alloc_bytes_peak_value = 0;
static _Atomic(size_t) scalanative_rift_stats_family_alloc_raw_bytes_total_values
    [SCALANATIVE_RIFT_FAMILY_MAX];
static _Atomic(size_t) scalanative_rift_stats_family_active_bytes_current_values
    [SCALANATIVE_RIFT_FAMILY_MAX];
static _Atomic(size_t) scalanative_rift_stats_family_active_bytes_peak_values
    [SCALANATIVE_RIFT_FAMILY_MAX];
static _Atomic(size_t)
    scalanative_rift_stats_family_active_alloc_bytes_current_values
    [SCALANATIVE_RIFT_FAMILY_MAX];
static _Atomic(size_t)
    scalanative_rift_stats_family_active_alloc_bytes_peak_values
    [SCALANATIVE_RIFT_FAMILY_MAX];
static _Atomic(size_t) scalanative_rift_stats_tls_reuse_total_value = 0;
static _Atomic(size_t) scalanative_rift_stats_pool_reuse_total_value = 0;
static _Atomic(size_t) scalanative_rift_stats_region_op_ns_value = 0;
static _Atomic(size_t) scalanative_rift_stats_open_ns_value = 0;
static _Atomic(size_t) scalanative_rift_stats_close_ns_value = 0;
static _Atomic(size_t) scalanative_rift_stats_reset_ns_value = 0;
static _Atomic(size_t) scalanative_rift_stats_slow_alloc_ns_value = 0;

static bool scalanative_rift_precise_alloc_stats_initialized = false;
static bool scalanative_rift_precise_alloc_stats_value = false;
static bool scalanative_rift_alloc_stats_initialized = false;
static bool scalanative_rift_alloc_stats_value = true;

static __thread scalanative_rift_slab *
    scalanative_rift_tls_cache[SCALANATIVE_RIFT_TLS_SLAB_CACHE_MAX];
static __thread int scalanative_rift_tls_count = 0;

static inline uint64_t scalanative_rift_now_ns(void) {
#if defined(__APPLE__) && defined(__MACH__)
    mach_timebase_info_data_t timebase;
    uint64_t ticks = mach_absolute_time();
    if (mach_timebase_info(&timebase) != 0 || timebase.denom == 0) return 0;
    return (ticks * (uint64_t)timebase.numer) / (uint64_t)timebase.denom;
#else
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return ((uint64_t)ts.tv_sec * 1000000000ull) + (uint64_t)ts.tv_nsec;
#endif
}

static inline void scalanative_rift_stats_add(_Atomic(size_t) *counter,
                                              size_t value) {
    atomic_fetch_add_explicit(counter, value, memory_order_relaxed);
}

static inline bool scalanative_rift_precise_alloc_stats_enabled(void) {
    if (!scalanative_rift_precise_alloc_stats_initialized) {
        const char *value = getenv("RIFT_PRECISE_ALLOC_STATS");
        scalanative_rift_precise_alloc_stats_value =
            value != NULL && value[0] != '\0' &&
            !(value[0] == '0' && value[1] == '\0');
        scalanative_rift_precise_alloc_stats_initialized = true;
    }
    return scalanative_rift_precise_alloc_stats_value;
}

static inline bool scalanative_rift_truthy_env(const char *value) {
    return value != NULL && value[0] != '\0' &&
           !(value[0] == '0' && value[1] == '\0');
}

static inline bool scalanative_rift_alloc_stats_enabled(void) {
    if (!scalanative_rift_alloc_stats_initialized) {
        const char *explicit_value = getenv("RIFT_ALLOC_STATS");
        if (explicit_value != NULL && explicit_value[0] != '\0') {
            scalanative_rift_alloc_stats_value =
                scalanative_rift_truthy_env(explicit_value);
        } else {
            scalanative_rift_alloc_stats_value =
                !scalanative_rift_truthy_env(getenv("RIFT_FINAL_CLEAN"));
        }
        scalanative_rift_alloc_stats_initialized = true;
    }
    return scalanative_rift_alloc_stats_value;
}

static inline void scalanative_rift_stats_update_peak(
    _Atomic(size_t) *counter, size_t candidate) {
    size_t observed = atomic_load_explicit(counter, memory_order_relaxed);
    while (candidate > observed &&
           !atomic_compare_exchange_weak_explicit(
               counter, &observed, candidate, memory_order_relaxed,
               memory_order_relaxed)) {
    }
}

static inline int scalanative_rift_family_is_valid(uint32_t family) {
    return family < SCALANATIVE_RIFT_FAMILY_MAX;
}

static inline uint32_t scalanative_rift_normalize_family(uint32_t family) {
    return scalanative_rift_family_is_valid(family) ? family : 0;
}

static inline void scalanative_rift_stats_family_add_current(
    _Atomic(size_t) *counters, _Atomic(size_t) *peaks, uint32_t family,
    size_t bytes) {
    size_t current;

    if (family == 0 || !scalanative_rift_family_is_valid(family) ||
        bytes == 0) {
        return;
    }

    current = atomic_fetch_add_explicit(&counters[family], bytes,
                                        memory_order_relaxed) +
              bytes;
    scalanative_rift_stats_update_peak(&peaks[family], current);
}

static inline void scalanative_rift_stats_family_sub_current(
    _Atomic(size_t) *counters, uint32_t family, size_t bytes) {
    if (family == 0 || !scalanative_rift_family_is_valid(family) ||
        bytes == 0) {
        return;
    }

    atomic_fetch_sub_explicit(&counters[family], bytes, memory_order_relaxed);
}

static inline void scalanative_rift_stats_family_add_total(
    _Atomic(size_t) *counters, uint32_t family, size_t bytes) {
    if (family == 0 || !scalanative_rift_family_is_valid(family) ||
        bytes == 0) {
        return;
    }

    atomic_fetch_add_explicit(&counters[family], bytes, memory_order_relaxed);
}

static inline void scalanative_rift_stats_record_mmap(size_t bytes) {
    size_t current_slabs = atomic_fetch_add_explicit(
                               &scalanative_rift_stats_mmap_slab_current_value,
                               1, memory_order_relaxed) +
                           1;
    size_t current_bytes = atomic_fetch_add_explicit(
                               &scalanative_rift_stats_mmap_bytes_current_value,
                               bytes, memory_order_relaxed) +
                           bytes;

    scalanative_rift_stats_add(&scalanative_rift_stats_mmap_slab_total_value,
                               1);
    scalanative_rift_stats_add(&scalanative_rift_stats_mmap_bytes_total_value,
                               bytes);
    scalanative_rift_stats_update_peak(
        &scalanative_rift_stats_mmap_slab_peak_value, current_slabs);
    scalanative_rift_stats_update_peak(
        &scalanative_rift_stats_mmap_bytes_peak_value, current_bytes);
}

static inline void scalanative_rift_stats_record_munmap(size_t bytes) {
    atomic_fetch_sub_explicit(&scalanative_rift_stats_mmap_slab_current_value,
                              1, memory_order_relaxed);
    atomic_fetch_sub_explicit(&scalanative_rift_stats_mmap_bytes_current_value,
                              bytes, memory_order_relaxed);
}

static inline void scalanative_rift_stats_record_active_acquire(
    scalanative_rift_region *region, size_t bytes) {
    size_t current_slabs = atomic_fetch_add_explicit(
                               &scalanative_rift_stats_active_slab_current_value,
                               1, memory_order_relaxed) +
                           1;
    size_t current_bytes = atomic_fetch_add_explicit(
                               &scalanative_rift_stats_active_bytes_current_value,
                               bytes, memory_order_relaxed) +
                           bytes;

    scalanative_rift_stats_update_peak(
        &scalanative_rift_stats_active_slab_peak_value, current_slabs);
    scalanative_rift_stats_update_peak(
        &scalanative_rift_stats_active_bytes_peak_value, current_bytes);
    if (region != NULL) {
        scalanative_rift_stats_family_add_current(
            scalanative_rift_stats_family_active_bytes_current_values,
            scalanative_rift_stats_family_active_bytes_peak_values,
            region->family, bytes);
    }
}

static inline void scalanative_rift_stats_record_active_release(
    uint32_t family, size_t bytes) {
    atomic_fetch_sub_explicit(&scalanative_rift_stats_active_slab_current_value,
                              1, memory_order_relaxed);
    atomic_fetch_sub_explicit(&scalanative_rift_stats_active_bytes_current_value,
                              bytes, memory_order_relaxed);
    scalanative_rift_stats_family_sub_current(
        scalanative_rift_stats_family_active_bytes_current_values, family,
        bytes);
}

static inline void scalanative_rift_stats_record_alloc_bytes(
    scalanative_rift_region *region, size_t bytes) {
    size_t current;

    if (region == NULL) return;
    if (!scalanative_rift_alloc_stats_enabled()) return;
    region->alloc_raw_bytes += bytes;
    if (bytes == 0 || !scalanative_rift_precise_alloc_stats_enabled()) return;

    current = atomic_fetch_add_explicit(
                  &scalanative_rift_stats_active_alloc_bytes_current_value,
                  bytes, memory_order_relaxed) +
              bytes;
    scalanative_rift_stats_update_peak(
        &scalanative_rift_stats_active_alloc_bytes_peak_value, current);
    scalanative_rift_stats_family_add_current(
        scalanative_rift_stats_family_active_alloc_bytes_current_values,
        scalanative_rift_stats_family_active_alloc_bytes_peak_values,
        region->family, bytes);
}

static inline void scalanative_rift_stats_release_alloc_bytes(
    scalanative_rift_region *region) {
    if (region == NULL || region->alloc_raw_bytes == 0) return;
    if (scalanative_rift_precise_alloc_stats_enabled()) {
        atomic_fetch_sub_explicit(
            &scalanative_rift_stats_active_alloc_bytes_current_value,
            region->alloc_raw_bytes, memory_order_relaxed);
        scalanative_rift_stats_family_sub_current(
            scalanative_rift_stats_family_active_alloc_bytes_current_values,
            region->family, region->alloc_raw_bytes);
    }
    region->alloc_raw_bytes = 0;
}

static inline void scalanative_rift_stats_add_duration(
    _Atomic(size_t) *counter, uint64_t start_ns, uint64_t end_ns) {
    if (end_ns >= start_ns) {
        scalanative_rift_stats_add(counter, (size_t)(end_ns - start_ns));
    }
}

static inline int scalanative_rift_slab_is_huge(
    const scalanative_rift_slab *slab) {
    return (slab->flags & SCALANATIVE_RIFT_SLAB_FLAG_HUGE) != 0;
}

static inline int scalanative_rift_slab_is_zeroed(
    const scalanative_rift_slab *slab) {
    return (slab->flags & SCALANATIVE_RIFT_SLAB_FLAG_ZEROED) != 0;
}

static inline int scalanative_rift_slab_is_small(
    const scalanative_rift_slab *slab) {
    return (slab->flags & SCALANATIVE_RIFT_SLAB_FLAG_SMALL) != 0;
}

static inline size_t scalanative_rift_slab_usable_size(
    const scalanative_rift_slab *slab) {
    return slab->mapped_size - sizeof(*slab);
}

static size_t scalanative_rift_region_mapped_bytes(
    const scalanative_rift_region *region) {
    size_t bytes = 0;
    const scalanative_rift_slab *slab;

    if (region == NULL) return 0;
    slab = region->head;
    while (slab != NULL) {
        bytes += slab->mapped_size;
        slab = slab->next;
    }
    return bytes;
}

static inline size_t scalanative_rift_normalize_align(size_t align) {
    if (align == 0) return SCALANATIVE_RIFT_DEFAULT_ALIGN;
    if ((align & (align - 1)) != 0) return SCALANATIVE_RIFT_DEFAULT_ALIGN;
    return align;
}

static size_t scalanative_rift_round_up_to_pages(size_t n) {
    long page_size = sysconf(_SC_PAGESIZE);
    size_t page = page_size > 0 ? (size_t)page_size : 4096u;
    size_t rem = n % page;
    return rem == 0 ? n : (n + (page - rem));
}

static size_t scalanative_rift_small_slab_mapped_size(void) {
    return scalanative_rift_round_up_to_pages(
        SCALANATIVE_RIFT_SMALL_SLAB_SIZE);
}

static size_t scalanative_rift_pool_resident_bytes_approx(void) {
    size_t regular = atomic_load_explicit(&scalanative_rift_pool_size,
                                          memory_order_relaxed);
    size_t small = atomic_load_explicit(&scalanative_rift_small_pool_size,
                                        memory_order_relaxed);
    return regular * SCALANATIVE_RIFT_SLAB_SIZE +
           small * scalanative_rift_small_slab_mapped_size();
}

static scalanative_rift_slab *scalanative_rift_pool_pop_from(
    _Atomic(scalanative_rift_slab *) *pool_head, _Atomic(size_t) *pool_size) {
    scalanative_rift_slab *head;
    scalanative_rift_slab *next;

    do {
        head = atomic_load_explicit(pool_head, memory_order_acquire);
        if (head == NULL) return NULL;
        next = head->next;
    } while (!atomic_compare_exchange_weak_explicit(
        pool_head, &head, next, memory_order_acq_rel, memory_order_acquire));

    atomic_fetch_sub_explicit(pool_size, 1, memory_order_relaxed);
    scalanative_rift_stats_add(
        &scalanative_rift_stats_pool_reuse_total_value, 1);
    head->next = NULL;
    return head;
}

static scalanative_rift_slab *scalanative_rift_pool_pop(void) {
    return scalanative_rift_pool_pop_from(&scalanative_rift_pool_head,
                                          &scalanative_rift_pool_size);
}

static scalanative_rift_slab *scalanative_rift_small_pool_pop(void) {
    return scalanative_rift_pool_pop_from(&scalanative_rift_small_pool_head,
                                          &scalanative_rift_small_pool_size);
}

static void scalanative_rift_pool_push_to(
    _Atomic(scalanative_rift_slab *) *pool_head, _Atomic(size_t) *pool_size,
    scalanative_rift_slab *slab) {
    scalanative_rift_slab *head;

    if (slab == NULL) return;

    do {
        head = atomic_load_explicit(pool_head, memory_order_acquire);
        slab->next = head;
    } while (!atomic_compare_exchange_weak_explicit(
        pool_head, &head, slab, memory_order_acq_rel, memory_order_acquire));

    atomic_fetch_add_explicit(pool_size, 1, memory_order_relaxed);
}

static void scalanative_rift_pool_push(scalanative_rift_slab *slab) {
    scalanative_rift_pool_push_to(&scalanative_rift_pool_head,
                                  &scalanative_rift_pool_size, slab);
}

static void scalanative_rift_pool_push_chain_to(
    _Atomic(scalanative_rift_slab *) *pool_head, _Atomic(size_t) *pool_size,
    scalanative_rift_slab *head, scalanative_rift_slab *tail, size_t count) {
    scalanative_rift_slab *old_head;

    if (head == NULL || tail == NULL || count == 0) return;

    do {
        old_head = atomic_load_explicit(pool_head, memory_order_acquire);
        tail->next = old_head;
    } while (!atomic_compare_exchange_weak_explicit(
        pool_head, &old_head, head, memory_order_acq_rel,
        memory_order_acquire));

    atomic_fetch_add_explicit(pool_size, count, memory_order_relaxed);
}

static void scalanative_rift_pool_push_chain(scalanative_rift_slab *head,
                                             scalanative_rift_slab *tail,
                                             size_t count) {
    scalanative_rift_pool_push_chain_to(&scalanative_rift_pool_head,
                                        &scalanative_rift_pool_size, head,
                                        tail, count);
}

static void scalanative_rift_small_pool_push_chain(
    scalanative_rift_slab *head, scalanative_rift_slab *tail, size_t count) {
    scalanative_rift_pool_push_chain_to(&scalanative_rift_small_pool_head,
                                        &scalanative_rift_small_pool_size, head,
                                        tail, count);
}

static void scalanative_rift_unmap_slab_chain(
    scalanative_rift_slab *head) {
    while (head != NULL) {
        scalanative_rift_slab *next = head->next;
        scalanative_rift_stats_record_munmap(head->mapped_size);
        (void)munmap(head, head->mapped_size);
        head = next;
    }
}

static void scalanative_rift_pool_push_regular_chain_capped(
    scalanative_rift_slab *head, scalanative_rift_slab *tail, size_t count,
    int small) {
    size_t slab_size = small ? scalanative_rift_small_slab_mapped_size()
                             : SCALANATIVE_RIFT_SLAB_SIZE;
    size_t pool_bytes = scalanative_rift_pool_resident_bytes_approx();
    size_t keep_count = 0;
    scalanative_rift_slab *keep_tail;
    scalanative_rift_slab *drop_head;
    size_t i;

    if (head == NULL || tail == NULL || count == 0) return;

    if (pool_bytes < SCALANATIVE_RIFT_POOL_MAX_BYTES) {
        keep_count = (SCALANATIVE_RIFT_POOL_MAX_BYTES - pool_bytes) / slab_size;
        if (keep_count > count) keep_count = count;
    }

    if (keep_count == 0) {
        scalanative_rift_unmap_slab_chain(head);
        return;
    }

    if (keep_count == count) {
        if (small) {
            scalanative_rift_small_pool_push_chain(head, tail, count);
        } else {
            scalanative_rift_pool_push_chain(head, tail, count);
        }
        return;
    }

    keep_tail = head;
    for (i = 1; i < keep_count; i++) {
        keep_tail = keep_tail->next;
    }
    drop_head = keep_tail->next;
    keep_tail->next = NULL;

    if (small) {
        scalanative_rift_small_pool_push_chain(head, keep_tail, keep_count);
    } else {
        scalanative_rift_pool_push_chain(head, keep_tail, keep_count);
    }
    scalanative_rift_unmap_slab_chain(drop_head);
}

static scalanative_rift_slab *scalanative_rift_tls_pop(void) {
    scalanative_rift_slab *slab;

    if (scalanative_rift_tls_count == 0) return NULL;
    slab = scalanative_rift_tls_cache[--scalanative_rift_tls_count];
    scalanative_rift_tls_cache[scalanative_rift_tls_count] = NULL;
    slab->next = NULL;
    scalanative_rift_stats_add(&scalanative_rift_stats_tls_reuse_total_value,
                               1);
    return slab;
}

static scalanative_rift_slab *scalanative_rift_mmap_slab_bytes(size_t bytes,
                                                               uint32_t flags,
                                                               int populate,
                                                               int use_hugepage) {
    int mmap_flags = MAP_PRIVATE | MAP_ANONYMOUS;
    void *raw = mmap(NULL, bytes, PROT_READ | PROT_WRITE,
                     mmap_flags | (populate ? MAP_POPULATE : 0), -1, 0);
    scalanative_rift_slab *slab;

    if (raw == MAP_FAILED) return NULL;

#ifdef __linux__
    if (use_hugepage) {
        (void)madvise(raw, bytes, MADV_HUGEPAGE);
    }
#else
    (void)use_hugepage;
#endif

    slab = (scalanative_rift_slab *)raw;
    slab->next = NULL;
    slab->mapped_size = bytes;
    slab->flags = flags | SCALANATIVE_RIFT_SLAB_FLAG_ZEROED;
    slab->reserved = 0;
    scalanative_rift_stats_record_mmap(bytes);
    return slab;
}

static scalanative_rift_slab *scalanative_rift_mmap_regular_slab(void) {
    return scalanative_rift_mmap_slab_bytes(SCALANATIVE_RIFT_SLAB_SIZE, 0, 1,
                                            1);
}

static scalanative_rift_slab *scalanative_rift_mmap_small_slab(void) {
    return scalanative_rift_mmap_slab_bytes(
        scalanative_rift_small_slab_mapped_size(),
        SCALANATIVE_RIFT_SLAB_FLAG_SMALL, 1, 0);
}

static scalanative_rift_slab *scalanative_rift_mmap_huge_slab(size_t need) {
    size_t bytes =
        scalanative_rift_round_up_to_pages(sizeof(scalanative_rift_slab) + need);
    return scalanative_rift_mmap_slab_bytes(bytes,
                                            SCALANATIVE_RIFT_SLAB_FLAG_HUGE, 0,
                                            0);
}

static scalanative_rift_slab *scalanative_rift_slab_acquire(void) {
    scalanative_rift_slab *slab = scalanative_rift_tls_pop();
    if (slab != NULL) return slab;

    slab = scalanative_rift_pool_pop();
    if (slab != NULL) return slab;

    return scalanative_rift_mmap_regular_slab();
}

static scalanative_rift_slab *scalanative_rift_small_slab_acquire(void) {
    scalanative_rift_slab *slab = scalanative_rift_small_pool_pop();
    if (slab != NULL) return slab;

    return scalanative_rift_mmap_small_slab();
}

static void scalanative_rift_region_append_slab(
    scalanative_rift_region *region, scalanative_rift_slab *slab) {
    slab->next = NULL;
    if (region->current != NULL) {
        region->current->next = slab;
    } else {
        region->head = slab;
    }
    region->current = slab;
    if (region->head == NULL) region->head = slab;
    region->bump = slab->data;
    region->end = slab->data + scalanative_rift_slab_usable_size(slab);
    region->slab_count++;
    region->current_slab_zeroed =
        scalanative_rift_slab_is_zeroed(slab) ? 1u : 0u;
    scalanative_rift_stats_record_active_acquire(region, slab->mapped_size);
}

static void scalanative_rift_region_flush_alloc_stats(
    scalanative_rift_region *region) {
    if (region->alloc_raw_count != 0) {
        scalanative_rift_stats_add(&scalanative_rift_stats_alloc_raw_total_value,
                                   region->alloc_raw_count);
        region->alloc_raw_count = 0;
    }
    if (region->alloc_object_count != 0) {
        scalanative_rift_stats_add(
            &scalanative_rift_stats_alloc_object_total_value,
            region->alloc_object_count);
        region->alloc_object_count = 0;
    }
    if (region->alloc_slow_count != 0) {
        scalanative_rift_stats_add(&scalanative_rift_stats_alloc_slow_total_value,
                                   region->alloc_slow_count);
        region->alloc_slow_count = 0;
    }
    if (region->alloc_raw_bytes != 0) {
        scalanative_rift_stats_add(
            &scalanative_rift_stats_alloc_raw_bytes_total_value,
            region->alloc_raw_bytes);
        scalanative_rift_stats_family_add_total(
            scalanative_rift_stats_family_alloc_raw_bytes_total_values,
            region->family, region->alloc_raw_bytes);
    }
}

static void scalanative_rift_release_slab_chain(
    scalanative_rift_slab *head, uint32_t family) {
    scalanative_rift_slab *regular_head = NULL;
    scalanative_rift_slab *regular_tail = NULL;
    scalanative_rift_slab *small_head = NULL;
    scalanative_rift_slab *small_tail = NULL;
    size_t regular_count = 0;
    size_t small_count = 0;

    while (head != NULL) {
        scalanative_rift_slab *next = head->next;

        scalanative_rift_stats_record_active_release(family,
                                                     head->mapped_size);
        if (scalanative_rift_slab_is_huge(head)) {
            scalanative_rift_stats_record_munmap(head->mapped_size);
            (void)munmap(head, head->mapped_size);
        } else if (scalanative_rift_slab_is_small(head)) {
            head->flags &= ~SCALANATIVE_RIFT_SLAB_FLAG_ZEROED;
            head->next = NULL;
            if (small_tail != NULL) {
                small_tail->next = head;
            } else {
                small_head = head;
            }
            small_tail = head;
            small_count++;
        } else {
            head->flags &= ~SCALANATIVE_RIFT_SLAB_FLAG_ZEROED;
            head->next = NULL;
            if (regular_tail != NULL) {
                regular_tail->next = head;
            } else {
                regular_head = head;
            }
            regular_tail = head;
            regular_count++;
        }

        head = next;
    }

    while (regular_head != NULL &&
           scalanative_rift_tls_count <
               SCALANATIVE_RIFT_TLS_SLAB_CACHE_MAX) {
        scalanative_rift_slab *next = regular_head->next;
        regular_head->next = NULL;
        scalanative_rift_tls_cache[scalanative_rift_tls_count++] = regular_head;
        regular_head = next;
        regular_count--;
    }

    if (regular_head != NULL) {
        scalanative_rift_pool_push_regular_chain_capped(
            regular_head, regular_tail, regular_count, 0);
    }
    if (small_head != NULL) {
        scalanative_rift_pool_push_regular_chain_capped(
            small_head, small_tail, small_count, 1);
    }
}

static void *scalanative_rift_region_alloc_slow(
    scalanative_rift_region *region, size_t size, size_t align,
    bool stats_enabled) {
    scalanative_rift_slab *slab;
    uintptr_t mask;
    uintptr_t p;
    uintptr_t n;
    uint64_t start_ns = scalanative_rift_now_ns();

    if (stats_enabled) region->alloc_slow_count++;
    align = scalanative_rift_normalize_align(align);
    if (size > SCALANATIVE_RIFT_SLAB_DATA_SIZE) {
        slab = scalanative_rift_mmap_huge_slab(size + align);
    } else {
        slab = scalanative_rift_slab_acquire();
    }

    if (slab == NULL) {
        uint64_t end_ns = scalanative_rift_now_ns();
        scalanative_rift_stats_add_duration(
            &scalanative_rift_stats_slow_alloc_ns_value, start_ns, end_ns);
        scalanative_rift_stats_add_duration(
            &scalanative_rift_stats_region_op_ns_value, start_ns, end_ns);
        return NULL;
    }

    scalanative_rift_region_append_slab(region, slab);
    mask = (uintptr_t)align - 1u;
    p = ((uintptr_t)region->bump + mask) & ~mask;
    n = p + size;
    if (n > (uintptr_t)region->end) {
        uint64_t end_ns = scalanative_rift_now_ns();
        scalanative_rift_stats_add_duration(
            &scalanative_rift_stats_slow_alloc_ns_value, start_ns, end_ns);
        scalanative_rift_stats_add_duration(
            &scalanative_rift_stats_region_op_ns_value, start_ns, end_ns);
        return NULL;
    }

    region->bump = (uint8_t *)n;
    if (stats_enabled) scalanative_rift_stats_record_alloc_bytes(region, size);
    {
        uint64_t end_ns = scalanative_rift_now_ns();
        scalanative_rift_stats_add_duration(
            &scalanative_rift_stats_slow_alloc_ns_value, start_ns, end_ns);
        scalanative_rift_stats_add_duration(
            &scalanative_rift_stats_region_op_ns_value, start_ns, end_ns);
    }
    return (void *)p;
}

void scalanative_rift_init(size_t initial_slabs) {
    size_t i;

    for (i = 0; i < initial_slabs; i++) {
        scalanative_rift_slab *slab = scalanative_rift_mmap_regular_slab();
        if (slab == NULL) break;
        scalanative_rift_pool_push(slab);
    }
}

void scalanative_rift_shutdown(void) {
    scalanative_rift_slab *head;
    scalanative_rift_slab *small_head;

    while (scalanative_rift_tls_count > 0) {
        scalanative_rift_slab *slab =
            scalanative_rift_tls_cache[--scalanative_rift_tls_count];
        scalanative_rift_tls_cache[scalanative_rift_tls_count] = NULL;
        scalanative_rift_stats_record_munmap(slab->mapped_size);
        (void)munmap(slab, slab->mapped_size);
    }

    head = atomic_exchange_explicit(&scalanative_rift_pool_head, NULL,
                                    memory_order_acq_rel);
    atomic_store_explicit(&scalanative_rift_pool_size, 0,
                          memory_order_relaxed);
    small_head = atomic_exchange_explicit(&scalanative_rift_small_pool_head,
                                          NULL, memory_order_acq_rel);
    atomic_store_explicit(&scalanative_rift_small_pool_size, 0,
                          memory_order_relaxed);

    while (head != NULL) {
        scalanative_rift_slab *next = head->next;
        scalanative_rift_stats_record_munmap(head->mapped_size);
        (void)munmap(head, head->mapped_size);
        head = next;
    }
    while (small_head != NULL) {
        scalanative_rift_slab *next = small_head->next;
        scalanative_rift_stats_record_munmap(small_head->mapped_size);
        (void)munmap(small_head, small_head->mapped_size);
        small_head = next;
    }
}

void *scalanative_rift_region_open(uint32_t kind) {
    uint64_t start_ns = scalanative_rift_now_ns();
    scalanative_rift_region *region =
        (scalanative_rift_region *)calloc(1, sizeof(*region));
    scalanative_rift_slab *slab;

    if (region == NULL) return NULL;

    if (kind == SCALANATIVE_RIFT_KIND_STREAMING) {
        slab = scalanative_rift_small_slab_acquire();
    } else {
        slab = scalanative_rift_slab_acquire();
    }
    if (slab == NULL) {
        free(region);
        return NULL;
    }

    slab->next = NULL;
    region->bump = slab->data;
    region->end = slab->data + scalanative_rift_slab_usable_size(slab);
    region->current = slab;
    region->head = slab;
    region->kind = kind;
    region->family = 0;
    region->slab_count = 1;
    region->alloc_stats_enabled =
        scalanative_rift_alloc_stats_enabled() ? 1u : 0u;
    region->current_slab_zeroed =
        scalanative_rift_slab_is_zeroed(slab) ? 1u : 0u;
    scalanative_rift_stats_record_active_acquire(region, slab->mapped_size);
    scalanative_rift_stats_add(&scalanative_rift_stats_region_open_total_value,
                               1);
    {
        uint64_t end_ns = scalanative_rift_now_ns();
        scalanative_rift_stats_add_duration(
            &scalanative_rift_stats_open_ns_value, start_ns, end_ns);
        scalanative_rift_stats_add_duration(
            &scalanative_rift_stats_region_op_ns_value, start_ns, end_ns);
    }
    return (void *)region;
}

void scalanative_rift_region_set_family(void *rawregion, uint32_t family) {
    scalanative_rift_region *region = (scalanative_rift_region *)rawregion;
    uint32_t next_family = scalanative_rift_normalize_family(family);
    uint32_t old_family;
    size_t mapped_bytes;
    size_t alloc_bytes;

    if (region == NULL) return;
    old_family = region->family;
    if (old_family == next_family) return;

    mapped_bytes = scalanative_rift_region_mapped_bytes(region);
    alloc_bytes = region->alloc_raw_bytes;

    scalanative_rift_stats_family_sub_current(
        scalanative_rift_stats_family_active_bytes_current_values, old_family,
        mapped_bytes);
    if (scalanative_rift_precise_alloc_stats_enabled()) {
        scalanative_rift_stats_family_sub_current(
            scalanative_rift_stats_family_active_alloc_bytes_current_values,
            old_family, alloc_bytes);
    }

    region->family = next_family;

    scalanative_rift_stats_family_add_current(
        scalanative_rift_stats_family_active_bytes_current_values,
        scalanative_rift_stats_family_active_bytes_peak_values, next_family,
        mapped_bytes);
    if (scalanative_rift_precise_alloc_stats_enabled()) {
        scalanative_rift_stats_family_add_current(
            scalanative_rift_stats_family_active_alloc_bytes_current_values,
            scalanative_rift_stats_family_active_alloc_bytes_peak_values,
            next_family, alloc_bytes);
    }
}

void scalanative_rift_region_close(void *rawregion) {
    scalanative_rift_region *region = (scalanative_rift_region *)rawregion;
    uint64_t start_ns;

    if (region == NULL) return;
    start_ns = scalanative_rift_now_ns();
    scalanative_rift_region_flush_alloc_stats(region);
    scalanative_rift_stats_release_alloc_bytes(region);
    scalanative_rift_release_slab_chain(region->head, region->family);
    free(region);
    scalanative_rift_stats_add(&scalanative_rift_stats_region_close_total_value,
                               1);
    {
        uint64_t end_ns = scalanative_rift_now_ns();
        scalanative_rift_stats_add_duration(
            &scalanative_rift_stats_close_ns_value, start_ns, end_ns);
        scalanative_rift_stats_add_duration(
            &scalanative_rift_stats_region_op_ns_value, start_ns, end_ns);
    }
}

void scalanative_rift_region_reset(void *rawregion) {
    scalanative_rift_region *region = (scalanative_rift_region *)rawregion;
    scalanative_rift_slab *first;
    scalanative_rift_slab *rest;
    uint64_t start_ns;

    if (region == NULL || region->head == NULL) return;

    start_ns = scalanative_rift_now_ns();
    scalanative_rift_region_flush_alloc_stats(region);
    scalanative_rift_stats_release_alloc_bytes(region);
    first = region->head;
    rest = first->next;
    first->next = NULL;

    scalanative_rift_release_slab_chain(rest, region->family);

    first->flags &= ~SCALANATIVE_RIFT_SLAB_FLAG_ZEROED;

    region->bump = first->data;
    region->end = first->data + scalanative_rift_slab_usable_size(first);
    region->current = first;
    region->slab_count = 1;
    region->current_slab_zeroed = 0u;
    scalanative_rift_stats_add(&scalanative_rift_stats_region_reset_total_value,
                               1);
    {
        uint64_t end_ns = scalanative_rift_now_ns();
        scalanative_rift_stats_add_duration(
            &scalanative_rift_stats_reset_ns_value, start_ns, end_ns);
        scalanative_rift_stats_add_duration(
            &scalanative_rift_stats_region_op_ns_value, start_ns, end_ns);
    }
}

static inline void *scalanative_rift_region_alloc_raw_impl(
    scalanative_rift_region *region, size_t size, size_t align,
    bool stats_enabled) {
    uintptr_t mask;
    uintptr_t p;
    uintptr_t n;

    if (region == NULL) return NULL;
    if (stats_enabled) region->alloc_raw_count++;

    align = scalanative_rift_normalize_align(align);
    mask = (uintptr_t)align - 1u;
    p = ((uintptr_t)region->bump + mask) & ~mask;
    n = p + size;

    if (__builtin_expect(n > (uintptr_t)region->end, 0)) {
        return scalanative_rift_region_alloc_slow(
            region, size, align, stats_enabled);
    }

    region->bump = (uint8_t *)n;
    if (stats_enabled) scalanative_rift_stats_record_alloc_bytes(region, size);
    return (void *)p;
}

void *scalanative_rift_region_alloc_raw(void *rawregion, size_t size,
                                        size_t align) {
    scalanative_rift_region *region = (scalanative_rift_region *)rawregion;
    if (region == NULL) return NULL;
    return scalanative_rift_region_alloc_raw_impl(
        region, size, align, region->alloc_stats_enabled != 0u);
}

static inline void *scalanative_rift_region_alloc_object_fast(
    scalanative_rift_region *region, size_t size, bool stats_enabled) {
    const uintptr_t mask = (uintptr_t)sizeof(void *) - 1u;
    uintptr_t p;
    uintptr_t n;

    if (stats_enabled) region->alloc_raw_count++;

    p = ((uintptr_t)region->bump + mask) & ~mask;
    n = p + size;

    if (__builtin_expect(n > (uintptr_t)region->end, 0)) {
        return scalanative_rift_region_alloc_slow(
            region, size, sizeof(void *), stats_enabled);
    }

    region->bump = (uint8_t *)n;
    if (stats_enabled) scalanative_rift_stats_record_alloc_bytes(region, size);
    return (void *)p;
}

void *scalanative_rift_region_alloc(void *rawregion, void *info, size_t size) {
    scalanative_rift_region *region = (scalanative_rift_region *)rawregion;
    bool stats_enabled;
    void *current;

    if (region == NULL) return NULL;
    stats_enabled = region->alloc_stats_enabled != 0u;

    current = scalanative_rift_region_alloc_object_fast(
        region, size, stats_enabled);
    if (current == NULL) return NULL;

    if (stats_enabled) region->alloc_object_count++;
    if (!region->current_slab_zeroed) {
        memset(current, 0, size);
    }
    *((void **)current) = info;
    return current;
}

size_t scalanative_rift_pool_slab_count(void) {
    size_t regular = atomic_load_explicit(&scalanative_rift_pool_size,
                                          memory_order_relaxed);
    size_t small = atomic_load_explicit(&scalanative_rift_small_pool_size,
                                        memory_order_relaxed);
    return regular + small;
}

size_t scalanative_rift_pool_resident_bytes(void) {
    return scalanative_rift_pool_resident_bytes_approx();
}

void scalanative_rift_stats_reset(void) {
    size_t i;

    atomic_store_explicit(&scalanative_rift_stats_region_open_total_value, 0,
                          memory_order_relaxed);
    atomic_store_explicit(&scalanative_rift_stats_region_close_total_value, 0,
                          memory_order_relaxed);
    atomic_store_explicit(&scalanative_rift_stats_region_reset_total_value, 0,
                          memory_order_relaxed);
    atomic_store_explicit(&scalanative_rift_stats_alloc_raw_total_value, 0,
                          memory_order_relaxed);
    atomic_store_explicit(&scalanative_rift_stats_alloc_raw_bytes_total_value, 0,
                          memory_order_relaxed);
    atomic_store_explicit(&scalanative_rift_stats_alloc_object_total_value, 0,
                          memory_order_relaxed);
    atomic_store_explicit(&scalanative_rift_stats_alloc_slow_total_value, 0,
                          memory_order_relaxed);
    atomic_store_explicit(&scalanative_rift_stats_mmap_slab_total_value, 0,
                          memory_order_relaxed);
    atomic_store_explicit(&scalanative_rift_stats_mmap_bytes_total_value, 0,
                          memory_order_relaxed);
    atomic_store_explicit(
        &scalanative_rift_stats_mmap_slab_peak_value,
        atomic_load_explicit(&scalanative_rift_stats_mmap_slab_current_value,
                             memory_order_relaxed),
        memory_order_relaxed);
    atomic_store_explicit(
        &scalanative_rift_stats_mmap_bytes_peak_value,
        atomic_load_explicit(&scalanative_rift_stats_mmap_bytes_current_value,
                             memory_order_relaxed),
        memory_order_relaxed);
    atomic_store_explicit(
        &scalanative_rift_stats_active_slab_peak_value,
        atomic_load_explicit(&scalanative_rift_stats_active_slab_current_value,
                             memory_order_relaxed),
        memory_order_relaxed);
    atomic_store_explicit(
        &scalanative_rift_stats_active_bytes_peak_value,
        atomic_load_explicit(&scalanative_rift_stats_active_bytes_current_value,
                             memory_order_relaxed),
        memory_order_relaxed);
    atomic_store_explicit(
        &scalanative_rift_stats_active_alloc_bytes_peak_value,
        atomic_load_explicit(
            &scalanative_rift_stats_active_alloc_bytes_current_value,
            memory_order_relaxed),
        memory_order_relaxed);
    atomic_store_explicit(&scalanative_rift_stats_tls_reuse_total_value, 0,
                          memory_order_relaxed);
    atomic_store_explicit(&scalanative_rift_stats_pool_reuse_total_value, 0,
                          memory_order_relaxed);
    atomic_store_explicit(&scalanative_rift_stats_region_op_ns_value, 0,
                          memory_order_relaxed);
    atomic_store_explicit(&scalanative_rift_stats_open_ns_value, 0,
                          memory_order_relaxed);
    atomic_store_explicit(&scalanative_rift_stats_close_ns_value, 0,
                          memory_order_relaxed);
    atomic_store_explicit(&scalanative_rift_stats_reset_ns_value, 0,
                          memory_order_relaxed);
    atomic_store_explicit(&scalanative_rift_stats_slow_alloc_ns_value, 0,
                          memory_order_relaxed);

    for (i = 0; i < SCALANATIVE_RIFT_FAMILY_MAX; i++) {
        atomic_store_explicit(
            &scalanative_rift_stats_family_alloc_raw_bytes_total_values[i], 0,
            memory_order_relaxed);
        atomic_store_explicit(
            &scalanative_rift_stats_family_active_bytes_peak_values[i],
            atomic_load_explicit(
                &scalanative_rift_stats_family_active_bytes_current_values[i],
                memory_order_relaxed),
            memory_order_relaxed);
        atomic_store_explicit(
            &scalanative_rift_stats_family_active_alloc_bytes_peak_values[i],
            atomic_load_explicit(
                &scalanative_rift_stats_family_active_alloc_bytes_current_values
                     [i],
                memory_order_relaxed),
            memory_order_relaxed);
    }
}

static size_t scalanative_rift_stats_family_get(
    _Atomic(size_t) *counters, uint32_t family) {
    if (!scalanative_rift_family_is_valid(family)) return 0;
    return atomic_load_explicit(&counters[family], memory_order_relaxed);
}

size_t scalanative_rift_stats_family_alloc_raw_bytes_total(uint32_t family) {
    return scalanative_rift_stats_family_get(
        scalanative_rift_stats_family_alloc_raw_bytes_total_values, family);
}

size_t scalanative_rift_stats_family_active_bytes_current(uint32_t family) {
    return scalanative_rift_stats_family_get(
        scalanative_rift_stats_family_active_bytes_current_values, family);
}

size_t scalanative_rift_stats_family_active_bytes_peak(uint32_t family) {
    return scalanative_rift_stats_family_get(
        scalanative_rift_stats_family_active_bytes_peak_values, family);
}

size_t scalanative_rift_stats_family_active_alloc_bytes_current(
    uint32_t family) {
    return scalanative_rift_stats_family_get(
        scalanative_rift_stats_family_active_alloc_bytes_current_values,
        family);
}

size_t scalanative_rift_stats_family_active_alloc_bytes_peak(uint32_t family) {
    return scalanative_rift_stats_family_get(
        scalanative_rift_stats_family_active_alloc_bytes_peak_values, family);
}

#define SCALANATIVE_RIFT_STATS_GETTER(name)                                    \
    size_t scalanative_rift_stats_##name(void) {                               \
        return atomic_load_explicit(                                           \
            &scalanative_rift_stats_##name##_value, memory_order_relaxed);     \
    }

SCALANATIVE_RIFT_STATS_GETTER(region_open_total)
SCALANATIVE_RIFT_STATS_GETTER(region_close_total)
SCALANATIVE_RIFT_STATS_GETTER(region_reset_total)
SCALANATIVE_RIFT_STATS_GETTER(alloc_raw_total)
SCALANATIVE_RIFT_STATS_GETTER(alloc_raw_bytes_total)
SCALANATIVE_RIFT_STATS_GETTER(alloc_object_total)
SCALANATIVE_RIFT_STATS_GETTER(alloc_slow_total)
SCALANATIVE_RIFT_STATS_GETTER(mmap_slab_total)
SCALANATIVE_RIFT_STATS_GETTER(mmap_bytes_total)
SCALANATIVE_RIFT_STATS_GETTER(mmap_slab_current)
SCALANATIVE_RIFT_STATS_GETTER(mmap_slab_peak)
SCALANATIVE_RIFT_STATS_GETTER(mmap_bytes_current)
SCALANATIVE_RIFT_STATS_GETTER(mmap_bytes_peak)
SCALANATIVE_RIFT_STATS_GETTER(active_slab_current)
SCALANATIVE_RIFT_STATS_GETTER(active_slab_peak)
SCALANATIVE_RIFT_STATS_GETTER(active_bytes_current)
SCALANATIVE_RIFT_STATS_GETTER(active_bytes_peak)
SCALANATIVE_RIFT_STATS_GETTER(active_alloc_bytes_current)
SCALANATIVE_RIFT_STATS_GETTER(active_alloc_bytes_peak)
SCALANATIVE_RIFT_STATS_GETTER(tls_reuse_total)
SCALANATIVE_RIFT_STATS_GETTER(pool_reuse_total)
SCALANATIVE_RIFT_STATS_GETTER(region_op_ns)
SCALANATIVE_RIFT_STATS_GETTER(open_ns)
SCALANATIVE_RIFT_STATS_GETTER(close_ns)
SCALANATIVE_RIFT_STATS_GETTER(reset_ns)
SCALANATIVE_RIFT_STATS_GETTER(slow_alloc_ns)

#undef SCALANATIVE_RIFT_STATS_GETTER
#endif
