#if defined(SCALANATIVE_GC_EXPERIMENTAL)

#include "MMTkUpcalls.hpp"
// #define DEBUG 1

thread_local MMTk_GCThreadTLS *mmtk_gc_thread_tls;

/// Stop all the mutator threads. MMTk calls this method when it requires all the mutator to yield for a GC.
/// This method is called by a single thread in MMTk (the GC controller).
/// This method should not return until all the threads are yielded.
/// The actual thread synchronization mechanism is up to the VM, and MMTk does not make assumptions on that.
///
/// Arguments:
/// * `tls`: The thread pointer for the GC controller/coordinator.
/// If scan_mutators_in_safepoint is false, we will scan all the mutators here
static void mmtk_stop_all_mutators(void *tls, bool scan_mutators_in_safepoint, MutatorClosure closure) {
	#ifdef DEBUG
	printf("mmtk_stop_all_mutators\n");
	#endif
	
	// if (currentMutatorThread == NULL) {
	// 	int stackBottom = 0;
	// 	GCThread_init((Field_t *)&stackBottom);
	// } 

	Synchronizer_acquire();
	if (!scan_mutators_in_safepoint) {
		MutatorThreads_foreach(mutatorThreads, node) {
			MutatorThread *thread = node->value;
			invoke_MutatorClosure(&closure, thread->mutatorContext);
		}
	}
	// Print out the heap start and end, and the stack start and end
	printf("Heap start: %p\n", mmtk_starting_heap_address());
	printf("Heap end: %p\n", mmtk_last_heap_address());
	printf("Stack bottom: %p\n", currentMutatorThread->stackBottom);
	printf("Stack top: %p\n", currentMutatorThread->stackTop);
	printf("Reference stack bottom: %p\n", stack.bottom);
	printf("Some random local variable address on the stack: %p\n", &closure);
}

/// Resume all the mutator threads, the opposite of the above. When a GC is finished, MMTk calls this method.
///
/// Arguments:
/// * `tls`: The thread pointer for the GC controller/coordinator.
static void mmtk_resume_mutators(void *tls) {
	#ifdef DEBUG
	printf("mmtk_resume_mutators\n");
	#endif

	if (currentMutatorThread == NULL) {
		printf("GC Thread being inited\n");
		int stackBottom = 0;
		GCThread_init((Field_t *)&stackBottom);
	}

	Synchronizer_release();
}

/// Block the current thread for GC. This is called when an allocation request cannot be fulfilled and a GC
/// is needed. MMTk calls this method to inform the VM that the current thread needs to be blocked as a GC
/// is going to happen. Then MMTk starts a GC. For a stop-the-world GC, MMTk will then call `stop_all_mutators()`
/// before the GC, and call `resume_mutators()` after the GC.
///
/// Arguments:
/// * `tls`: The current thread pointer that should be blocked. The VM can optionally check if the current thread matches `tls`.
static void mmtk_block_for_gc(void *tls) {
	#ifdef DEBUG
	printf("mmtk_block_for_gc\n");
	#endif
	
	if ((void *)currentMutatorThread == tls) {
		#ifdef DEBUG
		printf("Thread %p is blocked for GC\n", tls);
		#endif
		Synchronizer_changeToCollecting();
		Synchronizer_wait();
	}
}

/// Inform the VM of an out-of-memory error. The binding should hook into the VM's error
/// routine for OOM. Note that there are two different categories of OOM:
///  * Critical OOM: This is the case where the OS is unable to mmap or acquire more memory.
///    MMTk expects the VM to abort immediately if such an error is thrown.
///  * Heap OOM: This is the case where the specified heap size is insufficient to execute the
///    application. MMTk expects the binding to notify the VM about this OOM. MMTk makes no
///    assumptions about whether the VM will continue executing or abort immediately.
///
/// See [`AllocationError`] for more information.
///
/// Arguments:
/// * `tls`: The thread pointer for the mutator which failed the allocation and triggered the OOM.
/// * `err_kind`: The type of OOM error that was encountered.
static void mmtk_out_of_memory(void *tls, MMTkAllocationError err_kind) {
	#ifdef DEBUG
	printf("mmtk_out_of_memory\n");
	#endif

	switch (err_kind) {
	case HeapOutOfMemory:
		fprintf(stderr, "Heap out of memory\n");
		exit(1);
	case MmapOutOfMemory:
		fprintf(stderr, "Mmap out of memory\n");
		exit(1);
	default:
		fprintf(stderr, "Unknown out of memory error\n");
		exit(1);
	}
}

