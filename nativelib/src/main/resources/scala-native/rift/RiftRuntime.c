#if defined(SCALANATIVE_COMPILE_ALWAYS) || defined(__SCALANATIVE_MEMORY_RIFT)
#include "rift/RiftRuntime.h"

#include <stdatomic.h>
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
    uint32_t kind;
    uint32_t slab_count;
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
static _Atomic(size_t) scalanative_rift_stats_alloc_object_total_value = 0;
static _Atomic(size_t) scalanative_rift_stats_alloc_slow_total_value = 0;
static _Atomic(size_t) scalanative_rift_stats_mmap_slab_total_value = 0;
static _Atomic(size_t) scalanative_rift_stats_mmap_bytes_total_value = 0;
static _Atomic(size_t) scalanative_rift_stats_tls_reuse_total_value = 0;
static _Atomic(size_t) scalanative_rift_stats_pool_reuse_total_value = 0;
static _Atomic(size_t) scalanative_rift_stats_region_op_ns_value = 0;
static _Atomic(size_t) scalanative_rift_stats_open_ns_value = 0;
static _Atomic(size_t) scalanative_rift_stats_close_ns_value = 0;
static _Atomic(size_t) scalanative_rift_stats_reset_ns_value = 0;
static _Atomic(size_t) scalanative_rift_stats_slow_alloc_ns_value = 0;

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
    scalanative_rift_stats_add(&scalanative_rift_stats_mmap_slab_total_value,
                               1);
    scalanative_rift_stats_add(&scalanative_rift_stats_mmap_bytes_total_value,
                               bytes);
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
}

static void scalanative_rift_release_slab_chain(
    scalanative_rift_slab *head) {
    scalanative_rift_slab *regular_head = NULL;
    scalanative_rift_slab *regular_tail = NULL;
    scalanative_rift_slab *small_head = NULL;
    scalanative_rift_slab *small_tail = NULL;
    size_t regular_count = 0;
    size_t small_count = 0;

    while (head != NULL) {
        scalanative_rift_slab *next = head->next;

        if (scalanative_rift_slab_is_huge(head)) {
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
    scalanative_rift_region *region, size_t size, size_t align) {
    scalanative_rift_slab *slab;
    uintptr_t mask;
    uintptr_t p;
    uintptr_t n;
    uint64_t start_ns = scalanative_rift_now_ns();

    region->alloc_slow_count++;
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
        (void)munmap(head, head->mapped_size);
        head = next;
    }
    while (small_head != NULL) {
        scalanative_rift_slab *next = small_head->next;
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
    region->slab_count = 1;
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

void scalanative_rift_region_close(void *rawregion) {
    scalanative_rift_region *region = (scalanative_rift_region *)rawregion;
    uint64_t start_ns;

    if (region == NULL) return;
    start_ns = scalanative_rift_now_ns();
    scalanative_rift_region_flush_alloc_stats(region);
    scalanative_rift_release_slab_chain(region->head);
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
    first = region->head;
    rest = first->next;
    first->next = NULL;

    scalanative_rift_release_slab_chain(rest);

    first->flags &= ~SCALANATIVE_RIFT_SLAB_FLAG_ZEROED;

    region->bump = first->data;
    region->end = first->data + scalanative_rift_slab_usable_size(first);
    region->current = first;
    region->slab_count = 1;
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

void *scalanative_rift_region_alloc_raw(void *rawregion, size_t size,
                                        size_t align) {
    scalanative_rift_region *region = (scalanative_rift_region *)rawregion;
    uintptr_t mask;
    uintptr_t p;
    uintptr_t n;

    if (region == NULL) return NULL;
    region->alloc_raw_count++;

    align = scalanative_rift_normalize_align(align);
    mask = (uintptr_t)align - 1u;
    p = ((uintptr_t)region->bump + mask) & ~mask;
    n = p + size;

    if (__builtin_expect(n > (uintptr_t)region->end, 0)) {
        return scalanative_rift_region_alloc_slow(region, size, align);
    }

    region->bump = (uint8_t *)n;
    return (void *)p;
}

void *scalanative_rift_region_alloc(void *rawregion, void *info, size_t size) {
    scalanative_rift_region *region = (scalanative_rift_region *)rawregion;
    void *current = scalanative_rift_region_alloc_raw(
        rawregion, size, sizeof(void *));
    if (current == NULL) return NULL;

    region->alloc_object_count++;
    if (region == NULL || region->current == NULL ||
        !scalanative_rift_slab_is_zeroed(region->current)) {
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
    atomic_store_explicit(&scalanative_rift_stats_region_open_total_value, 0,
                          memory_order_relaxed);
    atomic_store_explicit(&scalanative_rift_stats_region_close_total_value, 0,
                          memory_order_relaxed);
    atomic_store_explicit(&scalanative_rift_stats_region_reset_total_value, 0,
                          memory_order_relaxed);
    atomic_store_explicit(&scalanative_rift_stats_alloc_raw_total_value, 0,
                          memory_order_relaxed);
    atomic_store_explicit(&scalanative_rift_stats_alloc_object_total_value, 0,
                          memory_order_relaxed);
    atomic_store_explicit(&scalanative_rift_stats_alloc_slow_total_value, 0,
                          memory_order_relaxed);
    atomic_store_explicit(&scalanative_rift_stats_mmap_slab_total_value, 0,
                          memory_order_relaxed);
    atomic_store_explicit(&scalanative_rift_stats_mmap_bytes_total_value, 0,
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
SCALANATIVE_RIFT_STATS_GETTER(alloc_object_total)
SCALANATIVE_RIFT_STATS_GETTER(alloc_slow_total)
SCALANATIVE_RIFT_STATS_GETTER(mmap_slab_total)
SCALANATIVE_RIFT_STATS_GETTER(mmap_bytes_total)
SCALANATIVE_RIFT_STATS_GETTER(tls_reuse_total)
SCALANATIVE_RIFT_STATS_GETTER(pool_reuse_total)
SCALANATIVE_RIFT_STATS_GETTER(region_op_ns)
SCALANATIVE_RIFT_STATS_GETTER(open_ns)
SCALANATIVE_RIFT_STATS_GETTER(close_ns)
SCALANATIVE_RIFT_STATS_GETTER(reset_ns)
SCALANATIVE_RIFT_STATS_GETTER(slow_alloc_ns)

#undef SCALANATIVE_RIFT_STATS_GETTER
#endif
