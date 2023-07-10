#include "mmtk.h"

void invoke_MutatorClosure(MutatorClosure* closure, MMTk_Mutator mutator) {
    invoke_mutator_closure(closure, mutator);
}

NewBuffer invoke_EdgesClosure(EdgesClosure* closure, void** buf, size_t size, size_t capa) {
    return closure->func(buf, size, capa, closure->data);
}

NewBuffer invoke_NodesClosure(NodesClosure* closure, void** buf, size_t size, size_t capa) {
    return closure->func(buf, size, capa, closure->data);
}
