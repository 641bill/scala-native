#include <stddef.h>
#include <stdio.h>
#include "../mmtk-scala-native/scala-native/mmtk.h"
#include "CommonConstants.h"
#include "GCTypes.h"

static MMTk_Mutator global_mutator;
static const size_t HEAP_SIZE = 128 * 1024 * 1024; // Example heap size: 128MB

void scalanative_init() {
    // Initialize the MMTk instance with the specified heap size
    mmtk_init(HEAP_SIZE);

    // Bind the global mutator
    global_mutator = mmtk_bind_mutator(NULL);
}

INLINE void *scalanative_alloc(void *info, size_t size) {
    size = MathUtils_RoundToNextMultiple(size, ALLOCATION_ALIGNMENT);
    // Allocate memory using MMTk's NoGC allocator
    const ssize_t offset = 0;
    const int allocator = 0; // Allocator ID for NoGC allocator
    void *allocated_memory = mmtk_alloc(global_mutator, size, ALLOCATION_ALIGNMENT, offset, allocator);
    *((void **)allocated_memory) = info;
    // Perform any necessary post-allocation tasks, such as setting object metadata

    return allocated_memory;
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

size_t scalanative_free_size() {
    // With NoGC, there is no concept of free memory. Returning 0 or an appropriate value.
    return 0;
}

void scalanative_exit() {
    // Perform cleanup, if necessary
}
