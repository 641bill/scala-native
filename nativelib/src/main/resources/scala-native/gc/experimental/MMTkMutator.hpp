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

typedef struct {
  void* data;
  void* vtable;
} RustDynPtr;

// These constants should match the constants defind in mmtk::util::alloc::allocators
#define MAX_BUMP_ALLOCATORS 6
#define MAX_LARGE_OBJECT_ALLOCATORS 2
#define MAX_MALLOC_ALLOCATORS 1
#define MAX_IMMIX_ALLOCATORS 1
#define MAX_FREE_LIST_ALLOCATORS 2
#define MAX_MARK_COMPACT_ALLOCATORS 1

// The following types should have the same layout as the types with the same name in MMTk core (Rust)

typedef struct {
  void* tls;
  void* cursor;
  void* limit;
  RustDynPtr space;
  void* context;
} BumpAllocator;

typedef struct {
    uintptr_t cursor;
    uintptr_t limit;
} BumpPointer;

typedef struct {
  void* tls;
  void* space;
  void* context;
} LargeObjectAllocator;

typedef struct {
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
} ImmixAllocator;

typedef struct {
  void* Address;
} FLBlock;

typedef struct {
  FLBlock first;
  FLBlock last;
  size_t size;
  char lock;
} FLBlockList;

typedef struct {
  void* tls;
  void* space;
  void* context;
  FLBlockList* available_blocks;
  FLBlockList* available_blocks_stress;
  FLBlockList* unswept_blocks;
  FLBlockList* consumed_blocks;
} FreeListAllocator;

typedef struct {
  void* tls;
  void* space;
  void* context;
} MallocAllocator;

typedef struct {
  BumpAllocator bump_allocator;
} MarkCompactAllocator;

typedef struct  {
  BumpAllocator bump_pointer[MAX_BUMP_ALLOCATORS];
  LargeObjectAllocator large_object[MAX_LARGE_OBJECT_ALLOCATORS];
  MallocAllocator malloc[MAX_MALLOC_ALLOCATORS];
  ImmixAllocator immix[MAX_IMMIX_ALLOCATORS];
  FreeListAllocator free_list[MAX_FREE_LIST_ALLOCATORS];
  MarkCompactAllocator markcompact[MAX_MARK_COMPACT_ALLOCATORS];
} Allocators;

typedef struct {
  void* allocator_mapping;
  void* space_mapping;
  RustDynPtr prepare_func;
  RustDynPtr release_func;
} MutatorConfig;

// An opaque type, so that HeapWord* can be a generic pointer into the heap.
// We require that object sizes be measured in units of heap words (e.g.
// pointer-sized values), so that given HeapWord* hw,
//   hw += oop(hw)->foo();
// works, where foo is a method (like size or scavenge) that returns the
// object size.
struct HeapWordImpl;             // Opaque, never defined.
typedef struct HeapWordImpl* HeapWord;

typedef struct {
  Allocators allocators;
  RustDynPtr barrier;
  void* mutator_tls;
  RustDynPtr plan;
  MutatorConfig config;
} MMTkMutatorContext;

extern const int HeapWordSize;
// Max object size that does not need to go into LOS. We get the value from mmtk-core, and cache its value here.
extern size_t max_non_los_default_alloc_bytes;
extern size_t immix_bump_ptr_offset;
extern uintptr_t mmtk_vo_bit_log_region_size;
extern uintptr_t mmtk_vo_bit_base_addr;

HeapWord* MMTkMutatorContext_alloc(MMTkMutatorContext* context, size_t bytes, enum MMTkAllocator allocator);
void MMTkMutatorContext_flush(MMTkMutatorContext* context);
void MMTkMutatorContext_destroy(MMTkMutatorContext* context);
MMTkMutatorContext MMTkMutatorContext_bind(void* current);
int MMTkMutatorContext_is_ready_to_bind();

#ifdef __cplusplus
} 
#endif

#endif // MMTK_MUTATOR_HPP