/// Inform the VM to schedule finalization threads.
static void mmtk_schedule_finalizer() {
	// unimplemented
}

static int mmtk_get_object_array_id() {
	return __object_array_id;
}

static int mmtk_get_weak_ref_ids_min() {
	return __weak_ref_ids_min;
}

static int mmtk_get_weak_ref_ids_max() {
	return __weak_ref_ids_max;
}

static int mmtk_get_weak_ref_field_offset() {
	return __weak_ref_field_offset;
}

static int mmtk_get_array_ids_min() {
	return __array_ids_min;
}

static int mmtk_get_array_ids_max() {
	return __array_ids_max;
}

static size_t mmtk_get_allocation_alignment() {
	return ALLOCATION_ALIGNMENT;
}

static inline void mmtk_mark_lockWords(Heap *heap, Stack *stack,
                                        Object *object, MMTkRootsClosure &roots_closure);
static inline void mmtk_scan_lockWords(Heap *heap, Object *object, void* edge_visitor);

void mmtk_mark_object(Heap *heap, Stack *stack, Bytemap *bytemap,
                       Object *object, ObjectMeta *objectMeta, MMTkRootsClosure &roots_closure) {
    assert(ObjectMeta_IsAllocated(objectMeta));
    assert(object->rtti != NULL);

    mmtk_mark_lockWords(heap, stack, object, roots_closure);
    if (Object_IsWeakReference(object)) {
        // Added to the WeakReference stack for additional later visit
        Stack_Push(&weakRefStack, object);
    }

    assert(Object_Size(object) != 0);
		// Create the work packets for MMTk instead of marking in the runtime
		roots_closure.do_work(object);
    ObjectMeta_SetMarked(objectMeta);
    Stack_Push(stack, object);
}

static inline void mmtk_mark_field(Heap *heap, Stack *stack, Field_t field, MMTkRootsClosure &roots_closure) {
	if (Heap_IsWordInHeap(heap, field)) {
		ObjectMeta *fieldMeta = Bytemap_Get(heap->bytemap, field);
		if (ObjectMeta_IsAllocated(fieldMeta)) {
			Object *object = (Object *)field;
			mmtk_mark_object(heap, stack, heap->bytemap, object, fieldMeta, roots_closure);
		}
	}
}

void mmtk_mark_conservative(Heap *heap, Stack *stack, word_t *address, MMTkRootsClosure &roots_closure) {
	assert(Heap_IsWordInHeap(heap, address));
	Object *object = Object_GetUnmarkedObject(heap, address);
	Bytemap *bytemap = heap->bytemap;
	if (object != NULL) {
		ObjectMeta *objectMeta = Bytemap_Get(bytemap, (word_t *)object);
		assert(ObjectMeta_IsAllocated(objectMeta));
		if (ObjectMeta_IsAllocated(objectMeta)) {
			mmtk_mark_object(heap, stack, bytemap, object, objectMeta, roots_closure);
		}
	}
}

NO_SANITIZE void mmtk_mark_range(Heap *heap, Stack *stack, word_t **from,
                                  word_t **to, MMTkRootsClosure &roots_closure) {
	assert(from != NULL);
	assert(to != NULL);
	#ifdef MOVING
	// Have a vector storing the pinned objects of the current run
	std::vector<word_t*> current_pinned_objects;
	#endif
	for (word_t **current = from; current <= to; current += 1) {
		word_t *addr = *current;
		if (Heap_IsWordInHeap(heap, addr) && Bytemap_isPtrAligned(addr)) {
			#ifdef MOVING
			// Pin the object pointer by conservative roots
			if (mmtk_pin_object(addr)) {
				current_pinned_objects.push_back(addr);
			}
			#endif
			mmtk_mark_conservative(heap, stack, addr, roots_closure);
		}
	}
	#ifdef MOVING
	mmtk_append_pinned_objects(current_pinned_objects.data(), current_pinned_objects.size());
	#endif
}

