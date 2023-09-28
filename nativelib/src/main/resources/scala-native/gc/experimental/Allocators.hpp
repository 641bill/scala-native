#ifndef ALLOCATORS_HPP
#define ALLOCATORS_HPP

enum Allocator {
  AllocatorDefault = 0,
  AllocatorImmortal = 1,
  AllocatorLos = 2,
  AllocatorCode = 3,
  AllocatorReadOnly = 4,
};

typedef struct RustDynPtr {
  void* data;
  void* vtable;
};

typedef struct BumpAllocator {
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

#endif