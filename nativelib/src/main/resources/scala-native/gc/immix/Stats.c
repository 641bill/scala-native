#if defined(SCALANATIVE_GC_IMMIX)

#ifdef _WIN32
// fopen is deprecated in WinCRT, disable warnings
#define _CRT_SECURE_NO_WARNINGS
#endif

#include "Stats.h"
#include <stdio.h>
#include <inttypes.h>

void Stats_writeToFile(Stats *stats);

void Stats_Init(Stats *stats, const char *statsFile) {
    stats->outFile = fopen(statsFile, "w");
    fprintf(stats->outFile, "mark_time_ns,nullify_time_ns,sweep_time_ns\n");
    stats->collections = 0;
}

void Stats_RecordCollection(Stats *stats, uint64_t start_ns,
                            uint64_t nullify_start_ns, uint64_t sweep_start_ns,
                            uint64_t end_ns) {
    uint64_t index = stats->collections % STATS_MEASUREMENTS;
    stats->mark_time_ns[index] = nullify_start_ns - start_ns;
    stats->nullify_time_ns[index] = sweep_start_ns - nullify_start_ns;
    stats->sweep_time_ns[index] = end_ns - sweep_start_ns;
    stats->collections += 1;
    if (stats->collections % STATS_MEASUREMENTS == 0) {
        Stats_writeToFile(stats);
    }
}

void Stats_writeToFile(Stats *stats) {
    uint64_t collections = stats->collections;
    uint64_t remainder = collections % STATS_MEASUREMENTS;
    if (remainder == 0) {
        remainder = STATS_MEASUREMENTS;
    }
    FILE *outFile = stats->outFile;
    for (uint64_t i = 0; i < remainder; i++) {
        fprintf(outFile, "%" PRIu64 ",%" PRIu64 ",%" PRIu64 "\n",
                stats->mark_time_ns[i], stats->nullify_time_ns[i],
                stats->sweep_time_ns[i]);
    }
    fflush(outFile);
}

void Stats_OnExit(Stats *stats) {
    if (stats != NULL) {
        uint64_t remainder = stats->collections % STATS_MEASUREMENTS;
        if (remainder > 0) {
            // there were some measurements not written in the last full batch.
            Stats_writeToFile(stats);
        }
        // Add every number in the file and append it to the end of the file
        FILE *outFile = stats->outFile;
        fprintf(outFile, "\n");
        uint64_t mark_time_ns = 0;
        uint64_t nullify_time_ns = 0;
        uint64_t sweep_time_ns = 0;
        uint64_t collections = stats->collections;
        uint64_t remainder2 = collections % STATS_MEASUREMENTS;
        if (remainder2 == 0) {
            remainder2 = STATS_MEASUREMENTS;
        }
        for (uint64_t i = 0; i < remainder2; i++) {
            mark_time_ns += stats->mark_time_ns[i];
            nullify_time_ns += stats->nullify_time_ns[i];
            sweep_time_ns += stats->sweep_time_ns[i];
        }
        // Add all of them together
        uint64_t total_time_ns = mark_time_ns + nullify_time_ns + sweep_time_ns;
        uint64_t total_time_ms = total_time_ns / 1000000;
        // Append to file
        fprintf(outFile, "Total time (ms): %" PRIu64 "\n", total_time_ms);
        fclose(stats->outFile);
    }
}

#endif
