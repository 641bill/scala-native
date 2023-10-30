#ifndef IMMIX_STATE_H
#define IMMIX_STATE_H

#ifdef __cplusplus
extern "C" {
#endif
#include "Heap.h"
#include "shared/ThreadUtil.h"
#include "MutatorThread.h"
#include "shared/Safepoint.h"
#include "immix_commix/GCRoots.h"
#include "stddef.h"
#include "MMTkMutator.hpp"
#ifdef __cplusplus
}
#endif


extern Heap heap;
extern Stack stack;
extern Stack weakRefStack;
extern BlockAllocator blockAllocator;
extern MutatorThreads mutatorThreads;
extern thread_local MutatorThread *currentMutatorThread;
extern safepoint_t scalanative_gc_safepoint;
extern GC_Roots *roots;
extern thread_local void* third_party_heap_collector;
extern thread_local BumpPointer* immix_bump_pointer;

#endif // IMMIX_STATE_H
