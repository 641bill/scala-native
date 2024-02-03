#if defined(SCALANATIVE_GC_EXPERIMENTAL)

#include "MutatorThread.h"
#include "State.h"
#include <stdlib.h>
#include <stdatomic.h>
#include <setjmp.h>
#include "shared/ThreadUtil.h"
#include <assert.h>

static mutex_t threadListsModifiactionLock;
volatile atomic_uint mutatorThreadsCounter = ATOMIC_VAR_INIT(0);

void MutatorThread_init(Field_t *stackbottom) {
    MutatorThread *self = (MutatorThread *)malloc(sizeof(MutatorThread));
    memset(self, 0, sizeof(MutatorThread));
    currentMutatorThread = self;

    self->stackBottom = stackbottom;
#ifdef _WIN32
    self->wakeupEvent = CreateEvent(NULL, true, false, NULL);
    if (self->wakeupEvent == NULL) {
        fprintf(stderr, "Failed to setup mutator thread: errno=%lu\n",
                GetLastError());
        exit(1);
    }
#else
    self->thread = pthread_self();
#endif
    MutatorThread_switchState(self, MutatorThreadState_Managed);
    MutatorThreads_add(self);
    // printf("Thread %p inited and added\n", self);
}

void GCThread_init(Field_t *stackbottom) {
    MutatorThread *self = (MutatorThread *)malloc(sizeof(MutatorThread));
    memset(self, 0, sizeof(MutatorThread));
    currentMutatorThread = self;

    self->stackBottom = stackbottom;
#ifdef _WIN32
    self->wakeupEvent = CreateEvent(NULL, true, false, NULL);
    if (self->wakeupEvent == NULL) {
        fprintf(stderr, "Failed to setup mutator thread: errno=%lu\n",
                GetLastError());
        exit(1);
    }
#else
    self->thread = pthread_self();
#endif
    // MutatorThreads_add(self);
}

void MutatorThread_delete(MutatorThread *self) {
    MutatorThread_switchState(self, MutatorThreadState_Unmanaged);
    MutatorThreads_remove(self);
#ifdef _WIN32
    CloseHandle(self->wakeupEvent);
#endif
    free(self->mutatorContext);
    free(self);
}

typedef word_t **volatile stackptr_t;

NOINLINE static stackptr_t MutatorThread_approximateStackTop() {
    volatile word_t sp;
    sp = (word_t)&sp;
    /* Also force stack to grow if necessary. Otherwise the later accesses might
     * cause the kernel to think we're doing something wrong. */
    return (stackptr_t)sp;
}

void MutatorThread_switchState(MutatorThread *self,
                               MutatorThreadState newState) {
    assert(self != null);
    if (newState == MutatorThreadState_Unmanaged) {
        // Dump registers to allow for their marking later
        setjmp(self->executionContext);
        self->stackTop = MutatorThread_approximateStackTop();
    } else {
        self->stackTop = NULL;
    }
    self->state = newState;
}

void MutatorThreads_init() { mutex_init(&threadListsModifiactionLock); }

void MutatorThreads_add(MutatorThread *node) {
    if (!node)
        return;
    MutatorThreads_lock();
    MutatorThreadNode *newNode =
        (MutatorThreadNode *)malloc(sizeof(MutatorThreadNode));
    newNode->value = node;
    newNode->next = mutatorThreads;
    mutatorThreads = newNode;
    MutatorThreads_unlock();
    atomic_fetch_add(&mutatorThreadsCounter, 1);
}

void MutatorThreads_remove(MutatorThread *node) {
    if (!node)
        return;

    MutatorThreads_lock();
    MutatorThreads current = mutatorThreads;
    if (current->value == node) { // expected is at head
        mutatorThreads = current->next;
        free(current);
    } else {
        while (current->next && current->next->value != node) {
            current = current->next;
        }
        MutatorThreads next = current->next;
        if (next) {
            current->next = next->next;
            free(next);
        }
    }
    MutatorThreads_unlock();
}

void MutatorThreads_lock() { mutex_lock(&threadListsModifiactionLock); }

void MutatorThreads_unlock() { mutex_unlock(&threadListsModifiactionLock); }

#endif