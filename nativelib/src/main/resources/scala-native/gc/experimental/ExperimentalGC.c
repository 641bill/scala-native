#if defined(SCALANATIVE_GC_EXPERIMENTAL)

#include <stddef.h>
#include <stdio.h>
#include "immix_commix/CommonConstants.h"
#include "shared/GCTypes.h"
#include "immix_commix/utils/MathUtils.h"
#include "shared/ScalaNativeGC.h"
#include "State.h"
#include "experimental/Constants.h"
#include "Settings.h"
#include "WeakRefStack.h"
#include "shared/GCScalaNative.h"
#include "immix_commix/GCRoots.h"
#include "shared/Parsing.h"
#include "MutatorThread.h"
#include "MMTkMutator.hpp"
#include "MMTkUpcalls.h"
#include "mmtk.h"

#ifdef SCALANATIVE_MULTITHREADING_ENABLED
#include "Synchronizer.h"
#endif

// Stack boottom of the main thread
extern word_t **__stack_bottom;

void scalanative_afterexit() { Stats_OnExit(heap.stats); }

void scalanative_init() {
    // Initialize the MMTk instance with the specified heap size
    Heap_Init(&heap, Settings_MinHeapSize(), Settings_MinHeapSize());

    max_non_los_default_alloc_bytes = get_max_non_los_default_alloc_bytes();
    mmtk_vo_bit_log_region_size = get_vo_bit_log_region_size();
    mmtk_vo_bit_base_addr = get_vo_bit_base();
    immix_bump_ptr_offset = get_immix_bump_ptr_offset();
    
    scalanative_gc_init(&mmtk_upcalls);
    mmtk_init_binding(&mmtk_upcalls);
    Stack_Init(&stack, INITIAL_STACK_SIZE);
    Stack_Init(&weakRefStack, INITIAL_STACK_SIZE);

#ifdef SCALANATIVE_MULTITHREADING_ENABLED
    Synchronizer_init();
#endif
    MutatorThreads_init();
    MutatorThread_init(__stack_bottom);

    currentMutatorThread->third_party_heap_collector = NULL;
    currentMutatorThread->mutatorContext = (MMTkMutatorContext *)malloc(sizeof(MMTkMutatorContext));
    *currentMutatorThread->mutatorContext = MMTkMutatorContext_bind(currentMutatorThread);
    
    immix_bump_pointer = (BumpPointer *)((char *)currentMutatorThread->mutatorContext + immix_bump_ptr_offset);
    currentMutatorThread->mutator_local = (void *) immix_bump_pointer;

    mmtk_initialize_collection(currentMutatorThread);
    
    atexit(scalanative_afterexit);
}

INLINE void *scalanative_alloc_slow_path(void *info, size_t size) {
    const ssize_t offset = 0;
    const int mmtk_allocator = 0;
    HeapWord* alloc = MMTkMutatorContext_alloc(currentMutatorThread->mutatorContext, size, mmtk_allocator);

    Object *object = (Object *)alloc;
    __builtin_prefetch(object + 36, 0, 3);

    *alloc = info;

    return (void *)alloc;
}

#define MMTK_USE_POST_ALLOC_FAST_PATH true
#define MMTK_VO_BIT_SET_NON_ATOMIC true

INLINE void scalanative_post_alloc(void* alloc, size_t size) {
    if (MMTK_USE_POST_ALLOC_FAST_PATH) {
        uintptr_t obj_addr = (uintptr_t)alloc;
        uintptr_t region_offset = obj_addr >> mmtk_vo_bit_log_region_size;
        uintptr_t byte_offset = region_offset / 8;
        uintptr_t bit_offset = region_offset % 8;
        uintptr_t meta_byte_address = mmtk_vo_bit_base_addr + byte_offset;
        uint8_t byte = 1 << bit_offset;
        if (MMTK_VO_BIT_SET_NON_ATOMIC) {
            uint8_t *meta_byte_ptr = (uint8_t *)meta_byte_address;
            *meta_byte_ptr |= byte;
        } else {
            volatile _Atomic uint8_t *meta_byte_ptr = (volatile _Atomic uint8_t*)meta_byte_address;
            // relaxed: We don't use VO bits for synchronisation during mutator phase.
            // When GC is triggered, the handshake between GC and mutator provides synchronization.
            atomic_fetch_or_explicit(meta_byte_ptr, byte, memory_order_relaxed);
        }
    } else {
        mmtk_post_alloc(currentMutatorThread->mutatorContext, alloc, size, 0);
    }
}

INLINE void *scalanative_alloc_fast_path(void *info, size_t size) {
    BumpPointer* local = (BumpPointer *)(currentMutatorThread->mutator_local);
    uintptr_t cursor = local->cursor;
    uintptr_t limit = local->limit;

    void* alloc = (void *)cursor;
    uintptr_t new_cursor = cursor + size;

    // Note: If the selected plan is not Immix, then both the cursor and the limit will always be 0
    // In that case this function will try the slow path.
    if (new_cursor <= limit) {
        immix_bump_pointer->cursor = new_cursor;
        scalanative_post_alloc(alloc, size);
        return alloc;
    }

    return NULL;
}