void mmtk_mark_program_stack(MutatorThread *thread, Heap *heap, Stack *stack, MMTkRootsClosure &roots_closure) {
	word_t **stackBottom = thread->stackBottom;
	/* At this point ALL threads are stopped and their stackTop is not NULL -
		* that's the condition to exit Sychronizer_acquire However, for some
		* reasons I'm not aware of there are some rare situations upon which on the
		* first read of volatile stackTop it still would return NULL.
		* Due to the lack of alternatives or knowledge why this happends, just
		* retry to reach non-null state
		*/
	word_t **stackTop;
	do {
			stackTop = thread->stackTop;
	} while (stackTop == NULL);

	mmtk_mark_range(heap, stack, stackTop, stackBottom, roots_closure);

	// Mark last context of execution
	assert(thread->executionContext != NULL);
	word_t **regs = (word_t **)thread->executionContext;
	size_t regsSize = sizeof(jmp_buf) / sizeof(word_t *);
	mmtk_mark_range(heap, stack, regs, regs + regsSize, roots_closure);
}

/// Scan all the mutators for roots.
/// Apply the NodesClosure to every node (reference).
static void mmtk_scan_roots_in_all_mutator_threads(NodesClosure closure) {
	#ifdef DEBUG
	printf("mmtk_scan_roots_in_all_mutator_threads\n");
	#endif
	
	MMTkRootsClosure roots_closure(closure);
	atomic_thread_fence(memory_order_seq_cst);

	MutatorThreadNode *head = mutatorThreads;
	MutatorThreads_foreach(mutatorThreads, node) {
		MutatorThread *thread = node->value;
		mmtk_mark_program_stack(thread, &heap, &stack, roots_closure);
	}
}

/// Scan one mutator for roots.
static void mmtk_scan_roots_in_mutator_thread(NodesClosure closure, void *tls) {
	#ifdef DEBUG
	printf("mmtk_scan_roots_in_mutator_thread\n");
	#endif
	
	MutatorThread *thread = (MutatorThread*) tls;
	MMTkRootsClosure roots_closure(closure);
	mmtk_mark_program_stack(thread, &heap, &stack, roots_closure);
}

/* If compiling with enabled lock words check if object monitor is inflated and
 * can be marked. Otherwise, in singlethreaded mode this funciton is no-op
 */
static inline void mmtk_mark_lockWords(Heap *heap, Stack *stack,
                                        Object *object, MMTkRootsClosure &roots_closure) {
#ifdef USES_LOCKWORD
    if (object != NULL) {
        Field_t rttiLock = object->rtti->rt.lockWord;
        if (Field_isInflatedLock(rttiLock)) {
            mmtk_mark_field(heap, stack, Field_allignedLockRef(rttiLock), roots_closure);
        }

        Field_t objectLock = object->lockWord;
        if (Field_isInflatedLock(objectLock)) {
            Field_t field = Field_allignedLockRef(objectLock);
            mmtk_mark_field(heap, stack, field, roots_closure);
        }
    }
#endif
}

extern word_t *__modules;
extern int __modules_size;
#define LAST_FIELD_OFFSET -1

void mmtk_mark_modules(Heap *heap, Stack *stack, MMTkRootsClosure &roots_closure) {
	word_t **modules = &__modules;
	int nb_modules = __modules_size;
	#ifdef MOVING
	// Have a vector storing the pinned objects of the current run
	std::vector<word_t*> current_pinned_objects;
	#endif
	for (int i = 0; i < nb_modules; i++) {
		Object *object = (Object *)modules[i];
		#ifdef MOVING
		if (mmtk_pin_object((Field_t)object)) {
			current_pinned_objects.push_back((Field_t)object);
		}
		#endif
		mmtk_mark_field(heap, stack, (Field_t)object, roots_closure);
	}
	#ifdef MOVING
	mmtk_append_pinned_objects(current_pinned_objects.data(), current_pinned_objects.size());
	#endif
}

