#ifndef MMTK_UPCALLS_HPP
#define MMTK_UPCALLS_HPP

extern "C" {
#include <stdlib.h>
#include <pthread.h>
#include "immix_commix/headers/ObjectHeader.h"
#include "Heap.h"
#include "Marker.h"
#include "MutatorThread.h"
#include "Synchronizer.h"
#include "MMTkUpcalls.h"
}
#include "MMTkRootsClosure.hpp"

#endif // MMTK_UPCALLS_HPP
