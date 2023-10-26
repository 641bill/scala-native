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

    mmtk_init(Settings_MinHeapSize(), Settings_MaxHeapSize() - mmtk_get_bytes_in_page());
    // mmtk_init(Settings_MinHeapSize(), Settings_MinHeapSize());
    max_non_los_default_alloc_bytes = get_max_non_los_default_alloc_bytes();

    Heap_Init(&heap, Settings_MinHeapSize(), Settings_MaxHeapSize());
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

    mmtk_initialize_collection(currentMutatorThread);
    
    atexit(scalanative_afterexit);
}

INLINE void *scalanative_alloc_slow_path(void *info, size_t size) {
    size = MathUtils_RoundToNextMultiple(size, ALLOCATION_ALIGNMENT);

    const ssize_t offset = 0;
    const int mmtk_allocator = 0;
    HeapWord* allocated_memory = MMTkMutatorContext_alloc(currentMutatorThread->mutatorContext, size, mmtk_allocator);

    Object *object = (Object *)allocated_memory;

    // __builtin_prefetch(object + 36, 0, 3);

    *((void **)allocated_memory) = info;

    // printf("Allocated object at %p, with info %p\n", allocated_memory, info);

    return (void*)allocated_memory;
}

INLINE void *scalanative_alloc(void *info, size_t size) {
    size = MathUtils_RoundToNextMultiple(size, ALLOCATION_ALIGNMENT);
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
    WeakRefStack_SetHandler(handler);
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