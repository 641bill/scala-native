#if defined(SCALANATIVE_GC_EXPERIMENTAL)

#include "State.h"

Heap heap = {};
Stack stack = {};
Stack weakRefStack = {};
BlockAllocator blockAllocator = {};
MutatorThreads mutatorThreads = NULL;
thread_local MutatorThread *currentMutatorThread = NULL;
safepoint_t scalanative_gc_safepoint = NULL;
GC_Roots *roots = NULL;
thread_local void* third_party_heap_collector = NULL;
thread_local BumpPointer* immix_bump_pointer = NULL;

#endif