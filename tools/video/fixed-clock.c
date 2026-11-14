// A fixed wall clock for the Mac capture mode, so every still shows the story's date and time whenever it is made.
// Loaded only into the throwaway capture app (tools/video/mac-shots.sh, DYLD_INSERT_LIBRARIES); never part of the app.
// MND_CLOCK="2026-11-12T18:40:00" (local time). The clock starts there and runs on normally.
#include <stdlib.h>
#include <sys/time.h>
#include <time.h>

static double offset_seconds(void) {
    static int ready = 0;
    static double offset = 0;
    if (!ready) {
        ready = 1;
        const char *s = getenv("MND_CLOCK");
        struct tm tm = {0};
        if (s && strptime(s, "%Y-%m-%dT%H:%M:%S", &tm)) {
            tm.tm_isdst = -1;
            struct timeval now;
            gettimeofday(&now, NULL);
            offset = (double)mktime(&tm) - (double)now.tv_sec;
        }
    }
    return offset;
}

static int fixed_gettimeofday(struct timeval *tv, void *tz) {
    int r = gettimeofday(tv, tz);
    if (r == 0 && tv) tv->tv_sec += (time_t)offset_seconds();
    return r;
}
static int fixed_clock_gettime(clockid_t id, struct timespec *ts) {
    int r = clock_gettime(id, ts);
    if (r == 0 && ts && id == CLOCK_REALTIME) ts->tv_sec += (time_t)offset_seconds();
    return r;
}
static time_t fixed_time(time_t *out) {
    time_t t = time(NULL) + (time_t)offset_seconds();
    if (out) *out = t;
    return t;
}
static uint64_t fixed_clock_gettime_nsec_np(clockid_t id) {
    uint64_t n = clock_gettime_nsec_np(id);
    return id == CLOCK_REALTIME ? n + (uint64_t)((int64_t)offset_seconds() * 1000000000LL) : n;
}

#define INTERPOSE(new, old) __attribute__((used)) static struct { const void *n, *o; } _i_##old \
    __attribute__((section("__DATA,__interpose"))) = { (const void *)&new, (const void *)&old }
INTERPOSE(fixed_gettimeofday, gettimeofday);
INTERPOSE(fixed_clock_gettime, clock_gettime);
INTERPOSE(fixed_time, time);
INTERPOSE(fixed_clock_gettime_nsec_np, clock_gettime_nsec_np);
