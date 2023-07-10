#ifdef __cplusplus
extern "C" {
#endif
#include <ScalaNativeGC.h>
#include "GCTypes.h"
#include "Allocator.h"
#include "LargeAllocator.h"
#include "State.h"
#include "Safepoint.h"
#include <stdatomic.h>
#include <ThreadUtil.h>
#include <setjmp.h>
#include "MMTkMutator.hpp"
#include <stdatomic.h>
#ifdef __cplusplus
}
#endif

#ifndef MUTATOR_THREAD_H
#define MUTATOR_THREAD_H

#ifdef __cplusplus
extern "C" {
#endif

typedef struct {
    volatile MutatorThreadState state;
    word_t **volatile stackTop;
    volatile atomic_bool isWaiting;
    jmp_buf executionContext;
    // immutable fields
    word_t **stackBottom;
    Allocator allocator;
    LargeAllocator largeAllocator;
#ifdef _WIN32
    HANDLE wakeupEvent;
#else
    thread_t thread;
#endif
    MMTkMutatorContext *mutatorContext;
    void* third_party_heap_collector;
} MutatorThread;

typedef struct MutatorThreadNode {
    MutatorThread *value;
    struct MutatorThreadNode *next;
} MutatorThreadNode;

// Counter for threads
extern volatile atomic_uint mutatorThreadsCounter;

typedef MutatorThreadNode *MutatorThreads;

void MutatorThread_init(word_t **stackBottom);
void GCThread_init(word_t **stackBottom);
void MutatorThread_delete(MutatorThread *self);
void MutatorThread_switchState(MutatorThread *self,
                               MutatorThreadState newState);

void MutatorThreads_init();
void MutatorThreads_add(MutatorThread *node);
void MutatorThreads_remove(MutatorThread *node);
void MutatorThreads_lock();
void MutatorThreads_unlock();

#define MutatorThreads_foreach(list, node)                                     \
    for (MutatorThreads node = list; node != NULL; node = node->next)

#ifdef __cplusplus
}
#endif

#endif // MUTATOR_THREAD_H
