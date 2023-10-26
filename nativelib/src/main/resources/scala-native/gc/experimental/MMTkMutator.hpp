#ifndef MMTK_MUTATOR_HPP
#define MMTK_MUTATOR_HPP

#ifdef __cplusplus
extern "C" {
#endif

#include "mmtk.h"
#include <assert.h>

enum MMTkAllocator {
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

// These constants should match the constants defind in mmtk::util::alloc::allocators
#define MAX_BUMP_ALLOCATORS 6
#define MAX_LARGE_OBJECT_ALLOCATORS 2
#define MAX_MALLOC_ALLOCATORS 1
#define MAX_IMMIX_ALLOCATORS 1
#define MAX_FREE_LIST_ALLOCATORS 2
#define MAX_MARK_COMPACT_ALLOCATORS 1

// The following types should have the same layout as the types with the same name in MMTk core (Rust)

struct BumpAllocator {
  void* tls;
  void* cursor;
  void* limit;
  struct RustDynPtr space;
  void* context;
};

struct LargeObjectAllocator {
  void* tls;
  void* space;
  void* context;
};

struct ImmixAllocator {
  void* tls;
  void* cursor;
  void* limit;
  void* immix_space;
  void* context;
  uint8_t hot;
  uint8_t copy;
  void* large_cursor;
  void* large_limit;
  uint8_t request_for_large;
  uint8_t _align[7];
  uint8_t line_opt_tag;
  uintptr_t line_opt;
};

struct FLBlock {
  void* Address;
};

struct FLBlockList {
  struct FLBlock first;
  struct FLBlock last;
  size_t size;
  char lock;
};

struct FreeListAllocator {
  void* tls;
  void* space;
  void* context;
  struct FLBlockList* available_blocks;
  struct FLBlockList* available_blocks_stress;
  struct FLBlockList* unswept_blocks;
  struct FLBlockList* consumed_blocks;
};

struct MallocAllocator {
  void* tls;
  void* space;
  struct RustDynPtr plan;
};

struct MarkCompactAllocator {
  struct BumpAllocator bump_allocator;
};

struct Allocators {
  struct BumpAllocator bump_pointer[MAX_BUMP_ALLOCATORS];
  struct LargeObjectAllocator large_object[MAX_LARGE_OBJECT_ALLOCATORS];
  struct MallocAllocator malloc[MAX_MALLOC_ALLOCATORS];
  struct ImmixAllocator immix[MAX_IMMIX_ALLOCATORS];
  struct FreeListAllocator free_list[MAX_FREE_LIST_ALLOCATORS];
  struct MarkCompactAllocator markcompact[MAX_MARK_COMPACT_ALLOCATORS];
};

struct MutatorConfig {
  void* allocator_mapping;
  void* space_mapping;
  struct RustDynPtr prepare_func;
  struct RustDynPtr release_func;
};

// An opaque type, so that HeapWord* can be a generic pointer into the heap.
// We require that object sizes be measured in units of heap words (e.g.
// pointer-sized values), so that given HeapWord* hw,
//   hw += oop(hw)->foo();
// works, where foo is a method (like size or scavenge) that returns the
// object size.
struct HeapWordImpl;             // Opaque, never defined.
typedef struct HeapWordImpl* HeapWord;

typedef struct {
  struct Allocators allocators;
  struct RustDynPtr barrier;
  void* mutator_tls;
  struct RustDynPtr plan;
  struct MutatorConfig config;
} MMTkMutatorContext;

// Max object size that does not need to go into LOS. We get the value from mmtk-core, and cache its value here.
extern size_t max_non_los_default_alloc_bytes;
extern const int HeapWordSize;

HeapWord* MMTkMutatorContext_alloc(MMTkMutatorContext* context, size_t bytes, enum MMTkAllocator allocator);
void MMTkMutatorContext_flush(MMTkMutatorContext* context);
void MMTkMutatorContext_destroy(MMTkMutatorContext* context);
MMTkMutatorContext MMTkMutatorContext_bind(void* current);
int MMTkMutatorContext_is_ready_to_bind();

#ifdef __cplusplus
} 
#endif

#endif // MMTK_MUTATOR_HPP
