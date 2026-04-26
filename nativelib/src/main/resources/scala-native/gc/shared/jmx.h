#ifndef GC_JMX_H
#define GC_JMX_H

#include <stdlib.h>

size_t jmx_stats_get_collection_total();
size_t jmx_stats_get_collection_duration_total();
void jmx_stats_record_collection(size_t start_ns, size_t end_ns);
void jmx_stats_init_allocation();
int jmx_stats_allocation_enabled();
void jmx_stats_record_allocation(size_t bytes, size_t start_ns, size_t end_ns);
size_t jmx_stats_get_allocation_total();
size_t jmx_stats_get_allocation_bytes_total();
size_t jmx_stats_get_allocation_duration_total();
void jmx_stats_set_allocation_phase(int phase);
size_t jmx_stats_get_phase_allocation_total(int phase);
size_t jmx_stats_get_phase_allocation_bytes_total(int phase);
size_t jmx_stats_get_phase_allocation_duration_total(int phase);

#endif
