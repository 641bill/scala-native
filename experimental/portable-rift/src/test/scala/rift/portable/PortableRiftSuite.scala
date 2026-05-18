//> using dep "org.scalameta::munit:1.1.1"
//> using test.framework "munit.Framework"

package rift.portable

final class PortableRiftSuite extends munit.FunSuite:
  test("active epoch allocation succeeds") {
    val runtime = Rift.analysisOnly()
    val checksum = runtime.epoch { scope =>
      val value = scope.alloc(new PairRecord(7, 11L))
      value.key.toLong + value.value
    }
    assertEquals(checksum, 18L)
    assertEquals(runtime.stats.opens, 1L)
    assertEquals(runtime.stats.closes, 1L)
  }

  test("closed epoch allocation fails") {
    val runtime = Rift.analysisOnly()
    var stale: RiftScope | Null = null
    runtime.epoch { scope =>
      stale = scope
      scope.alloc(new Object)
    }
    intercept[IllegalStateException] {
      stale.nn.alloc(new Object)
    }
  }

  test("pooled records are cleared and reused across epochs") {
    val runtime = Rift.jvmPoolArena()
    val pool = new ObjectPool(() => new PairRecord(0, 0L))
    var first: PairRecord | Null = null
    runtime.epoch { scope =>
      first = scope.borrow(pool)(_.set(1, 2L))
      assertEquals(first.nn.key, 1)
      assertEquals(first.nn.value, 2L)
    }
    assertEquals(pool.cached, 1)

    runtime.epoch { scope =>
      val second = scope.borrow(pool) { record =>
        assertEquals(record.key, 0)
        assertEquals(record.value, 0L)
        record.set(3, 4L)
      }
      assert(first.nn eq second)
    }
    assertEquals(runtime.stats.pooledFreshAllocs, 1L)
    assertEquals(runtime.stats.pooledReuses, 1L)
  }

  test("heap fallback preserves retained epoch checksum") {
    val heap = Rift.heapFallback()
    val analysis = Rift.analysisOnly()
    val heapResult = PortableRiftWorkloads.retainedEpoch(heap, 20000, 5000, usePool = false)
    val analysisResult = PortableRiftWorkloads.retainedEpoch(analysis, 20000, 5000, usePool = false)
    assertEquals(analysisResult, heapResult)
  }

  test("nested epochs close in order and stale child scope fails") {
    val runtime = Rift.jvmPoolArena()
    var outerStale: RiftScope | Null = null
    var innerStale: RiftScope | Null = null
    runtime.epoch { outer =>
      outerStale = outer
      outer.epoch { inner =>
        innerStale = inner
        inner.alloc(new Object)
      }
      intercept[IllegalStateException] {
        innerStale.nn.alloc(new Object)
      }
    }
    intercept[IllegalStateException] {
      outerStale.nn.alloc(new Object)
    }
    assertEquals(runtime.stats.opens, 2L)
    assertEquals(runtime.stats.closes, 2L)
  }

  test("exceptions close epoch scopes") {
    val runtime = Rift.analysisOnly()
    var stale: RiftScope | Null = null
    intercept[RuntimeException] {
      runtime.epoch { scope =>
        stale = scope
        throw new RuntimeException("boom")
      }
    }
    assertEquals(runtime.stats.closes, 1L)
    intercept[IllegalStateException] {
      stale.nn.alloc(new Object)
    }
  }

  test("explicit roots and static metadata are accepted") {
    val runtime = Rift.rootFreeJvmPoolArena()
    val schema = Rift.staticMetadata("schema-v1")
    val root = Rift.root(new StringBuilder("durable"))
    runtime.epoch { scope =>
      assertEquals(scope.acceptStatic(schema), "schema-v1")
      assertEquals(scope.acceptRoot(root).toString, "durable")
    }
    assertEquals(runtime.stats.staticMetadataRefs, 1L)
    assertEquals(runtime.stats.heapRootRefs, 1L)
  }

  test("root-free eligible path rejects unwrapped dynamic heap metadata") {
    val runtime = Rift.rootFreeJvmPoolArena()
    intercept[IllegalArgumentException] {
      runtime.epoch { scope =>
        scope.acceptDynamicHeap(new StringBuilder("mutable"))
      }
    }
    assertEquals(runtime.stats.closes, 1L)
  }

  test("stale scope hidden in closure fails at use time") {
    val runtime = Rift.analysisOnly()
    val closure = runtime.epoch { scope =>
      () => scope.alloc(new PairRecord(1, 2L))
    }
    intercept[IllegalStateException] {
      closure()
    }
  }

  test("wasm model resets linear-memory cursor at epoch close") {
    val runtime = Rift.wasmLinearMemory(initialBytes = 64)
    val first = runtime.epoch { scope =>
      scope.allocBytes(16).offset
    }
    val second = runtime.epoch { scope =>
      scope.allocBytes(16).offset
    }
    assertEquals(first, 0)
    assertEquals(second, 0)
    assertEquals(runtime.stats.arenaAllocations, 2L)
  }