INLINE void *scalanative_alloc(void *info, size_t size) {
    size = MathUtils_RoundToNextMultiple(size, ALLOCATION_ALIGNMENT);
    if (size > max_non_los_default_alloc_bytes) {
        return scalanative_alloc_slow_path(info, size);
    }
    HeapWord* alloc = (HeapWord *)scalanative_alloc_fast_path(info, size);

    if (alloc) {
        Object *object = (Object *)alloc;
        __builtin_prefetch(object + 36, 0, 3);

        *alloc = info;
        return alloc;
    }

    // If we reach here, the allocation request is larger than the remaining space.
    // Fall back to the slower allocation path.
    return scalanative_alloc_slow_path(info, size);
}

INLINE void *scalanative_alloc_small(void *info, size_t size) {
    return scalanative_alloc(info, size);
}

INLINE void *scalanative_alloc_large(void *info, size_t size) {
    return scalanative_alloc(info, size);
}

INLINE void *scalanative_alloc_atomic(void *info, size_t size) {
    return scalanative_alloc(info, size);
}

void scalanative_collect() {
}

INLINE void scalanative_register_weak_reference_handler(void *handler) {
    mmtk_weak_ref_stack_set_handler(handler);
}

/* Get the minimum heap size */
/* If the user has set a minimum heap size using the GC_INITIAL_HEAP_SIZE
 * environment variable, */
/* then this size will be returned. */
/* Otherwise, the default minimum heap size will be returned.*/
size_t scalanative_get_init_heapsize() { return Settings_MinHeapSize(); }

/* Get the maximum heap size */
/* If the user has set a maximum heap size using the GC_MAXIMUM_HEAP_SIZE
 * environment variable,*/
/* then this size will be returned.*/
/* Otherwise, the total size of the physical memory (guarded) will be returned*/
size_t scalanative_get_max_heapsize() {
    return Parse_Env_Or_Default("GC_MAXIMUM_HEAP_SIZE", Settings_MaxHeapSize() - mmtk_get_bytes_in_page());
}

void scalanative_harness_begin(void* _) {
    mmtk_harness_begin(currentMutatorThread);
}

void scalanative_harness_end() {
    mmtk_harness_end();
}

#ifdef SCALANATIVE_MULTITHREADING_ENABLED
typedef void *RoutineArgs;
typedef struct {
    ThreadStartRoutine fn;
    RoutineArgs args;
} WrappedFunctionCallArgs;

static ThreadRoutineReturnType ProxyThreadStartRoutine(void *args) {
    WrappedFunctionCallArgs *wrapped = (WrappedFunctionCallArgs *)args;
    ThreadStartRoutine originalFn = wrapped->fn;
    RoutineArgs originalArgs = wrapped->args;
    int stackBottom = 0;

    free(args);
    MutatorThread_init((Field_t *)&stackBottom);

    currentMutatorThread->third_party_heap_collector = NULL;
    currentMutatorThread->mutatorContext = (MMTkMutatorContext *)malloc(sizeof(MMTkMutatorContext));
    *currentMutatorThread->mutatorContext = MMTkMutatorContext_bind(currentMutatorThread);
    
    immix_bump_pointer = (BumpPointer *)((char *)currentMutatorThread->mutatorContext + immix_bump_ptr_offset);
    currentMutatorThread->mutator_local = (void *) immix_bump_pointer;

    originalFn(originalArgs);
    MutatorThread_delete(currentMutatorThread);
    return (ThreadRoutineReturnType)0;
}

int scalanative_pthread_create(pthread_t *thread, pthread_attr_t *attr,
                               ThreadStartRoutine routine, RoutineArgs args) {
    WrappedFunctionCallArgs *proxyArgs =
            (WrappedFunctionCallArgs *)malloc(sizeof(WrappedFunctionCallArgs));
    proxyArgs->fn = routine;
    proxyArgs->args = args;
    printf("Creating thread %p\n", thread);
    return pthread_create(thread, attr,
                          (ThreadStartRoutine)&ProxyThreadStartRoutine,
                          (RoutineArgs)proxyArgs);
}
#endif

void scalanative_gc_set_mutator_thread_state(MutatorThreadState state) {
    MutatorThread_switchState(currentMutatorThread, state);
}
void scalanative_gc_safepoint_poll() {
    void *pollGC = *scalanative_gc_safepoint;
}

void scalanative_add_roots(void *addr_low, void *addr_high) {
    AddressRange range = {addr_low, addr_high};
    GC_Roots_Add(&roots, range);
}

void scalanative_remove_roots(void *addr_low, void *addr_high) {
    AddressRange range = {addr_low, addr_high};
    GC_Roots_RemoveByRange(&roots, range);
}

#endif