void mmtk_mark(Heap *heap, Stack *stack, MMTkRootsClosure &roots_closure) {
	Bytemap *bytemap = heap->bytemap;
	while (!Stack_IsEmpty(stack)) {
		Object *object = Stack_Pop(stack);
		if (Object_IsArray(object)) {
			if (object->rtti->rt.id == __object_array_id) {
				ArrayHeader *arrayHeader = (ArrayHeader *)object;
				size_t length = arrayHeader->length;
				word_t **fields = (word_t **)(arrayHeader + 1);
				for (int i = 0; i < length; i++) {
					mmtk_mark_field(heap, stack, fields[i], roots_closure);
				}
			}
			// non-object arrays do not contain pointers
		} else {
				int64_t *ptr_map = object->rtti->refMapStruct;
				for (int i = 0; ptr_map[i] != LAST_FIELD_OFFSET; i++) {
					if (Object_IsReferantOfWeakReference(object, ptr_map[i]))
						continue;
					mmtk_mark_field(heap, stack, object->fields[ptr_map[i]], roots_closure);
				}
		}
	}
}

void mmtk_scan_object(Heap *heap, Bytemap *bytemap,
                       Object *object, ObjectMeta *objectMeta, void* edge_visitor) {
    assert(ObjectMeta_IsAllocated(objectMeta));
    assert(object->rtti != NULL);

    mmtk_scan_lockWords(heap, object, edge_visitor);
    if (Object_IsWeakReference(object)) {
        // Added to the WeakReference stack for additional later visit
        Stack_Push(&weakRefStack, object);
    }

    assert(Object_Size(object) != 0);
		// Create the work packets for MMTk instead of marking in the runtime
		visit_edge(edge_visitor, object);
    ObjectMeta_SetMarked(objectMeta);
}

typedef struct {
  ArrayHeader header;
  int16_t value[];
} CharArray;

typedef struct { 
	// Extends Object, same as in the Object header
	Rtti *rtti;
#ifdef USES_LOCKWORD
	word_t *lockWord;
#endif
    // fields
	CharArray* value; // Array[Char] = _
	int offset; 
	int count;
	int cachedHashCode;
} StringObject;

static inline void mmtk_scan_field(Heap *heap, Field_t field, void* edge_visitor) {
	if (Heap_IsWordInHeap(heap, field)) {
		ObjectMeta *fieldMeta = Bytemap_Get(heap->bytemap, field);
		if (ObjectMeta_IsAllocated(fieldMeta)) {
			printf("Scanning field %p with content %p\n", field, *(word_t**)field);
			Object *object = (Object *)field;
			StringObject* string = (StringObject*)(object->rtti->rt.name);
			CharArray* charArr = string->value;
			// Create a string from the int16_t array.
			std::string str;
			for(size_t i = 0; i < charArr->header.length; i++) {
				// Append each int16_t as a char to the string
				if(charArr->value[i] >= 0 && charArr->value[i] < 128)
					str.push_back(static_cast<char>(charArr->value[i]));
			}
			// Print the string
			std::cout << "Field class name: " << str << std::endl;
			fflush(stdout);
			mmtk_scan_object(heap, heap->bytemap, object, fieldMeta, edge_visitor);
		}
	}
}

/* If compiling with enabled lock words check if object monitor is inflated and
 * can be marked. Otherwise, in singlethreaded mode this funciton is no-op
 */
static inline void mmtk_scan_lockWords(Heap *heap, Object *object, 
																				void* edge_visitor) {
#ifdef USES_LOCKWORD
    if (object != NULL) {
        Field_t rttiLock = object->rtti->rt.lockWord;
        if (Field_isInflatedLock(rttiLock)) {
            mmtk_scan_field(heap, Field_allignedLockRef(rttiLock), edge_visitor);
        }

        Field_t objectLock = object->lockWord;
        if (Field_isInflatedLock(objectLock)) {
            Field_t field = Field_allignedLockRef(objectLock);
            mmtk_scan_field(heap, field, edge_visitor);
        }
    }
#endif
}

static void mmtk_obj_iterate(const Object* obj, void* edge_visitor) {
	int64_t *ptr_map = obj->rtti->refMapStruct;
	for (int i = 0; ptr_map[i] != LAST_FIELD_OFFSET; i++) {
		if (Object_IsReferantOfWeakReference(&(*obj), ptr_map[i]))
			continue;
		mmtk_scan_field(&heap, obj->fields[ptr_map[i]], edge_visitor);
	}
}

