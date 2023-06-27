#ifndef MMTK_ROOTS_CLOSURE_HPP
#define MMTK_ROOTS_CLOSURE_HPP

#include "MMTkRootsClosure.h"

#define ROOTS_BUFFER_SIZE 4096

class MMTkRootsClosure {
  NodesClosure _nodes_closure;
  void** _buffer;
  size_t _cap;
  size_t _cursor;

public:
  template <class T>
  void do_work(T* p) {
    _buffer[_cursor++] = (void*) p;
    if (_cursor >= _cap) {
      flush();
    }
  }

  void flush();

  MMTkRootsClosure(NodesClosure nodes_closure);

  ~MMTkRootsClosure();
};

#endif // MMTK_ROOTS_CLOSURE_HPP
