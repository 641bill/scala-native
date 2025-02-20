#if defined(SCALANATIVE_GC_EXPERIMENTAL)

#include "MMTkMutator.hpp"
#include "immix_commix/CommonConstants.h"

size_t max_non_los_default_alloc_bytes = 0;
const int HeapWordSize = sizeof(HeapWord);

MMTkMutatorContext MMTkMutatorContext_bind(void* current) {
  if (FREE_LIST_ALLOCATOR_SIZE != sizeof(struct FreeListAllocator)) {
    printf("ERROR: Unmatched free list allocator size: rs=%zu cpp=%zu\n", FREE_LIST_ALLOCATOR_SIZE, sizeof(struct FreeListAllocator));
  }
  return *((MMTkMutatorContext*) mmtk_bind_mutator((void*) current));
}

HeapWord* MMTkMutatorContext_alloc(MMTkMutatorContext* context, size_t bytes, enum MMTkAllocator allocator) {
  // All allocations with size larger than max non-los bytes will get to this slowpath here.
  // We will use LOS for those.
  assert(max_non_los_default_alloc_bytes != 0 && "max_non_los_default_alloc_bytes hasn't been initialized");
  if (bytes >= max_non_los_default_alloc_bytes) {
    allocator = AllocatorLos;
  }

  // FIXME: Proper use of slow-path api
  HeapWord* o = (HeapWord*) mmtk_alloc((MMTk_Mutator) context, bytes, ALLOCATION_ALIGNMENT, 0, allocator);
  // Post allocation hooks. Note that we can get a nullptr from mmtk core in the case of OOM.
  // Hence, only call post allocation hooks if we have a proper object.
  if (o != NULL) {
    mmtk_post_alloc((MMTk_Mutator) context, o, bytes, allocator);
  }
  return o;
}

void MMTkMutatorContext_destroy(MMTkMutatorContext* context) {
  mmtk_destroy_mutator((MMTk_Mutator) context);
}

void MMTkMutatorContext_flush(MMTkMutatorContext* context) {
  mmtk_flush_mutator((MMTk_Mutator) context);
}

#endif