static void mmtk_array_iterate(const ArrayHeader* array, void* edge_visitor) {
	Bytemap *bytemap = (&heap) -> bytemap;
	if (array->rtti->rt.id == __object_array_id) {
		size_t length = array->length;
		word_t **fields = (word_t **)(&array + 1);
		for (int i = 0; i < length; i++) {
			mmtk_scan_field(&heap, fields[i], edge_visitor);
		}
	}
	// non-object arrays do not contain pointers
}

/// Scan VM-specific roots. The creation of all root scan tasks (except thread scanning)
/// goes here.
static void mmtk_scan_vm_specific_roots(NodesClosure closure) {
	MMTkRootsClosure roots_closure(closure);
	mmtk_mark_modules(&heap, &stack, roots_closure);
	// mmtk_mark(&heap, &stack, roots_closure);
}

static void mmtk_prepare_for_roots_re_scanning() {
	// unimplemented
}

static void mmtk_get_mutators(MutatorClosure closure) {
	#ifdef DEBUG
	printf("mmtk_get_mutators\n");
	#endif

	MutatorThreads_foreach(mutatorThreads, node) {
		MutatorThread *thread = node->value;
		invoke_MutatorClosure(&closure, thread->mutatorContext);
	}
}

/// Return whether there is a mutator created and associated with the thread.
///
/// Arguments:
/// * `tls`: The thread to query.
///
/// # Safety
/// The caller needs to make sure that the thread is valid (a value passed in by the VM binding through API).
static bool mmtk_is_mutator(void* tls) {
	#ifdef DEBUG
	printf("mmtk_is_mutator: %d\n", tls != NULL ? ((MutatorThread*) tls)->third_party_heap_collector == NULL : false);
	#endif

  if (tls == NULL) return false;
  return ((MutatorThread*) tls)->third_party_heap_collector == NULL;
}

/// Return the total count of mutators.
static size_t mmtk_number_of_mutators() {
	return atomic_load(&mutatorThreadsCounter);
}

static void* mmtk_get_mmtk_mutator(void* tls) {
	#ifdef DEBUG
	printf("mmtk_get_mmtk_mutator\n");
	#endif

	return reinterpret_cast<MutatorThread*>(tls)->mutatorContext;
}

static void mmtk_init_gc_worker_thread(MMTk_VMWorkerThread gc_thread_tls) {
    mmtk_gc_thread_tls = gc_thread_tls;

		int stackBottom = 0;
		GCThread_init((Field_t *)&stackBottom);
		MutatorThread_switchState(currentMutatorThread, MutatorThreadState_Unmanaged);
}

static MMTk_VMWorkerThread mmtk_get_gc_thread_tls() {
    return mmtk_gc_thread_tls;
}

static void mmtk_init_synchronizer_thread() {
		int stackBottom = 0;
		GCThread_init((Field_t *)&stackBottom);
		MutatorThread_switchState(currentMutatorThread, MutatorThreadState_Unmanaged);
}

static void weak_ref_stack_nullify() {
	WeakRefStack_Nullify();
}

static void weak_ref_stack_call_handlers () {
	WeakRefStack_CallHandlers();
}

ScalaNative_Upcalls mmtk_upcalls = {
	// collection 
	mmtk_stop_all_mutators,
	mmtk_resume_mutators,
	mmtk_block_for_gc,
	mmtk_out_of_memory,
	mmtk_schedule_finalizer,
	// abi
	mmtk_get_object_array_id,
	mmtk_get_weak_ref_ids_min,
	mmtk_get_weak_ref_ids_max,
	mmtk_get_weak_ref_field_offset,
	mmtk_get_array_ids_min,
	mmtk_get_array_ids_max,
	mmtk_get_allocation_alignment,
	// scanning
	/// Scan all the mutators for roots.
	mmtk_scan_roots_in_all_mutator_threads,
	/// Scan one mutator for roots.
	mmtk_scan_roots_in_mutator_thread,
	mmtk_scan_vm_specific_roots,
	mmtk_prepare_for_roots_re_scanning,
	mmtk_obj_iterate,
	mmtk_array_iterate,
	weak_ref_stack_nullify,
	weak_ref_stack_call_handlers,
	// active_plan
	mmtk_get_mutators,
	mmtk_is_mutator,
	mmtk_number_of_mutators,
	mmtk_get_mmtk_mutator,
	mmtk_init_gc_worker_thread,
	mmtk_get_gc_thread_tls,
	mmtk_init_synchronizer_thread,
};

#endif