#if defined(SCALANATIVE_GC_EXPERIMENTAL)

#include "Bytemap.h"
#include <stdio.h>
#include "immix_commix/utils/MathUtils.h"

void Bytemap_Init(Bytemap *bytemap, word_t *firstAddress, size_t size) {
    bytemap->firstAddress = firstAddress;
    bytemap->size = size / ALLOCATION_ALIGNMENT;
    bytemap->end = &bytemap->data[bytemap->size];
    assert(Bytemap_index(bytemap, (word_t *)((ubyte_t *)(firstAddress) + size) -
                                      ALLOCATION_ALIGNMENT) < bytemap->size);
}

#endif