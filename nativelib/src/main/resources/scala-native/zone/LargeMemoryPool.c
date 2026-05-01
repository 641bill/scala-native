#if defined(SCALANATIVE_COMPILE_ALWAYS) ||                                     \
    defined(__SCALANATIVE_MEMORY_SAFEZONE)
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include "LargeMemoryPool.h"
#include "MemoryPool.h"
#include "shared/ScalaNativeGC.h"
#include "shared/MemoryMap.h"
#include "Util.h"

static MemoryPage *LargeMemoryPool_merge_sorted_pages(MemoryPage *a,
                                                      MemoryPage *b) {
    MemoryPage *head = NULL;
    MemoryPage **tail = &head;
    while (a != NULL && b != NULL) {
        if ((uintptr_t)a->start <= (uintptr_t)b->start) {
            *tail = a;
            a = a->next;
        } else {
            *tail = b;
            b = b->next;
        }
        tail = &((*tail)->next);
    }
    *tail = (a != NULL) ? a : b;
    return head;
}

static MemoryPage *LargeMemoryPool_sort_pages_by_start(MemoryPage *head) {
    if (head == NULL || head->next == NULL) {
        return head;
    }
    MemoryPage *slow = head;
    MemoryPage *fast = head->next;
    while (fast != NULL && fast->next != NULL) {
        slow = slow->next;
        fast = fast->next->next;
    }
    MemoryPage *mid = slow->next;
    slow->next = NULL;
    MemoryPage *left = LargeMemoryPool_sort_pages_by_start(head);
    MemoryPage *right = LargeMemoryPool_sort_pages_by_start(mid);
    return LargeMemoryPool_merge_sorted_pages(left, right);
}

LargeMemoryPool *LargeMemoryPool_open() {
    LargeMemoryPool *largePool = malloc(sizeof(LargeMemoryPool));
    largePool->page = NULL;
    return largePool;
}

void LargeMemoryPool_alloc_page(LargeMemoryPool *largePool, size_t size) {
    const int rootsMode = MemoryPool_roots_mode();
    MemoryPage *page = malloc(sizeof(MemoryPage));
    page->start = memoryMapOrExitOnError(size);
    page->offset = 0;
    page->size = size;
    if (rootsMode == MEMORYPOOL_ROOTS_CHUNK) {
        scalanative_GC_add_roots(page->start, page->start + page->size);
    }
    page->next = largePool->page;
    largePool->page = page;
}

MemoryPage *LargeMemoryPool_claim(LargeMemoryPool *largePool, size_t size) {
    MemoryPage *result = NULL;
    if (largePool->page == NULL) {
        LargeMemoryPool_alloc_page(largePool, size);
        result = largePool->page;
    } else if (largePool->page->size < size) {
        MemoryPage *page = largePool->page, *prePage = NULL;
        while (page != NULL) {
            if (page->size >= size) {
                result = page;
                break;
            }
            prePage = page;
            page = page->next;
        }
        if (result != NULL) {
            prePage->next = result->next;
            result->next = largePool->page;
        } else {
            LargeMemoryPool_alloc_page(largePool, size);
            result = largePool->page;
        }
    } else {
        result = largePool->page;
    }
    largePool->page = result->next;
    result->next = NULL;
    result->offset = 0;
    const int rootsMode = MemoryPool_roots_mode();
    if (rootsMode != MEMORYPOOL_ROOTS_CHUNK &&
        rootsMode != MEMORYPOOL_ROOTS_UNSAFE_NO_ROOTS) {
        scalanative_GC_add_roots(result->start, result->start + result->size);
    }
    return result;
}

void LargeMemoryPool_reclaim(LargeMemoryPool *largePool, MemoryPage *head) {
    const int rootsMode = MemoryPool_roots_mode();
    MemoryPage *reclaimHead = head;
    MemoryPage *page = reclaimHead, *tail = NULL;
    if (rootsMode == MEMORYPOOL_ROOTS_IMPROVED) {
        reclaimHead = LargeMemoryPool_sort_pages_by_start(reclaimHead);
        page = reclaimHead;
        while (page != NULL) {
            MemoryPage *runTail = page;
            char *rangeStart = (char *)page->start;
            char *rangeEnd = rangeStart + page->size;
            while (runTail->next != NULL &&
                   rangeEnd == (char *)runTail->next->start) {
                runTail = runTail->next;
                rangeEnd = (char *)runTail->start + runTail->size;
            }
            scalanative_GC_remove_roots(rangeStart, rangeEnd);
            tail = runTail;
            page = runTail->next;
        }
    } else if (rootsMode == MEMORYPOOL_ROOTS_CURRENT) {
        while (page != NULL) {
            scalanative_GC_remove_roots(page->start, page->start + page->size);
            tail = page;
            page = page->next;
        }
    } else {
        while (page != NULL) {
            tail = page;
            page = page->next;
        }
    }
    if (tail != NULL) {
        tail->next = largePool->page;
        largePool->page = reclaimHead;
    }
}

void LargeMemoryPool_close(LargeMemoryPool *largePool) {
    const int rootsMode = MemoryPool_roots_mode();
    MemoryPage *page = largePool->page, *prePage = NULL;
    while (page != NULL) {
        prePage = page;
        page = page->next;
        if (rootsMode == MEMORYPOOL_ROOTS_CHUNK) {
            scalanative_GC_remove_roots(prePage->start,
                                        prePage->start + prePage->size);
        }
        memoryUnmapOrExitOnError(prePage->start, prePage->size);
        free(prePage);
    }
    free(largePool);
}
#endif
