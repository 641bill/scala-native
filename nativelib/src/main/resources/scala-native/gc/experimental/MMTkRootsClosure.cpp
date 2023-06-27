#include "MMTkRootsClosure.hpp"

void MMTkRootsClosure::flush() {
  if (_cursor > 0) {
    NewBuffer buf = invoke_NodesClosure(&_nodes_closure, _buffer, _cursor, _cap);
    _buffer = buf.buf;
    _cap = buf.cap;
    _cursor = 0;
  }
}

MMTkRootsClosure::MMTkRootsClosure(NodesClosure nodes_closure)
: _nodes_closure(nodes_closure), _cursor(0) {
  NewBuffer buf = invoke_NodesClosure(&nodes_closure, NULL, 0, 0);
  _buffer = buf.buf;
  _cap = buf.cap;
}

MMTkRootsClosure::~MMTkRootsClosure() {
  if (_cursor > 0) flush();
  if (_buffer != NULL) {
    release_buffer(_buffer, _cursor, _cap);
  }
}

extern "C" {
  void* create_MMTkRootsClosure(NodesClosure nodes_closure) {
    // Create a new MMTkRootsClosure and return its pointer.
    return new MMTkRootsClosure(nodes_closure);
  }

  void do_work_MMTkRootsClosure(void* closure, void* p) {
    // Cast the void pointers back to their original types and call do_work.
    reinterpret_cast<MMTkRootsClosure*>(closure)->do_work(p);
  }

  void delete_MMTkRootsClosure(void* closure) {
    // Cast the void pointer back to MMTkRootsClosure* and delete it.
    delete reinterpret_cast<MMTkRootsClosure*>(closure);
  }
}