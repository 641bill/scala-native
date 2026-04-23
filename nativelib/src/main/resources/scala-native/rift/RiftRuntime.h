#ifndef SCALANATIVE_RIFT_RUNTIME_H
#define SCALANATIVE_RIFT_RUNTIME_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SCALANATIVE_RIFT_SLAB_SIZE (32 * 1024)
#define SCALANATIVE_RIFT_DEFAULT_ALIGN 16
#define SCALANATIVE_RIFT_TLS_SLAB_CACHE_MAX 8

typedef enum {
    SCALANATIVE_RIFT_KIND_HPZONE = 0,
    SCALANATIVE_RIFT_KIND_SCOPED = 1,
    SCALANATIVE_RIFT_KIND_STREAMING = 2
} scalanative_rift_kind;

void scalanative_rift_init(size_t initial_slabs);
void scalanative_rift_shutdown(void);

void *scalanative_rift_region_open(uint32_t kind);
void scalanative_rift_region_close(void *region);
void scalanative_rift_region_reset(void *region);

void *scalanative_rift_region_alloc_raw(void *region, size_t size,
                                        size_t align);
void *scalanative_rift_region_alloc(void *region, void *info, size_t size);

size_t scalanative_rift_pool_slab_count(void);
size_t scalanative_rift_pool_resident_bytes(void);

#ifdef __cplusplus
}
#endif

#endif
