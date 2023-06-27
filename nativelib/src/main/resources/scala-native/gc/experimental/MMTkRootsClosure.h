#ifndef MMTK_ROOTS_CLOSURE_H
#define MMTK_ROOTS_CLOSURE_H

#include "mmtk.h"

#ifdef __cplusplus
extern "C" {
#endif

void* create_MMTkRootsClosure(NodesClosure nodes_closure);
void do_work_MMTkRootsClosure(void* closure, void* p);
void delete_MMTkRootsClosure(void* closure);

#ifdef __cplusplus
}
#endif

#endif // MMTK_ROOTS_CLOSURE_H
