#include <stddef.h>
#include <stdio.h>
#include "mmtk.h"
#include "CommonConstants.h"
#include "GCTypes.h"
#include "utils/MathUtils.h"
#include "ScalaNativeGC.h"
#include "State.h"
#include "Constants.h"
#include "Settings.h"
#include "WeakRefStack.h"
#include "MutatorThread.h"

#ifdef SCALANATIVE_MULTITHREADING_ENABLED
#include "Synchronizer.h"
#endif

static __thread MMTk_Mutator mutator = NULL;
pthread_mutex_t mutator_mutex;

pthread_t main_thread_id;

static const size_t HEAP_SIZE = 128 * 1024 * 1024; // Example heap size: 128MB

enum Allocator {
  AllocatorDefault = 0,
  AllocatorImmortal = 1,
  AllocatorLos = 2,
  AllocatorCode = 3,
  AllocatorReadOnly = 4,
};

struct RustDynPtr {
  void* data;
  void* vtable;
};

struct BumpAllocator {
    // To mirror the one in MMTk for each thread
    void* tls;
    void* cursor;
    void* limit;
    struct RustDynPtr space;
    struct RustDynPtr plan;
};

// These constants should match the constants defind in mmtk::util::alloc::allocators
const int MAX_BUMP_ALLOCATORS = 6;
const int MAX_LARGE_OBJECT_ALLOCATORS = 2;
const int MAX_MALLOC_ALLOCATORS = 1;
const int MAX_IMMIX_ALLOCATORS = 1;
const int MAX_FREE_LIST_ALLOCATORS = 2;
const int MAX_MARK_COMPACT_ALLOCATORS = 1;

static __thread struct BumpAllocator* bump_allocator = NULL;

// Stack boottom of the main thread
extern word_t **__stack_bottom;

void scalanative_afterexit() { Stats_OnExit(heap.stats); }

void scalanative_init() {
    // Initialize the MMTk instance with the specified heap size
    mmtk_init(HEAP_SIZE);
    Heap_Init(&heap, Settings_MinHeapSize(), Settings_MaxHeapSize());
    Stack_Init(&stack, INITIAL_STACK_SIZE);
    Stack_Init(&weakRefStack, INITIAL_STACK_SIZE);
    main_thread_id = pthread_self();

#ifdef SCALANATIVE_MULTITHREADING_ENABLED
    Synchronizer_init();
#endif
    MutatorThreads_init();
    MutatorThread_init(__stack_bottom);
    // printf("current mutator thread is %p\n", currentMutatorThread);
    mutator = mmtk_bind_mutator(currentMutatorThread);
    bump_allocator = (struct BumpAllocator *) &mutator;

    // printf("%lu bump allocator %p initialized with cursor at %p, and limit at %p\n", pthread_self(), bump_allocator, bump_allocator->cursor, bump_allocator->limit);
    
    atexit(scalanative_afterexit);
}

INLINE void *scalanative_alloc(void *info, size_t size) {
    size = MathUtils_RoundToNextMultiple(size, ALLOCATION_ALIGNMENT);

    const ssize_t offset = 0;
    const int allocator = 0;
    void *allocated_memory = NULL;
    
    if (pthread_equal(pthread_self(), main_thread_id)) {
        allocated_memory = mmtk_alloc(mutator, size, ALLOCATION_ALIGNMENT, offset, allocator);
        // printf("%lu GLOBAL %p: Slow path allocated memory by %p, is at %p, cursor at %p, and limit at %p\n", pthread_self(), mutator, bump_allocator, allocated_memory, bump_allocator->cursor, bump_allocator->limit);
    } else {
        allocated_memory = mmtk_alloc(mutator, size, ALLOCATION_ALIGNMENT, offset, allocator);
        // printf("%lu LOCAL %p: Slow path allocated memory by %p, is at %p, cursor at %p, and limit at %p\n", pthread_self(), mutator, bump_allocator, allocated_memory, bump_allocator->cursor, bump_allocator->limit);
    }

    *((void **)allocated_memory) = info;

    return allocated_memory;
}

INLINE void *scalanative_alloc_fast_path(void *info, size_t size) {
    size = MathUtils_RoundToNextMultiple(size, ALLOCATION_ALIGNMENT);
    if (bump_allocator->cursor == NULL || bump_allocator->limit == NULL) {
        // printf("%lu Bump allocator %p not initialized, go to slow path\n", pthread_self(), bump_allocator);
        return scalanative_alloc(info, size);
    }

    // printf("%lu %p Cursor is at %p, Size is %lx, Limit is at %p\n", pthread_self(), bump_allocator, bump_allocator->cursor, size, bump_allocator->limit);

    if ((uintptr_t)bump_allocator->cursor + size <= (uintptr_t)bump_allocator->limit) {
        void* allocated_memory = bump_allocator->cursor;
        // printf("Allocated memory is at %p\n", allocated_memory);
        bump_allocator->cursor = (void*)((uintptr_t)bump_allocator->cursor + size);
        // printf("Cursor is now at %p\n", bump_allocator->cursor);
        *((void **)allocated_memory) = info;
        return allocated_memory;
    }

    // If we reach here, the allocation request is larger than the remaining space.
    // Fall back to the slower allocation path.
    // printf("%lu Falling back to slow path\n", pthread_self());
    return scalanative_alloc(info, size);
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
    // NoGC does not collect memory, so this function is empty
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
    // printf("current mutator thread is %p\n", currentMutatorThread);
    mutator = mmtk_bind_mutator(currentMutatorThread);
    bump_allocator = (struct BumpAllocator *) &mutator;
    // printf("%lu bump allocator to local mutator to %p initialized with cursor at %p, and limit at %p\n", pthread_self(), bump_allocator, bump_allocator->cursor, bump_allocator->limit);
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
