#ifndef WEAK_REF_STACK_H
#define WEAK_REF_STACK_H
#include "Object.h"
#include "Heap.h"
#include "mmtk.h"

void WeakRefStack_Nullify(void);
void WeakRefStack_SetHandler(void *handler);
void WeakRefStack_CallHandlers(void);
void WeakRefStack_display(void);

#endif // WEAK_REF_STACK_H
