#ifndef MMTK_UPCALLS_H
#define MMTK_UPCALLS_H

#include "mmtk.h"

#ifdef __cplusplus
extern "C" {
#endif

extern ScalaNative_Upcalls mmtk_upcalls;
extern void invoke_MutatorClosure(MutatorClosure* closure, MMTk_Mutator mutator);
extern NewBuffer invoke_NodesClosure(NodesClosure* closure, void** buf, size_t size, size_t capa);
extern volatile atomic_uint mutatorThreadsCounter;

#ifdef __cplusplus
}
#endif

#endif // MMTK_UPCALLS_H
